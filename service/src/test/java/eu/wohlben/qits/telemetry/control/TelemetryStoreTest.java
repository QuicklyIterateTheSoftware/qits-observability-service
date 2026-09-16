package eu.wohlben.qits.telemetry.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.dto.MetricPoint;
import eu.wohlben.qits.telemetry.dto.SpanEvent;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.dto.StoredSource;
import eu.wohlben.qits.telemetry.dto.StoredSpan;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Plain-JUnit test of the store's bounding, indexing and bucket isolation — no Quarkus needed. */
class TelemetryStoreTest {

  private TelemetryStore store;

  @BeforeEach
  void setUp() {
    store = new TelemetryStore();
  }

  private static Map<String, String> qitsAttributes(String repoId, String workspaceId) {
    return Map.of(
        "service.name", "svc", "qits.repository.id", repoId, "qits.workspace.id", workspaceId);
  }

  private static StoredSpan span(
      String traceId, String name, Map<String, String> resourceAttributes, long receivedAt) {
    return new StoredSpan(
        traceId,
        "span-" + name,
        "",
        "svc",
        "scope",
        name,
        "SERVER",
        1_000_000_000L,
        1_250_000_000L,
        "UNSET",
        "",
        Map.of(),
        List.of(),
        resourceAttributes,
        receivedAt);
  }

  /**
   * A span whose {@code serviceName} agrees with its {@code service.name} resource attribute, the
   * way {@link TelemetryDecoder} produces one. {@link #span} hardcodes "svc" instead, which is
   * fine where the service is irrelevant and wrong where the bucketing keys on it.
   */
  private static StoredSpan serviceSpan(String traceId, String name, String service, long at) {
    Map<String, String> resource = Map.of("service.name", service);
    StoredSpan template = span(traceId, name, resource, at);
    return new StoredSpan(
        template.traceId(),
        template.spanId(),
        template.parentSpanId(),
        service,
        template.scopeName(),
        template.name(),
        template.kind(),
        template.startEpochNanos(),
        template.endEpochNanos(),
        template.status(),
        template.statusMessage(),
        template.attributes(),
        template.events(),
        resource,
        template.receivedAtMillis());
  }

  private static StoredLog log(
      String body, Map<String, String> resourceAttributes, long receivedAt) {
    return new StoredLog(
        1_000_000_000L, 9, "INFO", body, "", "", "svc", Map.of(), resourceAttributes, receivedAt);
  }

  private static MetricPoint metric(
      String name, double value, Map<String, String> attributes, Map<String, String> resource) {
    return new MetricPoint(
        name, "", "By", "GAUGE", value, 1_000_000_000L, attributes, "svc", resource, 1L);
  }

  /** Overrides {@link TelemetryChangePublisher#fire} to record instead of routing through CDI. */
  private static final class RecordingPublisher extends TelemetryChangePublisher {
    final List<TelemetryChanged> fired = new CopyOnWriteArrayList<>();

    @Override
    public void fire(String repoId, String workspaceId) {
      fired.add(new TelemetryChanged(repoId, workspaceId));
    }
  }

  @Test
  void appendingScopedTelemetryFiresOneTelemetryHintPerWorkspace() {
    RecordingPublisher publisher = new RecordingPublisher();
    store.changePublisher = publisher;

    store.addSpans(
        List.of(
            span("t1", "a", qitsAttributes("repo", "wt"), 1),
            span("t2", "b", qitsAttributes("repo", "wt"), 2)));
    store.addLogs(List.of(log("hi", qitsAttributes("repo", "wt"), 3)));
    store.addMetrics(List.of(metric("m", 1.0, Map.of(), qitsAttributes("repo", "wt"))));

    // Two spans for one workspace coalesce to one hint; each append method fires once → 3 total.
    // (The monorepo also asserted topic() == Topic.TELEMETRY; TelemetryChanged has no topic
    // field — it IS the telemetry topic — so the event type carries that assertion now.)
    assertEquals(3, publisher.fired.size());
    assertTrue(
        publisher.fired.stream()
            .allMatch(h -> h.repoId().equals("repo") && h.workspaceId().equals("wt")));
  }

