package eu.wohlben.qits.telemetry.control;

import eu.wohlben.qits.telemetry.dto.MetricPoint;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.dto.StoredSource;
import eu.wohlben.qits.telemetry.dto.StoredSpan;
import eu.wohlben.qits.telemetry.dto.TelemetryStreamFrame;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * The live stream asked about the past: every buffered log, span and metric point that a {@link
 * TelemetryFilter} matches inside a closed time window, answered as the live {@link
 * TelemetryStreamFrame}s. {@code POST …/telemetry/records/search} is its door and {@code qits observe
 * query} its client.
 *
 * <p><b>Same verdict as the live feed.</b> Matching and framing go through {@link
 * TelemetryLiveFeed}'s own {@code Kind}s, so one record and one filter are matched by the same
 * {@link TelemetryFilter#matches} call and framed by the same code, live or searched.
 *
 * <p><b>The window is the record's own time</b>, both bounds inclusive: a log's time ({@code
 * epochNanos}, which the decoder already falls back to the observed time), a span's start, a
 * metric point's time. Where an exporter left that at zero the ingest stamp stands in. This is the
 * one read that does not window on {@code receivedAtMillis}: the question it answers is "what
 * happened between these two instants".
 *
 * <p><b>A metric only by its latest point.</b> The store keeps one point per series and replaces
 * it in place, so a metric is in the answer only when its latest point falls in the window. A
 * series that reported inside the window and again after it is not found there.
 *
 * <p><b>Bounded at the end that matters.</b> When more than {@code limit} records match, the newest
 * {@code limit} are kept (a tail wants the end) and returned oldest first, and {@code truncated}
 * says so. {@code bufferedSince} is the ingest stamp of the oldest record still held across the
 * searched sources, null when they hold nothing: a window that starts before it reaches past what
 * the buffer remembers, so an empty answer there is not proof of absence.
 *
 * <p>Plain Java over the store, no CDI, so the tests run without Quarkus.
 */
public final class TelemetryRecordSearch {

  /** The answer: frames oldest first, whether more matched, and how far back the buffer reaches. */
  public record Result(
      List<TelemetryStreamFrame> records, boolean truncated, Instant bufferedSince) {}

  private TelemetryRecordSearch() {}

  /**
   * @param source a bucket key from {@code …/telemetry/sources}; null or blank searches every source
   * @param since inclusive lower bound, null for no lower bound
   * @param until inclusive upper bound, never null
   * @param limit at most this many records, at least 1
   */
  public static Result search(
      TelemetryStore store,
      TelemetryFilter filter,
      String source,
      Instant since,
      Instant until,
      int limit) {
    long from = since == null ? Long.MIN_VALUE : nanos(since);
    long to = nanos(until);
    List<String> keys = new ArrayList<>();
    Long oldest = null;
    for (StoredSource held : store.sources()) {
      if (source != null && !source.isBlank() && !source.equals(held.key())) {
        continue;
      }
      keys.add(held.key());
      Long first = held.oldestReceivedAtMillis();
      if (first != null && (oldest == null || first < oldest)) {
        oldest = first;
      }
    }

    List<Match<?>> matches = new ArrayList<>();
    if (!filter.isEmpty()) {
      for (String key : keys) {
        collect(store.logsIn(key), TelemetryLiveFeed.LOGS, TelemetryRecordSearch::at, filter,
            from, to, matches);
        collect(store.spansIn(key), TelemetryLiveFeed.SPANS, TelemetryRecordSearch::at, filter,
            from, to, matches);
        collect(store.metricsIn(key), TelemetryLiveFeed.METRICS, TelemetryRecordSearch::at,
            filter, from, to, matches);
      }
    }
    // Stable: records at the same instant keep the store's order.
    matches.sort(Comparator.comparingLong(Match::atNanos));
    boolean truncated = matches.size() > limit;
    List<Match<?>> kept =
        truncated ? matches.subList(matches.size() - limit, matches.size()) : matches;
    List<TelemetryStreamFrame> frames = new ArrayList<>(kept.size());
    for (Match<?> match : kept) {
      frames.add(match.frame());
    }
    return new Result(
        List.copyOf(frames), truncated, oldest == null ? null : Instant.ofEpochMilli(oldest));
  }

  /** One match before framing: only the kept ones are framed. */
  private record Match<T>(long atNanos, TelemetryLiveFeed.Kind<T> kind, T record) {
    TelemetryStreamFrame frame() {
      return kind.frame(record);
    }
  }

  private static <T> void collect(
      List<T> records,
      TelemetryLiveFeed.Kind<T> kind,
      ToLongFunction<T> at,
      TelemetryFilter filter,
      long from,
      long to,
      List<Match<?>> into) {
    for (T record : records) {
      long when = at.applyAsLong(record);
      if (when >= from && when <= to && kind.matches(filter, record)) {
        into.add(new Match<>(when, kind, record));
      }
    }
  }

  static long at(StoredLog log) {
    return orReceived(log.epochNanos(), log.receivedAtMillis());
  }

  static long at(StoredSpan span) {
    return orReceived(span.startEpochNanos(), span.receivedAtMillis());
  }

  static long at(MetricPoint point) {
    return orReceived(point.epochNanos(), point.receivedAtMillis());
  }

  private static long orReceived(long epochNanos, long receivedAtMillis) {
    return epochNanos != 0 ? epochNanos : receivedAtMillis * 1_000_000L;
  }

  /** Nanoseconds since the epoch, saturating outside 1677..2262 rather than overflowing. */
  private static long nanos(Instant instant) {
    try {
      return Math.addExact(
          Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    } catch (ArithmeticException outOfRange) {
      return instant.getEpochSecond() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
  }
}
