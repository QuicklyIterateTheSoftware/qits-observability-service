package eu.wohlben.qits.telemetry.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.issues.ErrorIssue;
import eu.wohlben.qits.telemetry.issues.ErrorIssueDelta;
import eu.wohlben.qits.telemetry.issues.ErrorIssueState;
import eu.wohlben.qits.telemetry.issues.ErrorIssues;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@link ErrorIssueAggregator} as plain Java, over an in-memory sink that applies the domain's own
 * rules ({@link ErrorIssues}) the way the store does: the fold, the cap, the hourly buckets, the
 * failed flush, and the regression of a DISMISSED issue.
 */
class ErrorIssueAggregatorTest {

  private static final Instant T = Instant.parse("2026-03-01T10:15:00Z");
  private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");

  /** What the store would hold, kept by the same rules. */
  static final class MemorySink implements ErrorIssueSink {
    final List<List<ErrorIssueDelta>> windows = new ArrayList<>();
    final Map<String, ErrorIssue> issues = new HashMap<>();
    final Map<String, TreeMap<Instant, Long>> hours = new HashMap<>();
    final List<String> opened = new ArrayList<>();
    final List<String> regressed = new ArrayList<>();
    boolean active = true;
    boolean fail;

    @Override
    public boolean active() {
      return active;
    }

    @Override
    public void write(List<ErrorIssueDelta> window) {
      if (fail) {
        throw new IllegalStateException("the database is gone");
      }
      windows.add(window);
      for (ErrorIssueDelta delta : window) {
        ErrorIssue existing = issues.get(delta.fingerprint());
        if (existing == null) {
          issues.put(delta.fingerprint(), ErrorIssues.open(delta));
          opened.add(delta.fingerprint());
        } else {
          ErrorIssues.Merged merged = ErrorIssues.merge(existing, delta, NOW);
          issues.put(delta.fingerprint(), merged.issue());
          if (merged.reopened()) {
            regressed.add(delta.fingerprint());
          }
        }
        delta
            .hourly()
            .forEach(
                (hour, count) ->
                    hours.computeIfAbsent(delta.fingerprint(), k -> new TreeMap<>())
                        .merge(hour, count, Long::sum));
      }
    }
  }

  private final ErrorFingerprinter fingerprinter = new ErrorFingerprinter();
  private final MemorySink sink = new MemorySink();
  private final ErrorIssueAggregator aggregator =
      new ErrorIssueAggregator(fingerprinter, sink, 1000, () -> 0L);

  /** A stamped ERROR log of {@code service}, as the receiver hands it over. */
  private StoredLog error(
      String service,
      String type,
      Instant at,
      String version,
      String body,
      String traceId,
      String stacktrace) {
    Map<String, String> attributes = new LinkedHashMap<>();
    if (type != null) {
      attributes.put("exception.type", type);
      attributes.put("code.function.name", "eu.wohlben.qits.Example.run");
    }
    if (stacktrace != null) {
      attributes.put("exception.stacktrace", stacktrace);
    }
    Map<String, String> resource = new LinkedHashMap<>();
    resource.put("service.name", service);
    if (version != null) {
      resource.put("service.version", version);
    }
    StoredLog log =
        new StoredLog(
            at == null ? 0 : at.getEpochSecond() * 1_000_000_000L + at.getNano(),
            17,
            "ERROR",
            body,
            traceId == null ? "" : traceId,
            "",
            service,
            attributes,
            resource,
            T.toEpochMilli());
    return fingerprinter.stamp(List.of(log)).getFirst();
  }

  private StoredLog error(Instant at, String version, String body) {
    return error("qits-a", "java.lang.IllegalStateException", at, version, body, null, null);
  }