  @Test
  void aBatchSpanningTwoWorkspacesFiresAHintForEach() {
    RecordingPublisher publisher = new RecordingPublisher();
    store.changePublisher = publisher;

    store.addSpans(
        List.of(
            span("t1", "a", qitsAttributes("repo", "wt-a"), 1),
            span("t2", "b", qitsAttributes("repo", "wt-b"), 2)));

    assertEquals(2, publisher.fired.size());
    assertTrue(publisher.fired.stream().anyMatch(h -> h.workspaceId().equals("wt-a")));
    assertTrue(publisher.fired.stream().anyMatch(h -> h.workspaceId().equals("wt-b")));
  }

  @Test
  void unscopedTelemetryFiresNoHint() {
    RecordingPublisher publisher = new RecordingPublisher();
    store.changePublisher = publisher;

    // No qits.repository.id / qits.workspace.id → lands in the unscoped bucket, nothing subscribes.
    store.addLogs(List.of(log("orphan", Map.of("service.name", "svc"), 1)));

    assertTrue(publisher.fired.isEmpty());
  }

  @Test
  void bucketsAreIsolatedByWorkspace() {
    store.addSpans(List.of(span("t1", "a", qitsAttributes("repoA", "wt1"), 1)));
    store.addSpans(List.of(span("t2", "b", qitsAttributes("repoB", "wt2"), 2)));

    assertEquals(1, store.spans("repoA", "wt1").size());
    assertEquals("a", store.spans("repoA", "wt1").getFirst().name());
    assertEquals(1, store.spans("repoB", "wt2").size());
    assertTrue(store.spans("repoA", "wt2").isEmpty());
  }

  @Test
  void spanCapEvictsOldestAndPrunesTraceIndex() {
    store.maxSpansPerWorkspace = 3;
    Map<String, String> attrs = qitsAttributes("repo", "wt");
    for (int i = 1; i <= 5; i++) {
      store.addSpans(List.of(span("trace-" + i, "span-" + i, attrs, i)));
    }

    List<StoredSpan> remaining = store.spans("repo", "wt");
    assertEquals(3, remaining.size());
    assertEquals("span-3", remaining.getFirst().name());
    assertEquals("span-5", remaining.getLast().name());
    assertTrue(store.trace("repo", "wt", "trace-1").isEmpty(), "evicted span left in trace index");
    assertEquals(1, store.trace("repo", "wt", "trace-4").size());
  }

  @Test
  void byteAccountingReturnsToZeroWhenEverythingEvicts() {
    store.maxSpansPerWorkspace = 1;
    store.maxLogsPerWorkspace = 1;
    Map<String, String> attrs = qitsAttributes("repo", "wt");
    for (int i = 0; i < 4; i++) {
      store.addSpans(List.of(span("t", "s" + i, attrs, i)));
      store.addLogs(List.of(log("l" + i, attrs, i)));
    }
    long expected =
        TelemetrySizeEstimator.bytesOf(store.spans("repo", "wt").getFirst())
            + TelemetrySizeEstimator.bytesOf(store.logs("repo", "wt").getFirst());
    assertEquals(expected, store.totalBytes());

    store.clear();
    assertEquals(0, store.totalBytes());
    assertTrue(store.spans("repo", "wt").isEmpty());
  }

  @Test
  void globalCeilingEvictsFromFattestBucketFirst() {
    // The global backstop is what is under test here, so take the per-source budget out of the way.
    store.maxBytesPerSource = Long.MAX_VALUE;
    Map<String, String> chatty = qitsAttributes("repo", "chatty");
    Map<String, String> quiet = qitsAttributes("repo", "quiet");
    store.addLogs(List.of(log("quiet log", quiet, 1)));
    long quietBytes = store.totalBytes();

    // A ceiling that fits the quiet log plus roughly two chatty logs.
    store.maxTotalBytes =
        quietBytes + 3 * TelemetrySizeEstimator.bytesOf(log("chatty 0", chatty, 0));
    for (int i = 0; i < 20; i++) {
      store.addLogs(List.of(log("chatty " + i, chatty, 10 + i)));
    }

    assertEquals(1, store.logs("repo", "quiet").size(), "quiet workspace lost telemetry");
    assertTrue(store.totalBytes() <= store.maxTotalBytes);
    List<StoredLog> chattyLogs = store.logs("repo", "chatty");
    assertTrue(chattyLogs.size() < 20, "chatty bucket was not evicted");
    assertEquals("chatty 19", chattyLogs.getLast().body(), "newest chatty log must survive");
  }

