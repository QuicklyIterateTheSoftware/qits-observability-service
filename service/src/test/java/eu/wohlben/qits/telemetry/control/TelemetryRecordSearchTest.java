package eu.wohlben.qits.telemetry.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.telemetry.dto.MetricPoint;
import eu.wohlben.qits.telemetry.dto.SpanEvent;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.dto.StoredSpan;
import eu.wohlben.qits.telemetry.dto.TelemetryLogDto;
import eu.wohlben.qits.telemetry.dto.TelemetrySpanDto;
import eu.wohlben.qits.telemetry.dto.TelemetryStreamFrame;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The records search against a store built by hand. Plain JUnit, like {@link TelemetryFilterTest}:
 * the window, the newest-kept truncation, the metric-point rule, and parity with the live feed —
 * one record and one filter give the same verdict and the same frame on both.
 */
class TelemetryRecordSearchTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** 2026-10-01T18:00:00Z, the window's reference instant. */
  private static final Instant T0 = Instant.parse("2026-10-01T18:00:00Z");

  private static final Map<String, String> CI =
      Map.of("service.name", "qits-ci", "service.version", "2026.912.101500");
  private static final Map<String, String> IDP = Map.of("service.name", "qits-idp");

  private TelemetryStore store;

  @BeforeEach
  void setUp() {
    store = new TelemetryStore();
  }

  // --- builders -------------------------------------------------------------------------------

  private static long nanos(Instant at) {
    return at.getEpochSecond() * 1_000_000_000L + at.getNano();
  }

  private static StoredLog log(Instant at, int severity, String body, Map<String, String> resource) {
    return new StoredLog(
        nanos(at),
        severity,
        severity >= 17 ? "ERROR" : "INFO",
        body,
        "",
        "",
        resource.get("service.name"),
        Map.of(),
        resource,
        at.toEpochMilli() + 5_000);
  }

  private static StoredSpan span(Instant start, String name, Map<String, String> resource) {
    return new StoredSpan(
        "4bf92f3577b34da6a3ce929d0e0e4736",
        "00f067aa0ba902b7",
        "",
        resource.get("service.name"),
        "scope",
        name,
        "SERVER",
        nanos(start),
        nanos(start.plusSeconds(30)),
        "ERROR",
        "boom",
        Map.of(),
        List.of(new SpanEvent("exception", nanos(start), Map.of())),
        resource,
        start.toEpochMilli() + 60_000);
  }

  private static MetricPoint metric(Instant at, double value) {
    return new MetricPoint(
        "jvm.memory.used", "", "By", "GAUGE", value, nanos(at), Map.of("pool", "heap"), "qits-ci",
        CI, at.toEpochMilli());
  }

  private static TelemetryFilter filter(String groups) {
    try {
      return TelemetryFilter.parseGroups(JSON.readTree(groups));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final TelemetryFilter EVERYTHING = filter("[{\"conditions\":[]}]");

  private TelemetryRecordSearch.Result search(
      TelemetryFilter filter, Instant since, Instant until, int limit) {
    return TelemetryRecordSearch.search(store, filter, null, since, until, limit);
  }

  private static List<String> bodies(TelemetryRecordSearch.Result result) {
    return result.records().stream().map(f -> ((TelemetryLogDto) f.record()).body()).toList();
  }

  // --- the window -----------------------------------------------------------------------------

  @Test
  void bothEdgesOfTheWindowAreInclusiveToTheNanosecond() {
    Instant since = T0;
    Instant until = T0.plusSeconds(60);
    store.addLogs(
        List.of(
            log(since.minusNanos(1), 9, "just before", CI),
            log(since, 9, "at since", CI),
            log(T0.plusSeconds(30), 9, "inside", CI),
            log(until, 9, "at until", CI),
            log(until.plusNanos(1), 9, "just after", CI)));

    TelemetryRecordSearch.Result result = search(EVERYTHING, since, until, 100);

    assertEquals(List.of("at since", "inside", "at until"), bodies(result));
    assertFalse(result.truncated());
  }

  @Test
  void aSpanIsInTheWindowByItsStartAndNotByWhenItArrived() {
    // Starts inside, ends and arrives after: in. Starts before, arrives inside: out.
    store.addSpans(
        List.of(
            span(T0.plusSeconds(50), "starts inside", CI),
            span(T0.minusSeconds(40), "starts before", CI)));

    TelemetryRecordSearch.Result result = search(EVERYTHING, T0, T0.plusSeconds(60), 100);

    assertEquals(1, result.records().size());
    assertEquals("starts inside", ((TelemetrySpanDto) result.records().get(0).record()).name());
  }

  @Test
  void noSinceReachesBackToTheStartOfTheBuffer() {
    store.addLogs(List.of(log(Instant.EPOCH.plusSeconds(1), 9, "ancient", CI)));

    assertEquals(List.of("ancient"), bodies(search(EVERYTHING, null, T0, 100)));
  }

  // --- truncation -----------------------------------------------------------------------------

  @Test
  void truncationKeepsTheNewestAndReturnsThemOldestFirst() {
    List<StoredLog> logs = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      logs.add(log(T0.plusSeconds(i), 9, "r" + i, CI));
    }
    // Arrival order is not time order: the answer is sorted by the records' own time.
    Collections.reverse(logs);
    store.addLogs(logs);

    TelemetryRecordSearch.Result result = search(EVERYTHING, T0, T0.plusSeconds(60), 3);

    assertEquals(List.of("r7", "r8", "r9"), bodies(result));
    assertTrue(result.truncated());
  }

  @Test
  void exactlyLimitMatchesIsNotTruncated() {
    store.addLogs(List.of(log(T0, 9, "a", CI), log(T0.plusSeconds(1), 9, "b", CI)));

    assertFalse(search(EVERYTHING, T0, T0.plusSeconds(60), 2).truncated());
  }

  // --- metrics --------------------------------------------------------------------------------

  @Test
  void aMetricIsFoundOnlyByItsLatestPoint() {
    store.addMetrics(List.of(metric(T0.plusSeconds(10), 1.0)));
    assertEquals(1, search(EVERYTHING, T0, T0.plusSeconds(60), 100).records().size());

    // The series reports again after the window: its point is replaced, the window loses it.
    store.addMetrics(List.of(metric(T0.plusSeconds(120), 2.0)));
    assertEquals(0, search(EVERYTHING, T0, T0.plusSeconds(60), 100).records().size());
    assertEquals(1, search(EVERYTHING, T0, T0.plusSeconds(180), 100).records().size());
  }

  // --- sources and bufferedSince --------------------------------------------------------------

  @Test
  void aSourceNarrowsTheSearchAndBufferedSinceToThatBucket() {
    store.addLogs(List.of(log(T0, 9, "from ci", CI)));
    store.addLogs(List.of(log(T0.plusSeconds(1), 9, "from idp", IDP)));

    TelemetryRecordSearch.Result idp =
        TelemetryRecordSearch.search(
            store, EVERYTHING, "_service/qits-idp", T0, T0.plusSeconds(60), 100);
    TelemetryRecordSearch.Result all = search(EVERYTHING, T0, T0.plusSeconds(60), 100);

    assertEquals(List.of("from idp"), bodies(idp));
    assertEquals("_service/qits-idp", idp.records().get(0).source());
    assertEquals(
        Instant.ofEpochMilli(T0.plusSeconds(1).toEpochMilli() + 5_000), idp.bufferedSince());
    assertEquals(List.of("from ci", "from idp"), bodies(all));
    assertEquals(Instant.ofEpochMilli(T0.toEpochMilli() + 5_000), all.bufferedSince());
  }

  @Test
  void anEmptyBufferHasNoBufferedSince() {
    assertNull(search(EVERYTHING, T0, T0.plusSeconds(60), 100).bufferedSince());
  }

  @Test
  void anEmptyGroupListMatchesNothingAsItDoesLive() {
    store.addLogs(List.of(log(T0, 9, "a", CI)));

    assertEquals(0, search(filter("[]"), T0, T0.plusSeconds(60), 100).records().size());
  }

  // --- parity with the live feed --------------------------------------------------------------

  /** A sink that finishes every write at once and keeps what it was sent. */
  private static final class RecordingSink implements TelemetryStreamSink {
    private final String id;
    final List<String> frames = Collections.synchronizedList(new ArrayList<>());

    RecordingSink(String id) {
      this.id = id;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public void send(String frame, Runnable done) {
      frames.add(frame);
      done.run();
    }
  }

  private TelemetryLiveFeed feed;

  @AfterEach
  void stopFeed() {
    if (feed != null) {
      feed.stop();
    }
  }

  @Test
  void aFilterGivesTheSameVerdictAndTheSameFrameLiveAndSearched() throws Exception {
    feed = new TelemetryLiveFeed();
    feed.objectMapper = JSON;
    feed.start();

    List<StoredLog> logs =
        List.of(
            log(T0, 17, "Connection refused by qits-githost", CI),
            log(T0.plusSeconds(1), 9, "started in 3s", CI),
            log(T0.plusSeconds(2), 17, "token rejected", IDP));
    List<StoredSpan> spans =
        List.of(
            span(T0.plusSeconds(3), "GET /ci/api/runs", CI),
            span(T0.plusSeconds(4), "GET /idp", IDP));
    List<MetricPoint> metrics = List.of(metric(T0.plusSeconds(5), 1.0));
    store.addLogs(logs);
    store.addSpans(spans);
    store.addMetrics(metrics);

    List<String> filters =
        List.of(
            "[{\"conditions\":[]}]",
            "[{\"conditions\":[{\"field\":\"severity\",\"op\":\"min\",\"value\":\"ERROR\"}]}]",
            "[{\"conditions\":[{\"field\":\"service\",\"op\":\"exact\",\"value\":\"qits-ci\"},"
                + "{\"field\":\"kind\",\"op\":\"exact\",\"value\":\"span\"}]}]",
            "[{\"conditions\":[{\"field\":\"body\",\"op\":\"contains\",\"value\":\"REFUSED\"}]},"
                + "{\"conditions\":[{\"field\":\"kind\",\"op\":\"exact\",\"value\":\"metric\"}]}]",
            "[{\"conditions\":[{\"field\":\"resource\",\"key\":\"service.version\",\"op\":\"prefix\","
                + "\"value\":\"2026.912\"}]}]",
            "[{\"conditions\":[{\"field\":\"event\",\"op\":\"exists\",\"value\":false}]}]");

    int n = 0;
    for (String groups : filters) {
      RecordingSink live = new RecordingSink("live-" + n);
      RecordingSink sentinel = new RecordingSink("sentinel-" + n);
      feed.opened(live);
      feed.opened(sentinel);
      feed.subscribe(live.id(), "{\"subscribe\":" + groups + "}");
      feed.subscribe(
          sentinel.id(),
          "{\"subscribe\":[{\"conditions\":[{\"field\":\"body\",\"op\":\"exact\","
              + "\"value\":\"sentinel-" + n + "\"}]}]}");

      feed.publishLogs(logs);
      feed.publishSpans(spans);
      feed.publishMetrics(metrics);
      // One dispatcher thread, FIFO: once the sentinel is through, every batch above is too.
      feed.publishLogs(List.of(log(T0, 9, "sentinel-" + n, Map.of())));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (sentinel.frames.isEmpty() && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertEquals(1, sentinel.frames.size(), "the live feed dispatched the batches");

      List<String> searched = new ArrayList<>();
      for (TelemetryStreamFrame frame :
          search(filter(groups), T0, T0.plusSeconds(60), 100).records()) {
        searched.add(JSON.writeValueAsString(frame));
      }
      assertEquals(
          List.copyOf(live.frames).stream()
              .filter(frame -> !frame.contains("sentinel-"))
              .sorted()
              .toList(),
          searched.stream().sorted().toList(),
          "same frames live and searched for " + groups);
      assertFalse(live.frames.isEmpty(), "the filter matched something: " + groups);

      feed.closed(live.id());
      feed.closed(sentinel.id());
      n++;
    }
  }
}