  @Test
  void occurrencesOfOneFingerprintFoldIntoOneDelta() {
    aggregator.offer(
        List.of(
            error("qits-a", "java.lang.IllegalStateException", T, "v1", "first 1", "aa01", "st-1"),
            error(
                "qits-a",
                "java.lang.IllegalStateException",
                T.plusSeconds(60),
                "v2",
                "newest 2",
                null,
                null)));
    aggregator.offer(
        List.of(
            error(
                "qits-a",
                "java.lang.IllegalStateException",
                T.minusSeconds(60),
                "v0",
                "earliest 0",
                "aa00",
                "st-0")));
    assertEquals(1, aggregator.pendingFingerprints());

    assertEquals(1, aggregator.flush());
    ErrorIssueDelta delta = sink.windows.getFirst().getFirst();
    assertEquals(3, delta.occurrences());
    assertEquals(T.minusSeconds(60), delta.firstSeen());
    assertEquals(T.plusSeconds(60), delta.lastSeen());
    assertEquals("v0", delta.firstVersion());
    assertEquals("v2", delta.lastVersion());
    assertEquals("newest 2", delta.sampleMessage(), "the newest occurrence's raw body");
    assertEquals("st-1", delta.sampleStacktrace(), "the newest occurrence that had one");
    assertEquals("aa01", delta.sampleTraceId(), "the newest non-empty trace id");
    assertEquals("java.lang.IllegalStateException", delta.exceptionType());
    assertEquals("eu.wohlben.qits.Example.run", delta.location());
    assertEquals("first <n>", delta.message(), "the normalised body is the title");

    assertEquals(0, aggregator.pendingFingerprints(), "the flush swapped the window out");
    assertEquals(0, aggregator.flush(), "an empty window writes nothing");
    assertEquals(1, sink.windows.size());
  }

  @Test
  void anOccurrenceLandsInTheHourOfItsOwnTime() {
    aggregator.offer(
        List.of(
            error(Instant.parse("2026-03-01T10:15:00Z"), "v1", "x"),
            error(Instant.parse("2026-03-01T10:45:00Z"), "v1", "x"),
            error(Instant.parse("2026-03-01T11:05:00Z"), "v1", "x"),
            // No record time: the ingest stamp (T, 10:15) stands in.
            error(null, "v1", "x")));
    aggregator.flush();

    ErrorIssueDelta delta = sink.windows.getFirst().getFirst();
    assertEquals(
        Map.of(
            Instant.parse("2026-03-01T10:00:00Z"), 3L, Instant.parse("2026-03-01T11:00:00Z"), 1L),
        delta.hourly());
    assertEquals(T, delta.firstSeen());

    aggregator.offer(List.of(error(Instant.parse("2026-03-01T11:30:00Z"), "v1", "x")));
    aggregator.flush();
    String fingerprint = delta.fingerprint();
    assertEquals(
        Map.of(
            Instant.parse("2026-03-01T10:00:00Z"), 3L, Instant.parse("2026-03-01T11:00:00Z"), 2L),
        sink.hours.get(fingerprint));
    assertEquals(5, sink.issues.get(fingerprint).occurrences());
  }

  @Test
  void theWindowIsCappedAndTheRestIsDroppedAndCounted() {
    ErrorIssueAggregator capped = new ErrorIssueAggregator(fingerprinter, sink, 2, () -> 0L);
    capped.offer(
        List.of(
            error("qits-a", "java.lang.A", T, "v1", "a", null, null),
            error("qits-b", "java.lang.B", T, "v1", "b", null, null),
            error("qits-c", "java.lang.C", T, "v1", "c", null, null),
            error("qits-d", "java.lang.D", T, "v1", "d", null, null)));
    // A fingerprint already held still folds.
    capped.offer(List.of(error("qits-a", "java.lang.A", T.plusSeconds(1), "v1", "a", null, null)));
    assertEquals(2, capped.pendingFingerprints());
    assertEquals(2, capped.droppedOccurrences());

    assertEquals(2, capped.flush());
    assertEquals(
        3,
        sink.windows.getFirst().stream().mapToLong(ErrorIssueDelta::occurrences).sum(),
        "two of qits-a, one of qits-b");
    // The next window has room again.
    capped.offer(List.of(error("qits-c", "java.lang.C", T, "v1", "c", null, null)));
    assertEquals(1, capped.pendingFingerprints());
  }