  @Test
  void globalCeilingEvictsOldestAcrossSpansAndLogs() {
    // The global backstop is what is under test here, so take the per-source budget out of the way.
    store.maxBytesPerSource = Long.MAX_VALUE;
    Map<String, String> attrs = qitsAttributes("repo", "wt");
    store.addSpans(List.of(span("t-old", "oldest-span", attrs, 1)));
    store.addLogs(List.of(log("newer log", attrs, 2)));
    store.maxTotalBytes = store.totalBytes(); // exactly full — the next append must evict

    store.addLogs(List.of(log("newest log", attrs, 3)));

    assertTrue(store.spans("repo", "wt").isEmpty(), "oldest record was a span; it must go first");
    assertEquals(2, store.logs("repo", "wt").size());
  }

  @Test
  void metricSeriesReplaceInPlaceAndNewSeriesAreCappedButUpdatesStillLand() {
    store.maxMetricSeriesPerWorkspace = 2;
    Map<String, String> attrs = qitsAttributes("repo", "wt");
    store.addMetrics(List.of(metric("m1", 1.0, Map.of("k", "a"), attrs)));
    store.addMetrics(List.of(metric("m1", 2.0, Map.of("k", "a"), attrs)));
    store.addMetrics(List.of(metric("m2", 5.0, Map.of(), attrs)));
    store.addMetrics(List.of(metric("m3", 9.0, Map.of(), attrs))); // over the cap: dropped
    store.addMetrics(List.of(metric("m2", 6.0, Map.of(), attrs))); // update of existing: lands

    List<MetricPoint> metrics = store.metrics("repo", "wt");
    assertEquals(2, metrics.size());
    assertEquals(
        2.0, metrics.stream().filter(m -> m.name().equals("m1")).findFirst().orElseThrow().value());
    assertEquals(
        6.0, metrics.stream().filter(m -> m.name().equals("m2")).findFirst().orElseThrow().value());
  }

  @Test
  void unattributedTelemetryIsQuarantinedNotVisibleToAnyWorkspace() {
    store.addSpans(List.of(span("t", "unscoped", Map.of("service.name", "svc"), 1)));

    assertTrue(store.spans("repo", "wt").isEmpty());
    assertTrue(store.totalBytes() > 0, "unscoped telemetry must still be retained (and bounded)");
  }

  @Test
  void telemetryWithoutTheQitsPairIsBucketedByServiceName() {
    store.addSpans(List.of(serviceSpan("t1", "from-ci", "qits-ci", 1)));
    store.addSpans(List.of(serviceSpan("t2", "from-cd", "qits-cd", 2)));

    assertEquals(1, store.spansIn("_service/qits-ci").size());
    assertEquals("from-ci", store.spansIn("_service/qits-ci").getFirst().name());
    assertEquals(1, store.spansIn("_service/qits-cd").size());
    assertTrue(store.spansIn(TelemetryStore.UNSCOPED_KEY).isEmpty(), "service.name was present");
  }

  @Test
  void telemetryWithNeitherPairNorServiceNameStillLandsInTheUnscopedBucket() {
    store.addSpans(List.of(span("t", "nameless", Map.of(), 1)));
    store.addLogs(List.of(log("nameless", Map.of("service.name", " "), 2)));

    assertEquals(1, store.spansIn(TelemetryStore.UNSCOPED_KEY).size());
    assertEquals(1, store.logsIn(TelemetryStore.UNSCOPED_KEY).size());
  }

  @Test
  void theQitsPairStillWinsOverServiceName() {
    store.addSpans(List.of(span("t", "scoped", qitsAttributes("repo", "wt"), 1)));

    assertEquals(1, store.spans("repo", "wt").size());
    assertTrue(store.spansIn("_service/svc").isEmpty(), "the pair must take precedence");
  }

  @Test
  void oneServiceCannotEvictAnotherNowThatEachHasItsOwnBucket() {
    store.maxSpansPerWorkspace = 3;
    Map<String, String> chatty = Map.of("service.name", "qits-gateway");
    Map<String, String> quiet = Map.of("service.name", "qits-cd");
    store.addSpans(List.of(span("t-quiet", "quiet", quiet, 1)));
    for (int i = 0; i < 20; i++) {
      store.addSpans(List.of(span("t-chatty-" + i, "chatty-" + i, chatty, 10 + i)));
    }

    // The cap is per source, so the chatty one pays its own bill and the quiet one keeps its span.
    assertEquals(3, store.spansIn("_service/qits-gateway").size());
    assertEquals(1, store.spansIn("_service/qits-cd").size());
    assertEquals(17, store.evictedSpans());
  }

