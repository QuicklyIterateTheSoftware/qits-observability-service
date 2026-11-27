package eu.wohlben.qits.telemetry.control;

import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.issues.ErrorIssueDelta;
import eu.wohlben.qits.telemetry.issues.ErrorIssues;
import eu.wohlben.qits.telemetry.sourcemap.BrowserStackMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Folds fingerprinted ERROR logs into error issues (epic qits-2538, dossier page "Error issue store
 * and API").
 *
 * <p><b>Ingest never waits on the database.</b> {@link #offer} is called by the receiver after the
 * store append and the live-feed publish. It touches no database and takes one short in-memory lock:
 * it folds the batch into a map keyed by fingerprint, holding per fingerprint the count, the
 * per-hour counts, the earliest and latest record time with their versions, and the newest sample
 * message, stack trace and trace id. Every {@value #FLUSH_INTERVAL_KEY} (10 s) {@link #flush} swaps
 * the map out and hands it to the {@link ErrorIssueSink} in one transaction. The cost, accepted: a
 * crash loses up to one window of counts.
 *
 * <p><b>Bounded.</b> At most {@value #MAX_PENDING_KEY} (1000) distinct fingerprints are held per
 * window; an occurrence of one more is dropped and counted. A flush that fails drops its window
 * and is counted too. Both log at <b>WARN, at most once a minute, never at ERROR</b>: this service's
 * own ERROR logs feed its own issues, so an ERROR here would feed itself.
 *
 * <p>A record's time is its own ({@code epochNanos}), or the ingest stamp when the exporter left it
 * at zero. Only logs carrying {@value ErrorFingerprinter#FINGERPRINT_ATTRIBUTE} — the ones the
 * receiver stamped because they {@linkplain ErrorFingerprinter#feeds feed} an issue — are folded.
 *
 * <p>Plain Java underneath: {@code ErrorIssueAggregatorTest} news it up with an in-memory sink.
 */
@ApplicationScoped
public class ErrorIssueAggregator {

  static final String FLUSH_INTERVAL_KEY = "qits.observability.error-issues.flush-interval";
  static final String MAX_PENDING_KEY = "qits.observability.error-issues.max-pending";

  /** The raw body kept as an issue's sample message is cut here. */
  static final int MAX_SAMPLE_MESSAGE = 1000;

  /** The newest {@code exception.stacktrace} kept as an issue's sample is cut here. */
  static final int MAX_SAMPLE_STACKTRACE = 16384;

  private static final long WARN_EVERY_MILLIS = 60_000L;

  private static final Logger LOG = Logger.getLogger(ErrorIssueAggregator.class);

  private final ErrorFingerprinter fingerprinter;
  private final ErrorIssueSink sink;
  private final int maxPending;
  private final LongSupplier millis;

  /** Guards {@link #pending}. Held only while folding a batch or swapping the window out. */
  private final Object lock = new Object();

  /** Serialises flushes, so the scheduled one and a test's never interleave; never held by offer. */
  private final Object flushLock = new Object();

  private Map<String, Pending> pending = new HashMap<>();

  private final AtomicLong droppedOccurrences = new AtomicLong();
  private final AtomicLong failedFlushes = new AtomicLong();
  private final AtomicLong lostInFailedFlushes = new AtomicLong();
  private final AtomicLong lastWarnMillis = new AtomicLong(Long.MIN_VALUE);

  @Inject
  public ErrorIssueAggregator(
      ErrorFingerprinter fingerprinter,
      ErrorIssueSink sink,
      @ConfigProperty(name = MAX_PENDING_KEY, defaultValue = "1000") int maxPending) {
    this(fingerprinter, sink, maxPending, System::currentTimeMillis);
  }

  /** For plain-Java callers; {@code millis} is the clock the WARN throttle reads. */
  public ErrorIssueAggregator(
      ErrorFingerprinter fingerprinter, ErrorIssueSink sink, int maxPending, LongSupplier millis) {
    this.fingerprinter = fingerprinter;
    this.sink = sink;
    this.maxPending = maxPending;
    this.millis = millis;
  }

  /**
   * Folds a decoded, stamped batch into the pending window. Returns at once, never throws, and
   * never touches the database; a process with no error-issue database ({@code %dev}) holds
   * nothing.
   */
  public void offer(List<StoredLog> logs) {
    try {
      if (logs.isEmpty() || !sink.active()) {
        return;
      }
      List<Occurrence> occurrences = new ArrayList<>();
      for (StoredLog log : logs) {
        if (log.attributes() != null
            && log.attributes().containsKey(ErrorFingerprinter.FINGERPRINT_ATTRIBUTE)) {
          occurrences.add(occurrence(log));
        }
      }
      if (occurrences.isEmpty()) {
        return;
      }
      long dropped = 0;
      synchronized (lock) {
        for (Occurrence occurrence : occurrences) {
          Pending entry = pending.get(occurrence.fingerprint().fingerprint());
          if (entry == null) {
            if (pending.size() >= maxPending) {
              dropped++;
              continue;
            }
            entry = new Pending(occurrence);
            pending.put(occurrence.fingerprint().fingerprint(), entry);
          }
          entry.fold(occurrence);
        }
      }
      if (dropped > 0) {
        droppedOccurrences.addAndGet(dropped);
        warn(
            () ->
                "error-issue window is full at "
                    + maxPending
                    + " fingerprints: "
                    + droppedOccurrences.get()
                    + " occurrences dropped so far");
      }
    } catch (RuntimeException e) {
      warn(() -> "error-issue aggregation skipped a batch: " + e);
    }
  }

  /** The scheduled flush. {@code %test} switches it off and calls {@link #flush} itself. */
  @Scheduled(
      identity = "error-issue-flush",
      every = "${" + FLUSH_INTERVAL_KEY + ":10s}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduledFlush() {
    flush();
  }

  /**
   * Swaps the pending window out and writes it in one transaction. A failed write drops the window
   * and is counted; it never throws. Answers how many fingerprints the window held.
   */
  public int flush() {
    synchronized (flushLock) {
      Map<String, Pending> window;
      synchronized (lock) {
        if (pending.isEmpty()) {
          return 0;
        }
        window = pending;
        pending = new HashMap<>();
      }
      List<ErrorIssueDelta> deltas = new ArrayList<>(window.size());
      long occurrences = 0;
      for (Pending entry : window.values()) {
        deltas.add(entry.toDelta());
        occurrences += entry.count;
      }
      try {
        sink.write(deltas);
      } catch (RuntimeException e) {
        failedFlushes.incrementAndGet();
        lostInFailedFlushes.addAndGet(occurrences);
        long lost = occurrences;
        warn(
            () ->
                "error-issue flush failed and dropped "
                    + lost
                    + " occurrences ("
                    + failedFlushes.get()
                    + " failed flushes so far): "
                    + e);
      }
      return deltas.size();
    }
  }

  /** Distinct fingerprints waiting for the next flush. */
  public int pendingFingerprints() {
    synchronized (lock) {
      return pending.size();
    }
  }

  /** Occurrences dropped because the window was full. */
  public long droppedOccurrences() {
    return droppedOccurrences.get();
  }

  /** Flushes whose write failed, and whose window was dropped. */
  public long failedFlushes() {
    return failedFlushes.get();
  }

  /** Occurrences lost with those failed flushes. */
  public long lostInFailedFlushes() {
    return lostInFailedFlushes.get();
  }

  /** WARN, at most once a minute. Never ERROR: see the class comment. */
  private void warn(Supplier<String> message) {
    long now = millis.getAsLong();
    long last = lastWarnMillis.get();
    if (last != Long.MIN_VALUE && now - last < WARN_EVERY_MILLIS) {
      return;
    }
    if (lastWarnMillis.compareAndSet(last, now)) {
      LOG.warn(message.get());
    }
  }

  private Occurrence occurrence(StoredLog log) {
    Map<String, String> attributes = log.attributes();
    // To the microsecond, which is what the timestamptz columns keep.
    Instant at =
        (log.epochNanos() != 0
                ? Instant.ofEpochSecond(0, log.epochNanos())
                : Instant.ofEpochMilli(log.receivedAtMillis()))
            .truncatedTo(ChronoUnit.MICROS);
    String version =
        log.resourceAttributes() == null ? null : log.resourceAttributes().get("service.version");
    return new Occurrence(
        fingerprinter.fingerprint(log),
        log.serviceName(),
        ErrorFingerprinter.normaliseMessage(log.body()),
        cut(log.body(), MAX_SAMPLE_MESSAGE),
        cut(sampleStacktrace(attributes), MAX_SAMPLE_STACKTRACE),
        log.hasTrace() ? log.traceId() : null,
        at,
        emptyToNull(version));
  }

  /**
   * The stack an issue keeps as its sample: the mapped one when the receiver mapped a browser
   * stack through its release's source maps (qits-2766), else the stack as it came.
   */
  private static String sampleStacktrace(Map<String, String> attributes) {
    String mapped = emptyToNull(attributes.get(BrowserStackMapper.MAPPED_STACKTRACE));
    return mapped != null ? mapped : emptyToNull(attributes.get("exception.stacktrace"));
  }

  private static String cut(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }

  /** One fingerprinted log, reduced to what an issue keeps. */
  private record Occurrence(
      ErrorFingerprinter.ErrorFingerprint fingerprint,
      String serviceName,
      String title,
      String body,
      String stacktrace,
      String traceId,
      Instant at,
      String version) {}

  /** One fingerprint's share of the window. Mutated only under {@link #lock}. */
  private static final class Pending {
    private final String fingerprint;
    private final String serviceName;
    private final String exceptionType;
    private final String location;
    private final String message;
    private final TreeMap<Instant, Long> hourly = new TreeMap<>();
    private long count;
    private Instant first;
    private String firstVersion;
    private Instant last;
    private String lastVersion;
    private String sampleMessage;
    private Instant stacktraceAt;
    private String sampleStacktrace;
    private Instant traceAt;
    private String sampleTraceId;

    Pending(Occurrence occurrence) {
      this.fingerprint = occurrence.fingerprint().fingerprint();
      this.serviceName = occurrence.serviceName();
      this.exceptionType = emptyToNull(occurrence.fingerprint().type());
      this.location = emptyToNull(occurrence.fingerprint().location());
      this.message = occurrence.title();
    }

    void fold(Occurrence occurrence) {
      Instant at = occurrence.at();
      count++;
      hourly.merge(ErrorIssues.hourOf(at), 1L, Long::sum);
      if (first == null || at.isBefore(first)) {
        first = at;
        firstVersion = occurrence.version();
      }
      if (last == null || !at.isBefore(last)) {
        last = at;
        lastVersion = occurrence.version();
        sampleMessage = occurrence.body();
      }
      if (occurrence.stacktrace() != null && (stacktraceAt == null || !at.isBefore(stacktraceAt))) {
        stacktraceAt = at;
        sampleStacktrace = occurrence.stacktrace();
      }
      if (occurrence.traceId() != null && (traceAt == null || !at.isBefore(traceAt))) {
        traceAt = at;
        sampleTraceId = occurrence.traceId();
      }
    }

    ErrorIssueDelta toDelta() {
      return new ErrorIssueDelta(
          fingerprint,
          serviceName,
          exceptionType,
          location,
          message,
          sampleMessage,
          sampleStacktrace,
          sampleTraceId,
          first,
          last,
          firstVersion,
          lastVersion,
          count,
          hourly);
    }
  }
}