  @Test
  void aFailedFlushDropsItsWindowAndCountsIt() {
    aggregator.offer(List.of(error(T, "v1", "x"), error(T, "v1", "x")));
    sink.fail = true;
    assertEquals(1, aggregator.flush(), "it never throws");
    assertEquals(1, aggregator.failedFlushes());
    assertEquals(2, aggregator.lostInFailedFlushes());
    assertEquals(0, aggregator.pendingFingerprints(), "the window is dropped, not retried");
  }

  @Test
  void onlyStampedLogsFoldAndNothingIsHeldWithoutADatabase() {
    StoredLog info =
        new StoredLog(1, 9, "INFO", "fine", "", "", "qits-a", Map.of(), Map.of(), T.toEpochMilli());
    aggregator.offer(List.of(info));
    assertEquals(0, aggregator.pendingFingerprints());

    sink.active = false;
    aggregator.offer(List.of(error(T, "v1", "x")));
    assertEquals(0, aggregator.pendingFingerprints());
  }

  @Test
  void aDismissedIssueThatRecursReopensAsARegression() {
    aggregator.offer(List.of(error(T, "v1", "x")));
    aggregator.flush();
    String fingerprint = sink.opened.getFirst();
    ErrorIssue dismissed =
        ErrorIssues.dismiss(sink.issues.get(fingerprint), "alice", T.plusSeconds(600));
    sink.issues.put(fingerprint, dismissed);

    // Dismissing twice keeps the first dismissal.
    assertEquals(dismissed, ErrorIssues.dismiss(dismissed, "bob", T.plusSeconds(700)));

    aggregator.offer(List.of(error(T.plusSeconds(3600), "v2", "x")));
    aggregator.flush();

    ErrorIssue reopened = sink.issues.get(fingerprint);
    assertEquals(List.of(fingerprint), sink.regressed);
    assertEquals(ErrorIssueState.OPEN, reopened.state());
    assertEquals(1, reopened.regressions());
    assertEquals(NOW, reopened.regressedAt());
    assertEquals(T.plusSeconds(600), reopened.dismissedAt(), "the dismiss history stays");
    assertEquals("alice", reopened.dismissedBy());
    assertEquals(2, reopened.occurrences());
    assertEquals("v1", reopened.firstVersion());
    assertEquals("v2", reopened.lastVersion());

    // An OPEN issue seen again is no regression.
    aggregator.offer(List.of(error(T.plusSeconds(7200), "v2", "x")));
    aggregator.flush();
    assertEquals(1, sink.regressed.size());
    assertEquals(1, sink.issues.get(fingerprint).regressions());
  }

  @Test
  void samplesAreCut() {
    String body = "b".repeat(ErrorIssueAggregator.MAX_SAMPLE_MESSAGE + 10);
    String stack = "s".repeat(ErrorIssueAggregator.MAX_SAMPLE_STACKTRACE + 10);
    aggregator.offer(
        List.of(error("qits-a", "java.lang.IllegalStateException", T, null, body, null, stack)));
    aggregator.flush();
    ErrorIssueDelta delta = sink.windows.getFirst().getFirst();
    assertEquals(ErrorIssueAggregator.MAX_SAMPLE_MESSAGE, delta.sampleMessage().length());
    assertEquals(ErrorIssueAggregator.MAX_SAMPLE_STACKTRACE, delta.sampleStacktrace().length());
    assertNull(delta.firstVersion());
    assertNull(delta.sampleTraceId());
    assertNotNull(delta.message());
    assertTrue(delta.message().length() <= 200);
  }
}