  /**
   * The byte-tier twin of {@link #oneServiceCannotEvictAnotherNowThatEachHasItsOwnBucket}. Under the
   * old global-only byte tier this scenario could only come out right by luck of who happened to be
   * fattest at each eviction; with a per-source budget it is a property.
   */
  @Test
  void oneServiceCannotSpendAnotherServicesByteBudget() {
    Map<String, String> chatty = Map.of("service.name", "qits-gateway");
    Map<String, String> quiet = Map.of("service.name", "qits-cd");
    // Fixed-width bodies so every chatty record estimates identically and "3 records" is exact.
    int logBytes = TelemetrySizeEstimator.bytesOf(log("chatty 00", chatty, 0));
    store.maxBytesPerSource = 3L * logBytes;

    store.addLogs(List.of(log("quiet log", quiet, 1)));
    for (int i = 0; i < 20; i++) {
      store.addLogs(List.of(log(String.format("chatty %02d", i), chatty, 10 + i)));
    }

    assertEquals(1, store.logsIn("_service/qits-cd").size(), "the quiet source lost telemetry");
    List<StoredLog> chattyLogs = store.logsIn("_service/qits-gateway");
    assertEquals(3, chattyLogs.size(), "the chatty source is held to its own budget");
    assertEquals("chatty 19", chattyLogs.getLast().body(), "newest chatty log must survive");
    assertEquals(17, store.evictedLogs(), "every drop is counted, not silent");
    assertEquals(0, store.evictedSpans());

    StoredSource chattySource =
        store.sources().stream()
            .filter(s -> s.key().equals("_service/qits-gateway"))
            .findFirst()
            .orElseThrow();
    assertTrue(chattySource.bytes() <= store.maxBytesPerSource, "chatty source is over budget");
  }

  /**
   * The per-source budget is not merely a faster route to the global ceiling's behaviour: with the
   * shipped 256 MiB backstop nowhere near reached, a single source is still held to its own figure.
   */
  @Test
  void aSourceIsHeldToItsOwnBudgetWithTheGlobalCeilingNowhereNearReached() {
    Map<String, String> attrs = Map.of("service.name", "qits-platform-edge");
    int logBytes = TelemetrySizeEstimator.bytesOf(log("record 00", attrs, 0));
    store.maxBytesPerSource = 5L * logBytes;

    for (int i = 0; i < 50; i++) {
      store.addLogs(List.of(log(String.format("record %02d", i), attrs, i)));
    }

    assertTrue(
        store.totalBytes() < store.maxTotalBytes / 2,
        "the global ceiling must be nowhere near binding, or this proves nothing");
    StoredSource source = store.sources().getFirst();
    assertEquals("_service/qits-platform-edge", source.key());
    assertTrue(
        source.bytes() <= store.maxBytesPerSource,
        "the bucket reports " + source.bytes() + " bytes, over its budget");
    assertEquals(5, store.logsIn("_service/qits-platform-edge").size());
    assertEquals(45, store.evictedLogs());
  }

  /**
   * A {@code removeFirst()} shortcut at the byte tier would leave the counters behind and the UI
   * would show a shrinking buffer alongside zero evictions. Pin the accounting and the counting.
   */
  @Test
  void theByteBudgetsEvictionsAreAccountedAndCounted() {
    Map<String, String> attrs = qitsAttributes("repo", "wt");
    // Same shape as the records the loop appends, so "room for exactly one of each" is exact.
    long spanBytes = TelemetrySizeEstimator.bytesOf(span("t0", "s0", attrs, 0));
    long logBytes = TelemetrySizeEstimator.bytesOf(log("l0", attrs, 0));
    // Room for exactly one span and one log; the count caps are far away and cannot be the cause.
    store.maxBytesPerSource = spanBytes + logBytes;

    for (int i = 0; i < 4; i++) {
      store.addSpans(List.of(span("t" + i, "s" + i, attrs, 2 * i)));
      store.addLogs(List.of(log("l" + i, attrs, 2 * i + 1)));
    }

    assertEquals(1, store.spans("repo", "wt").size());
    assertEquals(1, store.logs("repo", "wt").size());
    assertEquals(spanBytes + logBytes, store.totalBytes(), "byte accounting drifted");
    assertEquals(3, store.evictedSpans(), "span evictions at the byte tier must be counted");
    assertEquals(3, store.evictedLogs(), "log evictions at the byte tier must be counted");
    assertEquals(0, store.droppedMetricSeries());
  }

