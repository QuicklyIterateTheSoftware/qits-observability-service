package eu.wohlben.qits.telemetry.control;

import eu.wohlben.qits.telemetry.dto.MetricPoint;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.dto.StoredSource;
import eu.wohlben.qits.telemetry.dto.StoredSpan;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-memory telemetry buffer: recent spans, log records and metric points per workspace,
 * bounded, ephemeral — a JVM restart empties it, and that is the feature (no entity, no migration,
 * no H2 table). Same philosophy as the command ring buffer ({@code CommandSession}): byte-accounted
 * deques, evict-oldest.
 *
 * <p>Records are bucketed three ways, in this order:
 *
 * <ol>
 *   <li>by the {@code qits.repository.id} / {@code qits.workspace.id} resource attributes that qits
 *       stamps into every launch with the {@code otel} toggle ({@code OTEL_RESOURCE_ATTRIBUTES}) —
 *       key {@code <repoId>/<workspaceId>};
 *   <li>failing that, by {@code service.name} — key {@link #SERVICE_KEY_PREFIX} + the name;
 *   <li>failing even that, into {@link #UNSCOPED_KEY}.
 * </ol>
 *
 * <p>The {@code service.name} tier is what makes the fairness tier below real. Every process on this
 * platform exports here and none of them stamps the qits attributes, so before it existed all ten
 * shared one bucket and the "chatty service pays for its own volume" property had nothing to be fair
 * between: a busy gateway evicted a quiet service's spans. Bucket keys are opaque to callers — the
 * sources listing hands one out and the query surface takes it back verbatim as {@code ?source=},
 * which is why no reader has to know this shape. (A workspace whose repository id is literally
 * {@code _service} would collide with the second tier; that is the cost of a flat key space and it
 * is not worth defending against.)
 *
 * <p>Bounding is three-tier. First, per-source <em>count</em> caps (spans/logs/metric series),
 * enforced inside the bucket monitor. Second, a per-source <em>byte</em> budget ({@link
 * #maxBytesPerSource}), enforced in the same monitor immediately afterwards — this is the tier that
 * decides what a source retains, and it decides it from that source's own volume alone. Third, a
 * global byte ceiling ({@link #maxTotalBytes}), which evicts the oldest record from the
 * <em>fattest</em> bucket; it exists now to bound this process's heap, not to arbitrate between
 * sources. Lock order is always {@code evictionLock → bucket monitor}, and appenders never take
 * {@code evictionLock} while holding a bucket monitor, so the tiers can't deadlock — the byte budget
 * takes no lock of its own, it runs under the monitor the appender already holds.
 *
 * <p>The budget was chosen against a measurement, not against arithmetic: on 2026-09-16 the dev
 * deployment held 18 sources and 61,423,348 of 67,108,864 bytes — 91.5% of the then-64 MiB ceiling —
 * with nine buckets pinned at the 2,000-span cap at roughly 5.6 MB each. By {@link
 * TelemetrySizeEstimator}'s arithmetic a platform span estimates at about 2,800 bytes and an
 * access-log-shaped record at about 1,950 bytes, so a source at both count caps would retain some
 * 25 MB; 15 MiB per source is the figure that makes a chatty edge pay for its own volume well before
 * the log count cap does. {@code TelemetryStoreTest} pins that inequality against the real estimator
 * with deliberately lean fixtures, so drift in either direction breaks a test rather than a
 * deployment.
 *
 * <p><strong>If the global backstop ever starts binding, lower {@code
 * qits.telemetry.max-bytes-per-source} — do not raise {@code qits.telemetry.max-total-bytes}.</strong>
 * Raising the ceiling is the tempting move and it walks straight back into the failure this design
 * removes: a binding global tier means which records survive in one bucket is decided by what another
 * bucket happens to be holding.
 */
@ApplicationScoped
public class TelemetryStore {

  private static final Logger LOG = Logger.getLogger(TelemetryStore.class);

  /** Bucket for telemetry that carried neither the qits.* attributes nor a {@code service.name}. */
  public static final String UNSCOPED_KEY = "_unscoped";

  /** Key prefix of the per-{@code service.name} buckets. */
  public static final String SERVICE_KEY_PREFIX = "_service/";

  static final String REPOSITORY_ATTRIBUTE = "qits.repository.id";
  static final String WORKSPACE_ATTRIBUTE = "qits.workspace.id";

  // Package-visible so the plain-JUnit store test can shrink them; injected values come from
  // qits.telemetry.* when running in Quarkus (defaults here, pattern of qits.services.*).
  // The three count keys keep their historical `-per-workspace` spelling — a bucket is now a
  // source, but the knob is documented and renaming it would silently drop any deployment's
  // override. `max-bytes-per-source` is spelled the other way on purpose rather than matching its
  // three neighbours: it is brand new, so no deployment can be overriding it, and a new key gets
  // the word the code and the wire actually use instead of inheriting a compatibility spelling.
  @ConfigProperty(name = "qits.telemetry.max-spans-per-workspace", defaultValue = "2000")
  int maxSpansPerWorkspace = 2000;

  @ConfigProperty(name = "qits.telemetry.max-logs-per-workspace", defaultValue = "10000")
  int maxLogsPerWorkspace = 10000;

  @ConfigProperty(name = "qits.telemetry.max-metric-series-per-workspace", defaultValue = "500")
  int maxMetricSeriesPerWorkspace = 500;

  @ConfigProperty(name = "qits.telemetry.max-bytes-per-source", defaultValue = "15728640")
  long maxBytesPerSource = 15L * 1024 * 1024;

  @ConfigProperty(name = "qits.telemetry.max-total-bytes", defaultValue = "268435456")
  long maxTotalBytes = 256L * 1024 * 1024;

  // Null in the plain-JUnit store test (it news up the store directly, no CDI); guarded before use.
  @Inject TelemetryChangePublisher changePublisher;

  private final ConcurrentHashMap<String, WorkspaceBuffer> buffers = new ConcurrentHashMap<>();
  private final AtomicLong totalBytes = new AtomicLong();
  private final Object evictionLock = new Object();

  // What the buffer has thrown away since it started. Plain AtomicLongs on purpose: the store test
  // is plain JUnit with no CDI and must stay that way, so nothing here may need a container.
  private final AtomicLong evictedSpans = new AtomicLong();
  private final AtomicLong evictedLogs = new AtomicLong();
  private final AtomicLong droppedMetricSeries = new AtomicLong();

  private volatile Instant startedAt = Instant.now();

  /**
   * Pins {@link #startedAt} to process start rather than to first use. An {@code @ApplicationScoped}
   * bean is created lazily, so without this observer the "held in memory since" the UI states would
   * be the time of the first export, not the time the buffer became empty — the one number the
   * ephemerality statement cannot afford to round.
   */
  void onStart(@Observes StartupEvent ignored) {
    startedAt = Instant.now();
  }

  /** All fields guarded by the buffer's own monitor. */
  private static final class WorkspaceBuffer {
    final ArrayDeque<StoredSpan> spans = new ArrayDeque<>();
    final ArrayDeque<StoredLog> logs = new ArrayDeque<>();
    final LinkedHashMap<String, MetricPoint> metrics = new LinkedHashMap<>();
    final HashMap<String, List<StoredSpan>> spansByTrace = new HashMap<>();
    long bytes;
    boolean metricCapWarned;
  }

  public void addSpans(Collection<StoredSpan> spans) {
    for (StoredSpan span : spans) {
      WorkspaceBuffer buffer = bufferFor(span.resourceAttributes());
      synchronized (buffer) {
        buffer.spans.addLast(span);
        buffer.spansByTrace.computeIfAbsent(span.traceId(), t -> new ArrayList<>()).add(span);
        account(buffer, TelemetrySizeEstimator.bytesOf(span));
        while (buffer.spans.size() > maxSpansPerWorkspace) {
          evictOldestSpan(buffer);
        }
        enforceSourceBudget(buffer);
      }
    }
    enforceGlobalCeiling();
    fireTelemetryHints(spans, StoredSpan::resourceAttributes);
  }

  public void addLogs(Collection<StoredLog> logs) {
    for (StoredLog log : logs) {
      WorkspaceBuffer buffer = bufferFor(log.resourceAttributes());
      synchronized (buffer) {
        buffer.logs.addLast(log);
        account(buffer, TelemetrySizeEstimator.bytesOf(log));
        while (buffer.logs.size() > maxLogsPerWorkspace) {
          evictOldestLog(buffer);
        }
        enforceSourceBudget(buffer);
      }
    }
    enforceGlobalCeiling();
    fireTelemetryHints(logs, StoredLog::resourceAttributes);
  }

  public void addMetrics(Collection<MetricPoint> points) {
    for (MetricPoint point : points) {
      WorkspaceBuffer buffer = bufferFor(point.resourceAttributes());
      synchronized (buffer) {
        String key = point.seriesKey();
        MetricPoint previous = buffer.metrics.get(key);
        if (previous == null && buffer.metrics.size() >= maxMetricSeriesPerWorkspace) {
          droppedMetricSeries.incrementAndGet();
          if (!buffer.metricCapWarned) {
            buffer.metricCapWarned = true;
            LOG.warnf(
                "Telemetry metric-series cap (%d) reached for a workspace; new series are dropped",
                maxMetricSeriesPerWorkspace);
          }
          continue; // nothing was added, so there is no budget to re-check
        }
        buffer.metrics.put(key, point);
        if (previous != null) {
          account(buffer, -TelemetrySizeEstimator.bytesOf(previous));
        }
        account(buffer, TelemetrySizeEstimator.bytesOf(point));
        // Metrics are never evicted, but they do count towards buffer.bytes — so a metrics append
        // can push a source over its budget, and spans/logs answer for it.
        enforceSourceBudget(buffer);
      }
    }
    enforceGlobalCeiling();
    fireTelemetryHints(points, MetricPoint::resourceAttributes);
  }

  /**
   * Fire one debounce-able {@link TelemetryChanged} hint per distinct scoped workspace touched by
   * this batch — unscoped records ({@link #UNSCOPED_KEY}) produce no hint (nothing subscribes to them).
   * Deduped so a 1000-span batch for one workspace is one hint, not a thousand async events.
   */
  private <T> void fireTelemetryHints(
      Collection<T> records, java.util.function.Function<T, Map<String, String>> attributes) {
    if (changePublisher == null || records.isEmpty()) {
      return;
    }
    Set<Map.Entry<String, String>> scopes = new HashSet<>();
    for (T record : records) {
      Map<String, String> attrs = attributes.apply(record);
      String repoId = attrs.get(REPOSITORY_ATTRIBUTE);
      String workspaceId = attrs.get(WORKSPACE_ATTRIBUTE);
      if (repoId != null && !repoId.isBlank() && workspaceId != null && !workspaceId.isBlank()) {
        scopes.add(Map.entry(repoId, workspaceId));
      }
    }
    for (Map.Entry<String, String> scope : scopes) {
      changePublisher.fire(scope.getKey(), scope.getValue());
    }
  }

  /** Snapshot of the workspace's buffered spans, oldest first. */
  public List<StoredSpan> spans(String repoId, String workspaceId) {
    return spansIn(key(repoId, workspaceId));
  }

  /** The workspace's buffered spans belonging to {@code traceId}, oldest first. */
  public List<StoredSpan> trace(String repoId, String workspaceId, String traceId) {
    return traceIn(key(repoId, workspaceId), traceId);
  }

  /** Snapshot of the workspace's buffered log records, oldest first. */
  public List<StoredLog> logs(String repoId, String workspaceId) {
    return logsIn(key(repoId, workspaceId));
  }

  /** Snapshot of the workspace's metric series (latest point per series). */
  public List<MetricPoint> metrics(String repoId, String workspaceId) {
    return metricsIn(key(repoId, workspaceId));
  }

  // The `…In(sourceKey)` twins address any bucket, including the ones no repository/workspace pair
  // can spell. The pair-keyed methods above are kept as the MCP surface's vocabulary: an agent's
  // scope is a workspace and nothing else, so it never learns a key.

  /** Snapshot of one source's buffered spans, oldest first. Unknown key → empty. */
  public List<StoredSpan> spansIn(String sourceKey) {
    WorkspaceBuffer buffer = buffers.get(sourceKey);
    if (buffer == null) {
      return List.of();
    }
    synchronized (buffer) {
      return List.copyOf(buffer.spans);
    }
  }

  /** One source's buffered spans belonging to {@code traceId}, oldest first. */
  public List<StoredSpan> traceIn(String sourceKey, String traceId) {
    WorkspaceBuffer buffer = buffers.get(sourceKey);
    if (buffer == null) {
      return List.of();
    }
    synchronized (buffer) {
      List<StoredSpan> spans = buffer.spansByTrace.get(traceId);
      return spans == null ? List.of() : List.copyOf(spans);
    }
  }

  /**
   * Snapshot of one source's whole trace index. The grouping the trace list needs is already
   * maintained on ingest, so the listing is a copy rather than a scan-and-group.
   */
  public Map<String, List<StoredSpan>> tracesIn(String sourceKey) {
    WorkspaceBuffer buffer = buffers.get(sourceKey);
    if (buffer == null) {
      return Map.of();
    }
    synchronized (buffer) {
      Map<String, List<StoredSpan>> copy = new LinkedHashMap<>();
      buffer.spansByTrace.forEach((traceId, spans) -> copy.put(traceId, List.copyOf(spans)));
      return copy;
    }
  }

  /** Snapshot of one source's buffered log records, oldest first. Unknown key → empty. */
  public List<StoredLog> logsIn(String sourceKey) {
    WorkspaceBuffer buffer = buffers.get(sourceKey);
    if (buffer == null) {
      return List.of();
    }
    synchronized (buffer) {
      return List.copyOf(buffer.logs);
    }
  }

  /** Snapshot of one source's metric series (latest point per series). Unknown key → empty. */
  public List<MetricPoint> metricsIn(String sourceKey) {
    WorkspaceBuffer buffer = buffers.get(sourceKey);
    if (buffer == null) {
      return List.of();
    }
    synchronized (buffer) {
      return List.copyOf(buffer.metrics.values());
    }
  }

  /**
   * What is in the buffer, one entry per bucket, key order. A bucket that has been emptied by
   * eviction still appears with zero counts — "this source reported and nothing survived" is a
   * different answer from "this source has never reported", and only the listing can tell them
   * apart.
   */
  public List<StoredSource> sources() {
    List<StoredSource> sources = new ArrayList<>();
    for (Map.Entry<String, WorkspaceBuffer> entry : new TreeMap<>(buffers).entrySet()) {
      WorkspaceBuffer buffer = entry.getValue();
      Map<String, int[]> perService = new TreeMap<>();
      long oldest = Long.MAX_VALUE;
      long newest = Long.MIN_VALUE;
      int spans;
      int logs;
      int metrics;
      long bytes;
      synchronized (buffer) {
        spans = buffer.spans.size();
        logs = buffer.logs.size();
        metrics = buffer.metrics.size();
        bytes = buffer.bytes;
        for (StoredSpan span : buffer.spans) {
          perService.computeIfAbsent(span.serviceName(), s -> new int[3])[0]++;
          oldest = Math.min(oldest, span.receivedAtMillis());
          newest = Math.max(newest, span.receivedAtMillis());
        }
        for (StoredLog log : buffer.logs) {
          perService.computeIfAbsent(log.serviceName(), s -> new int[3])[1]++;
          oldest = Math.min(oldest, log.receivedAtMillis());
          newest = Math.max(newest, log.receivedAtMillis());
        }
        for (MetricPoint point : buffer.metrics.values()) {
          perService.computeIfAbsent(point.serviceName(), s -> new int[3])[2]++;
          oldest = Math.min(oldest, point.receivedAtMillis());
          newest = Math.max(newest, point.receivedAtMillis());
        }
      }
      List<StoredSource.Service> services =
          perService.entrySet().stream()
              .map(
                  s ->
                      new StoredSource.Service(
                          s.getKey(), s.getValue()[0], s.getValue()[1], s.getValue()[2]))
              .toList();
      sources.add(
          new StoredSource(
              entry.getKey(),
              services,
              spans,
              logs,
              metrics,
              bytes,
              oldest == Long.MAX_VALUE ? null : oldest,
              newest == Long.MIN_VALUE ? null : newest));
    }
    return sources;
  }

  /** Total estimated bytes currently retained across all buckets. */
  public long totalBytes() {
    return totalBytes.get();
  }

  /** When this buffer started holding what it holds — process start, or the last {@link #clear}. */
  public Instant startedAt() {
    return startedAt;
  }

  /** The configured backstop {@link #totalBytes} is held under, across every bucket. */
  public long maxTotalBytes() {
    return maxTotalBytes;
  }

  /**
   * The byte budget each single source is held to — the tier that decides what a source retains,
   * and the one to lower if the global backstop ever starts binding.
   */
  public long maxBytesPerSource() {
    return maxBytesPerSource;
  }

  /** The per-source count caps, in the order the store enforces them. */
  public int maxSpansPerSource() {
    return maxSpansPerWorkspace;
  }

  public int maxLogsPerSource() {
    return maxLogsPerWorkspace;
  }

  public int maxMetricSeriesPerSource() {
    return maxMetricSeriesPerWorkspace;
  }

  /** How many buckets exist. */
  public int sourceCount() {
    return buffers.size();
  }

  /**
   * Spans dropped at a cap since {@link #startedAt}. Non-zero is the difference between "the buffer
   * is showing you everything" and "the buffer is showing you what survived", which is why it is on
   * the wire at all.
   */
  public long evictedSpans() {
    return evictedSpans.get();
  }

  /** Log records dropped at a cap since {@link #startedAt}. */
  public long evictedLogs() {
    return evictedLogs.get();
  }

  /** New metric series refused at the per-source series cap since {@link #startedAt}. */
  public long droppedMetricSeries() {
    return droppedMetricSeries.get();
  }

  /**
   * Drops everything, counters and start time included — after this the buffer holds nothing and
   * has held nothing since now, which is exactly what it reports after a restart. Test seam (and
   * nothing else calls it).
   */
  public void clear() {
    synchronized (evictionLock) {
      startedAt = Instant.now();
      evictedSpans.set(0);
      evictedLogs.set(0);
      droppedMetricSeries.set(0);
      for (WorkspaceBuffer buffer : buffers.values()) {
        synchronized (buffer) {
          totalBytes.addAndGet(-buffer.bytes);
          buffer.bytes = 0;
          buffer.spans.clear();
          buffer.logs.clear();
          buffer.metrics.clear();
          buffer.spansByTrace.clear();
        }
      }
      buffers.clear();
    }
  }

  private WorkspaceBuffer bufferFor(Map<String, String> resourceAttributes) {
    return buffers.computeIfAbsent(keyFor(resourceAttributes), k -> new WorkspaceBuffer());
  }

  /** Which bucket a record's resource attributes name. See the class javadoc for the three tiers. */
  static String keyFor(Map<String, String> resourceAttributes) {
    String repoId = resourceAttributes.get(REPOSITORY_ATTRIBUTE);
    String workspaceId = resourceAttributes.get(WORKSPACE_ATTRIBUTE);
    if (!isBlank(repoId) && !isBlank(workspaceId)) {
      return key(repoId, workspaceId);
    }
    String serviceName = resourceAttributes.get(TelemetryDecoder.SERVICE_NAME_ATTRIBUTE);
    return isBlank(serviceName) ? UNSCOPED_KEY : SERVICE_KEY_PREFIX + serviceName;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** The bucket key of a workspace-scoped pair — the only key a caller can spell without asking. */
  public static String key(String repoId, String workspaceId) {
    return repoId + "/" + workspaceId;
  }

  /** Caller must hold the buffer monitor. */
  private void account(WorkspaceBuffer buffer, int delta) {
    buffer.bytes += delta;
    totalBytes.addAndGet(delta);
  }

  /** Caller must hold the buffer monitor; the buffer must have at least one span. */
  private void evictOldestSpan(WorkspaceBuffer buffer) {
    StoredSpan evicted = buffer.spans.removeFirst();
    List<StoredSpan> indexed = buffer.spansByTrace.get(evicted.traceId());
    if (indexed != null) {
      indexed.remove(evicted);
      if (indexed.isEmpty()) {
        buffer.spansByTrace.remove(evicted.traceId());
      }
    }
    account(buffer, -TelemetrySizeEstimator.bytesOf(evicted));
    evictedSpans.incrementAndGet();
  }

  /** Caller must hold the buffer monitor; the buffer must have at least one log. */
  private void evictOldestLog(WorkspaceBuffer buffer) {
    account(buffer, -TelemetrySizeEstimator.bytesOf(buffer.logs.removeFirst()));
    evictedLogs.incrementAndGet();
  }

  /**
   * The victim rule both byte tiers share: drop this bucket's oldest span-or-log, whichever arrived
   * first, preferring the span on a tie. Metrics are never evicted — they replace in place and are
   * series-capped, so their footprint is already bounded.
   *
   * <p>Caller must hold the buffer monitor. Returns false if the bucket has nothing evictable, which
   * is what stops both tiers spinning against a bucket holding only metrics.
   */
  private boolean evictOldestRecord(WorkspaceBuffer buffer) {
    StoredSpan oldestSpan = buffer.spans.peekFirst();
    StoredLog oldestLog = buffer.logs.peekFirst();
    if (oldestSpan == null && oldestLog == null) {
      return false;
    }
    boolean evictSpan =
        oldestLog == null
            || (oldestSpan != null && oldestSpan.receivedAtMillis() <= oldestLog.receivedAtMillis());
    if (evictSpan) {
      evictOldestSpan(buffer);
    } else {
      evictOldestLog(buffer);
    }
    return true;
  }

  /**
   * Hold one source to its own byte budget. This is the fairness tier: what a bucket retains is
   * decided by that bucket's volume and nothing else.
   *
   * <p>Caller must hold the buffer monitor — the appenders already do, so this takes no lock and
   * adds nothing to the {@code evictionLock → bucket monitor} order.
   */
  private void enforceSourceBudget(WorkspaceBuffer buffer) {
    while (buffer.bytes > maxBytesPerSource && evictOldestRecord(buffer)) {
      // evictOldestRecord accounts and counts; its false return is what stops the spin when
      // only metrics remain
    }
  }

  /**
   * While over the global byte ceiling, evict the oldest span-or-log from whichever bucket
   * currently retains the most bytes. A heap backstop, not a fairness tier: with the per-source
   * budget in force this should not bind in normal operation, and if it does the answer is a smaller
   * {@link #maxBytesPerSource}, not a larger ceiling. A bucket holding only metrics is skipped.
   */
  private void enforceGlobalCeiling() {
    if (totalBytes.get() <= maxTotalBytes) {
      return;
    }
    synchronized (evictionLock) {
      while (totalBytes.get() > maxTotalBytes) {
        WorkspaceBuffer fattest = null;
        long fattestBytes = -1;
        for (WorkspaceBuffer buffer : buffers.values()) {
          synchronized (buffer) {
            if (buffer.bytes > fattestBytes
                && (!buffer.spans.isEmpty() || !buffer.logs.isEmpty())) {
              fattest = buffer;
              fattestBytes = buffer.bytes;
            }
          }
        }
        if (fattest == null) {
          return; // nothing evictable (only metrics remain) — give up rather than spin
        }
        synchronized (fattest) {
          if (!evictOldestRecord(fattest)) {
            continue; // raced with another evictor; re-pick
          }
        }
      }
    }
  }
}