  @Test
  void evictionCountersCountWhatWasDropped() {
    store.maxSpansPerWorkspace = 1;
    store.maxLogsPerWorkspace = 1;
    store.maxMetricSeriesPerWorkspace = 1;
    Map<String, String> attrs = qitsAttributes("repo", "wt");
    for (int i = 0; i < 4; i++) {
      store.addSpans(List.of(span("t" + i, "s" + i, attrs, i)));
      store.addLogs(List.of(log("l" + i, attrs, i)));
      store.addMetrics(List.of(metric("m" + i, i, Map.of(), attrs)));
    }

    assertEquals(3, store.evictedSpans());
    assertEquals(3, store.evictedLogs());
    assertEquals(3, store.droppedMetricSeries(), "new series over the cap are dropped, not evicted");

    store.clear();
    assertEquals(0, store.evictedSpans());
    assertEquals(0, store.evictedLogs());
    assertEquals(0, store.droppedMetricSeries());
  }

  @Test
  void sourcesReportEveryBucketWithItsCountsAndAgeSpan() {
    store.addSpans(List.of(serviceSpan("t1", "a", "qits-ci", 100)));
    store.addSpans(List.of(serviceSpan("t2", "b", "qits-ci", 300)));
    store.addLogs(List.of(log("hello", Map.of("service.name", "qits-ci"), 200)));
    store.addSpans(List.of(span("t3", "c", qitsAttributes("repo", "wt"), 50)));
    store.addSpans(List.of(span("t4", "d", Map.of(), 400)));

    List<StoredSource> sources = store.sources();
    assertEquals(3, sources.size());
    assertEquals(
        List.of("_service/qits-ci", "_unscoped", "repo/wt"),
        sources.stream().map(StoredSource::key).toList(),
        "sources are listed in key order");

    StoredSource ci = sources.getFirst();
    assertEquals(2, ci.spans());
    assertEquals(1, ci.logs());
    assertEquals(100L, ci.oldestReceivedAtMillis(), "the oldest record in the bucket");
    assertEquals(300L, ci.newestReceivedAtMillis(), "the newest record in the bucket");
    assertTrue(ci.bytes() > 0);

    // The breakdown splits by the record's own service name, per signal — the log helper reports
    // "svc", so this bucket honestly holds two.
    assertEquals(List.of("qits-ci", "svc"), ci.services().stream().map(s -> s.name()).toList());
    assertEquals(2, ci.services().getFirst().spans());
    assertEquals(0, ci.services().getFirst().logs());
    assertEquals(1, ci.services().getLast().logs());
  }

  @Test
  void anEmptiedBucketIsStillListedSoItsSilenceIsDistinguishable() {
    store.maxSpansPerWorkspace = 1;
    store.addSpans(List.of(span("t1", "a", Map.of("service.name", "qits-ci"), 1)));
    store.maxSpansPerWorkspace = 0;
    store.addSpans(List.of(span("t2", "b", Map.of("service.name", "qits-ci"), 2)));

    List<StoredSource> sources = store.sources();
    assertEquals(1, sources.size());
    assertEquals(0, sources.getFirst().spans());
    assertEquals(null, sources.getFirst().oldestReceivedAtMillis(), "nothing left to be old");
    assertTrue(store.evictedSpans() > 0, "the counter is what says the silence is eviction");
  }

  /**
   * §1.4 of the observability-UI plan argued from arithmetic that the count caps bind before the
   * byte tier, and the DTO javadoc told operators to read counts rather than bytes on that basis.
   * The 2026-09-16 dev measurement retired that argument — 18 sources, nine of them pinned at the
   * span cap, 91.5% of the then-64 MiB ceiling — and the per-source byte budget was added precisely
   * so the opposite is true: a source at both count caps estimates well <em>above</em> its budget,
   * so the budget binds before the log count cap ever does, deliberately.
   *
   * <p>The fixtures below are the same guard the old test carried: real platform-shaped records run
   * through the real estimator, so estimator drift or a cap change breaks this rather than quietly
   * re-inverting the relationship the store and the DTO javadoc are written around.
   */
  @Test
  void thePerSourceByteBudgetBindsBeforeTheLogCountCap() {
    // A Quarkus server span as the platform actually exports one: ~10 span attributes on top of
    // ~8 resource attributes, http-route-shaped names.
    Map<String, String> resource =
        Map.of(
            "service.name", "qits-observability",
            "service.version", "1.0.0-SNAPSHOT",
            "telemetry.sdk.name", "opentelemetry",
            "telemetry.sdk.language", "java",
            "telemetry.sdk.version", "1.54.0",
            "host.name", "qits-cd-qits-qits-observability-bdc0983f",
            "os.type", "linux",
            "process.runtime.name", "GraalVM Native Image");
    Map<String, String> attributes =
        Map.of(
            "http.request.method", "POST",
            "url.path", "/observability/api/otel/v1/traces",
            "url.scheme", "http",
            "http.response.status_code", "200",
            "http.route", "/observability/api/otel/v1/traces",
            "server.address", "qits-observability",
            "server.port", "8080",
            "network.protocol.version", "1.1",
            "user_agent.original", "OTel-OTLP-Exporter-Java/1.54.0",
            "client.address", "172.18.0.5");
    StoredSpan realistic =
        new StoredSpan(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            "c8ad6b7169203332",
            "qits-observability",
            "io.quarkus.opentelemetry",
            "POST /observability/api/otel/v1/traces",
            "SERVER",
            1_000_000_000L,
            1_250_000_000L,
            "UNSET",
            "",
            attributes,
            List.of(),
            resource,
            1L);

    // An access-log record as qits-platform-edge's OTel logging bridge exports one: the same
    // resource shape, a request line for a body, and the handful of attributes the bridge adds.
    Map<String, String> logResource =
        Map.of(
            "service.name", "qits-platform-edge",
            "service.version", "1.0.0-SNAPSHOT",
            "telemetry.sdk.name", "opentelemetry",
            "telemetry.sdk.language", "java",
            "telemetry.sdk.version", "1.54.0",
            "host.name", "qits-cd-qits-qits-platform-edge-7f31ac02",
            "os.type", "linux",
            "process.runtime.name", "GraalVM Native Image");
    StoredLog realisticLog =
        new StoredLog(
            1_000_000_000L,
            9,
            "INFO",
            "172.18.0.5 - - [16/Sep/2026:09:41:07 +0000] \"GET"
                + " /observability/api/telemetry/sources HTTP/1.1\" 200 4213"
                + " \"https://qits.example/observability/\" \"Mozilla/5.0\" 7ms",
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            "qits-platform-edge",
            Map.of(
                "loggerName", "io.quarkus.http.access-log",
                "thread.name", "vert.x-eventloop-thread-3",
                "log.level", "INFO"),
            logResource,
            1L);

    int spanBytes = TelemetrySizeEstimator.bytesOf(realistic);
    int logBytes = TelemetrySizeEstimator.bytesOf(realisticLog);
    long atBothCountCaps =
        (long) store.maxSpansPerWorkspace * spanBytes + (long) store.maxLogsPerWorkspace * logBytes;

    assertEquals(2000, store.maxSpansPerWorkspace, "the plan's span cap");
    assertEquals(10000, store.maxLogsPerWorkspace, "the shipped log cap");
    assertEquals(15L * 1024 * 1024, store.maxBytesPerSource, "the shipped per-source budget");
    assertTrue(
        atBothCountCaps > store.maxBytesPerSource,
        "one source at both count caps estimates at "
            + atBothCountCaps
            + " bytes ("
            + spanBytes
            + " B/span, "
            + logBytes
            + " B/log), which must exceed the "
            + store.maxBytesPerSource
            + "-byte per-source budget — otherwise the byte tier is unreachable again and the "
            + "fairness this store is built on is back to being decided by the count caps");
    assertTrue(
        store.maxBytesPerSource < store.maxTotalBytes,
        "the global ceiling is a backstop above the per-source budget, not below it");
  }

  @Test
  void exceptionEventAndErrorHelpersWork() {
    StoredSpan error =
        new StoredSpan(
            "t",
            "s",
            "",
            "svc",
            "scope",
            "GET /boom",
            "SERVER",
            0,
            2_000_000L,
            "ERROR",
            "boom",
            Map.of(),
            List.of(new SpanEvent("exception", 1L, Map.of("exception.type", "X"))),
            Map.of(),
            1L);
    assertTrue(error.isError());
    assertTrue(error.hasExceptionEvent());
    assertEquals(2, error.durationMs());
    assertTrue(new StoredLog(1, 17, "ERROR", "b", "", "", "s", Map.of(), Map.of(), 1).isError());
  }
}
