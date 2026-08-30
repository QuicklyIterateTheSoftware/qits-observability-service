package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.control.TelemetryDecoder;
import eu.wohlben.qits.telemetry.control.TelemetryStore;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The UI's JSON twins answer from the same store buckets as the MCP tools. */
@QuarkusTest
class WorkspaceTelemetryControllerTest {

  private static final String REPO = "repo-rest";
  private static final String WORKSPACE = "wt-rest";
  private static final String BASE = "/observability/api/telemetry";

  /** The scope every query carries: a filter in the query string, not a path segment. */
  private static final String SCOPE = "repositoryId=" + REPO + "&workspaceId=" + WORKSPACE;

  @Inject TelemetryStore store;

  @Inject TelemetryDecoder decoder;

  @BeforeEach
  void seed() {
    store.clear();
    long now = System.currentTimeMillis();
    store.addSpans(
        decoder.decodeSpans(
            TelemetryFixtures.errorTraceRequest(
                "svc", REPO, WORKSPACE, TelemetryFixtures.TRACE_ID_A, TelemetryFixtures.SPAN_ID_A),
            now));
    store.addLogs(
        decoder.decodeLogs(
            TelemetryFixtures.logsRequest(
                "svc",
                REPO,
                WORKSPACE,
                SeverityNumber.SEVERITY_NUMBER_ERROR,
                "rest error log",
                TelemetryFixtures.TRACE_ID_A),
            now));
    store.addMetrics(
        decoder.decodeMetrics(
            TelemetryFixtures.metricsRequest("svc", REPO, WORKSPACE, 1.5, 3), now));
  }

  @Test
  void errorsGroupsByTrace() {
    given()
        .get(BASE + "/errors?" + SCOPE)
        .then()
        .statusCode(200)
        .body("groups", hasSize(1))
        .body("groups[0].traceId", equalTo(TelemetryFixtures.TRACE_ID_A))
        .body("groups[0].errorSpans", hasSize(1))
        .body("groups[0].errorLogs[0].body", equalTo("rest error log"));
  }

  @Test
  void traceReturnsSpansAndCorrelatedLogs() {
    given()
        .get(BASE + "/traces/" + TelemetryFixtures.TRACE_ID_A + "?" + SCOPE)
        .then()
        .statusCode(200)
        .body("trace.spans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_A))
        .body("trace.logs[0].body", equalTo("rest error log"));
  }

  @Test
  void slowSpansRespectsThreshold() {
    // The fixture span lasts 250ms.
    given()
        .get(BASE + "/slow-spans?" + SCOPE + "&thresholdMs=100")
        .then()
        .statusCode(200)
        .body("spans", hasSize(1))
        .body("spans[0].durationMs", greaterThanOrEqualTo(250));
    given()
        .get(BASE + "/slow-spans?" + SCOPE + "&thresholdMs=10000")
        .then()
        .statusCode(200)
        .body("spans", hasSize(0));
  }

  @Test
  void slowSpansSortRecentOrdersByStartTimeDesc() {
    // A later-starting but faster span: with the default duration sort it comes second,
    // with sort=recent it comes first.
    store.addSpans(
        decoder.decodeSpans(
            TelemetryFixtures.traceRequest(
                TelemetryFixtures.resource("svc", REPO, WORKSPACE),
                TelemetryFixtures.spanBuilder(
                        TelemetryFixtures.TRACE_ID_B,
                        TelemetryFixtures.SPAN_ID_B,
                        "GET /later",
                        2_000_000_000L,
                        2_100_000_000L)
                    .build()),
            System.currentTimeMillis()));
    given()
        .get(BASE + "/slow-spans?" + SCOPE + "&thresholdMs=0")
        .then()
        .statusCode(200)
        .body("spans", hasSize(2))
        .body("spans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_A));
    given()
        .get(BASE + "/slow-spans?" + SCOPE + "&thresholdMs=0&sort=recent")
        .then()
        .statusCode(200)
        .body("spans", hasSize(2))
        .body("spans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_B));
  }

  @Test
  void logsFilterByQueryAndService() {
    given()
        .get(BASE + "/logs?" + SCOPE + "&query=REST")
        .then()
        .statusCode(200)
        .body("logs", hasSize(1))
        .body("logs[0].serviceName", equalTo("svc"));
    given()
        .get(BASE + "/logs?" + SCOPE + "&service=unknown-svc")
        .then()
        .statusCode(200)
        .body("logs", hasSize(0));
  }

  /**
   * The severity floor is the service's, not the screen's — and it is applied before the answer is
   * cut, which is the whole reason it is a parameter. The band names are floors, so WARN admits the
   * ERROR record too, and INFO admits all three.
   */
  @Test
  void logsFilterByMinSeverity() {
    long now = System.currentTimeMillis();
    store.addLogs(
        decoder.decodeLogs(
            TelemetryFixtures.logsRequest(
                "svc", REPO, WORKSPACE, SeverityNumber.SEVERITY_NUMBER_INFO, "an info line", null),
            now));
    store.addLogs(
        decoder.decodeLogs(
            TelemetryFixtures.logsRequest(
                "svc", REPO, WORKSPACE, SeverityNumber.SEVERITY_NUMBER_WARN, "a warning", null),
            now));

    given()
        .get(BASE + "/logs?" + SCOPE)
        .then()
        .statusCode(200)
        .body("logs", hasSize(3));
    given()
        .get(BASE + "/logs?" + SCOPE + "&minSeverity=INFO")
        .then()
        .statusCode(200)
        .body("logs", hasSize(3));
    given()
        .get(BASE + "/logs?" + SCOPE + "&minSeverity=warn")
        .then()
        .statusCode(200)
        .body("logs", hasSize(2))
        .body("logs.body", hasItem("a warning"))
        .body("logs.body", not(hasItem("an info line")));
    given()
        .get(BASE + "/logs?" + SCOPE + "&minSeverity=ERROR")
        .then()
        .statusCode(200)
        .body("logs", hasSize(1))
        .body("logs[0].body", equalTo("rest error log"));
    // The raw OTel number is the same filter: 17 is where the ERROR band starts.
    given()
        .get(BASE + "/logs?" + SCOPE + "&minSeverity=17")
        .then()
        .statusCode(200)
        .body("logs", hasSize(1));
  }

  /** A misspelt band stops the request. Silently answering "everything" would read as "no errors". */
  @Test
  void unknownMinSeverityIsRefused() {
    for (String bad : new String[] {"nonsense", "0", "25", "-3"}) {
      given()
          .get(BASE + "/logs?" + SCOPE + "&minSeverity=" + bad)
          .then()
          .statusCode(400)
          .body("message", notNullValue());
    }
    // Blank is "no floor", not a mistake: it is what an unset dropdown sends.
    given().get(BASE + "/logs?" + SCOPE + "&minSeverity=").then().statusCode(200);
  }

  @Test
  void metricsReturnLatestPerSeriesWithNameFilter() {
    given().get(BASE + "/metrics?" + SCOPE).then().statusCode(200).body("metrics", hasSize(2));
    given()
        .get(BASE + "/metrics?" + SCOPE + "&name=jvm.memory.used")
        .then()
        .statusCode(200)
        .body("metrics", hasSize(1))
        .body("metrics[0].value", equalTo(1.5f));
  }

  @Test
  void anotherWorkspaceSeesNothing() {
    given()
        .get(BASE + "/errors?repositoryId=" + REPO + "&workspaceId=elsewhere")
        .then()
        .statusCode(200)
        .body("groups", hasSize(0));
  }

  // --- the source axis -------------------------------------------------------------------------

  /** Seeds a service-bucketed trace: no qits.* attributes, so the store keys it on service.name. */
  private void seedServiceTelemetry(String service, String traceId, String spanId) {
    long now = System.currentTimeMillis();
    store.addSpans(
        decoder.decodeSpans(
            TelemetryFixtures.errorTraceRequest(service, null, null, traceId, spanId), now));
    store.addLogs(
        decoder.decodeLogs(
            TelemetryFixtures.logsRequest(
                service, null, null, SeverityNumber.SEVERITY_NUMBER_ERROR, "unscoped log", traceId),
            now));
  }

  @Test
  void sourcesListsBothKindsOfBucketWithTheirCounts() {
    seedServiceTelemetry("qits-ci", TelemetryFixtures.TRACE_ID_B, TelemetryFixtures.SPAN_ID_B);

    given()
        .get(BASE + "/sources")
        .then()
        .statusCode(200)
        .body("sources", hasSize(2))
        .body("sources.find { it.key == '_service/qits-ci' }.kind", equalTo("SERVICE"))
        .body("sources.find { it.key == '_service/qits-ci' }.label", equalTo("qits-ci"))
        .body("sources.find { it.key == '_service/qits-ci' }.repositoryId", nullValue())
        .body("sources.find { it.key == '_service/qits-ci' }.spans", equalTo(1))
        .body("sources.find { it.key == '_service/qits-ci' }.logs", equalTo(1))
        .body("sources.find { it.key == '_service/qits-ci' }.services[0].name", equalTo("qits-ci"))
        .body("sources.find { it.key == '_service/qits-ci' }.oldestReceivedAt", notNullValue())
        .body("sources.find { it.key == 'repo-rest/wt-rest' }.kind", equalTo("WORKSPACE"))
        .body("sources.find { it.key == 'repo-rest/wt-rest' }.label", equalTo(WORKSPACE))
        .body("sources.find { it.key == 'repo-rest/wt-rest' }.repositoryId", equalTo(REPO))
        .body("sources.find { it.key == 'repo-rest/wt-rest' }.workspaceId", equalTo(WORKSPACE))
        .body("sources.find { it.key == 'repo-rest/wt-rest' }.metricSeries", equalTo(2));
  }

  @Test
  void storeStateReportsTheCapsAndWhatWasDropped() {
    given()
        .get(BASE + "/store")
        .then()
        .statusCode(200)
        .body("startedAt", notNullValue())
        .body("caps.spansPerSource", equalTo(2000))
        .body("caps.logsPerSource", equalTo(10000))
        .body("caps.metricSeriesPerSource", equalTo(500))
        .body("maxTotalBytes", equalTo(67108864))
        .body("sourceCount", equalTo(1))
        .body("evictedSpans", equalTo(0))
        .body("totalBytes", greaterThan(0));
  }

  @Test
  void sourceReachesTheServiceBucketNoWorkspacePairCanName() {
    seedServiceTelemetry("qits-ci", TelemetryFixtures.TRACE_ID_B, TelemetryFixtures.SPAN_ID_B);

    given()
        .get(BASE + "/traces/" + TelemetryFixtures.TRACE_ID_B + "?source=_service/qits-ci")
        .then()
        .statusCode(200)
        .body("trace.spans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_B))
        .body("trace.spans[0].serviceName", equalTo("qits-ci"))
        .body("trace.logs[0].body", equalTo("unscoped log"));

    given()
        .get(BASE + "/errors?source=_service/qits-ci")
        .then()
        .statusCode(200)
        .body("groups", hasSize(1))
        .body("groups[0].serviceName", equalTo("qits-ci"));
    given()
        .get(BASE + "/logs?source=_service/qits-ci")
        .then()
        .statusCode(200)
        .body("logs", hasSize(1));
    given()
        .get(BASE + "/slow-spans?source=_service/qits-ci&thresholdMs=0")
        .then()
        .statusCode(200)
        .body("spans", hasSize(1));
  }

  @Test
  void sourceWinsOverThePairWhenBothAreGiven() {
    seedServiceTelemetry("qits-ci", TelemetryFixtures.TRACE_ID_B, TelemetryFixtures.SPAN_ID_B);

    given()
        .get(BASE + "/logs?source=_service/qits-ci&" + SCOPE)
        .then()
        .statusCode(200)
        .body("logs", hasSize(1))
        .body("logs[0].body", equalTo("unscoped log"));
  }

  /**
   * An unknown source answers the same empty list an unknown workspace does, and on purpose: the
   * store cannot tell "never existed" from "evicted", so the surface does not pretend to. The
   * sources listing is what makes the two distinguishable, and it is one request away.
   */
  @Test
  void anUnknownSourceIsEmptyRatherThanA404() {
    given()
        .get(BASE + "/logs?source=_service/not-a-service")
        .then()
        .statusCode(200)
        .body("logs", hasSize(0))
        .body("total", equalTo(0))
        .body("truncated", equalTo(false));
    given()
        .get(BASE + "/traces?source=_service/not-a-service")
        .then()
        .statusCode(200)
        .body("traces", hasSize(0));
    given()
        .get(BASE + "/traces/" + TelemetryFixtures.TRACE_ID_A + "?source=_service/not-a-service")
        .then()
        .statusCode(200)
        .body("trace.spans", hasSize(0));
    given()
        .get(BASE + "/sources")
        .then()
        .statusCode(200)
        .body("sources.key", not(hasItem("_service/not-a-service")));
  }

  // --- the trace list --------------------------------------------------------------------------

  /** Two traces in one bucket: a long slow one that started first, a short one that started later. */
  private void seedTwoTraces() {
    long now = System.currentTimeMillis();
    store.addSpans(
        decoder.decodeSpans(
            TelemetryFixtures.traceRequest(
                TelemetryFixtures.resource("svc", REPO, WORKSPACE),
                TelemetryFixtures.spanBuilder(
                        TelemetryFixtures.TRACE_ID_B,
                        TelemetryFixtures.SPAN_ID_B,
                        "GET /quick",
                        5_000_000_000L,
                        5_010_000_000L)
                    .addAttributes(TelemetryFixtures.attribute("http.route", "/quick"))
                    .build()),
            now));
  }

  @Test
  void traceListSummarisesEachTraceAndHonoursBothLenses() {
    seedTwoTraces();

    // Recent: TRACE_ID_B starts at 5s, TRACE_ID_A at 1s.
    given()
        .get(BASE + "/traces?" + SCOPE + "&sort=recent")
        .then()
        .statusCode(200)
        .body("traces", hasSize(2))
        .body("total", equalTo(2))
        .body("truncated", equalTo(false))
        .body("traces[0].traceId", equalTo(TelemetryFixtures.TRACE_ID_B))
        .body("traces[0].rootName", equalTo("GET /quick"))
        .body("traces[0].rootService", equalTo("svc"))
        .body("traces[0].rootRoute", equalTo("/quick"))
        .body("traces[0].services", hasItem("svc"))
        .body("traces[0].durationMs", equalTo(10))
        .body("traces[0].spanCount", equalTo(1))
        .body("traces[0].errorSpanCount", equalTo(0))
        .body("traces[0].hasException", equalTo(false))
        .body("traces[0].rootMissing", equalTo(false));

    // Duration: the 250ms error trace outranks the 10ms one.
    given()
        .get(BASE + "/traces?" + SCOPE + "&sort=duration")
        .then()
        .statusCode(200)
        .body("traces[0].traceId", equalTo(TelemetryFixtures.TRACE_ID_A))
        .body("traces[0].durationMs", equalTo(250))
        .body("traces[0].errorSpanCount", equalTo(1))
        .body("traces[0].hasException", equalTo(true));
  }

  @Test
  void traceListDefaultsToRecentAndFiltersOnThresholdAndService() {
    seedTwoTraces();

    // No sort= at all: the default lens is recent, so the later-starting trace leads.
    given()
        .get(BASE + "/traces?" + SCOPE)
        .then()
        .statusCode(200)
        .body("traces[0].traceId", equalTo(TelemetryFixtures.TRACE_ID_B));
    given()
        .get(BASE + "/traces?" + SCOPE + "&thresholdMs=100")
        .then()
        .statusCode(200)
        .body("traces", hasSize(1))
        .body("traces[0].traceId", equalTo(TelemetryFixtures.TRACE_ID_A));
    given()
        .get(BASE + "/traces?" + SCOPE + "&service=nobody")
        .then()
        .statusCode(200)
        .body("traces", hasSize(0));
  }

  /**
   * A child whose parent is not buffered must not be promoted to root silently — the row says so
   * with {@code rootMissing}, which is the common case in a buffer that evicts oldest-first.
   */
  @Test
  void aTraceWithNoBufferedRootIsFlaggedRatherThanGuessedAt() {
    store.clear();
    store.addSpans(
        decoder.decodeSpans(
            TelemetryFixtures.traceRequest(
                TelemetryFixtures.resource("svc", REPO, WORKSPACE),
                TelemetryFixtures.spanBuilder(
                        TelemetryFixtures.TRACE_ID_A,
                        TelemetryFixtures.SPAN_ID_B,
                        "child work",
                        2_000_000_000L,
                        2_100_000_000L)
                    .setParentSpanId(
                        com.google.protobuf.ByteString.copyFrom(
                            java.util.HexFormat.of().parseHex(TelemetryFixtures.SPAN_ID_A)))
                    .build()),
            System.currentTimeMillis()));

    given()
        .get(BASE + "/traces?" + SCOPE)
        .then()
        .statusCode(200)
        .body("traces", hasSize(1))
        .body("traces[0].rootMissing", equalTo(true))
        .body("traces[0].rootName", equalTo("child work"))
        .body("traces[0].rootRoute", nullValue());
  }

  // --- limits ----------------------------------------------------------------------------------

  @Test
  void limitBoundsTheAnswerAndSaysSo() {
    long now = System.currentTimeMillis();
    for (int i = 0; i < 5; i++) {
      store.addLogs(
          decoder.decodeLogs(
              TelemetryFixtures.logsRequest(
                  "svc",
                  REPO,
                  WORKSPACE,
                  SeverityNumber.SEVERITY_NUMBER_INFO,
                  "bulk log " + i,
                  ""),
              now));
    }

    given()
        .get(BASE + "/logs?" + SCOPE + "&limit=2")
        .then()
        .statusCode(200)
        .body("logs", hasSize(2))
        .body("total", equalTo(6))
        .body("truncated", equalTo(true))
        // A tail keeps the newest matches, and still reads oldest-first.
        .body("logs[1].body", equalTo("bulk log 4"));
    given()
        .get(BASE + "/logs?" + SCOPE)
        .then()
        .statusCode(200)
        // The default limit is 200, well above six, so nothing is trimmed.
        .body("logs", hasSize(6))
        .body("truncated", equalTo(false));
  }

  @Test
  void anImpossibleLimitIsRefusedRatherThanClamped() {
    for (String bad : new String[] {"0", "-1", "1001"}) {
      given()
          .get(BASE + "/logs?" + SCOPE + "&limit=" + bad)
          .then()
          .statusCode(400)
          .body("message", equalTo("limit must be between 1 and 1000"));
    }
    given().get(BASE + "/errors?" + SCOPE + "&limit=0").then().statusCode(400);
    given().get(BASE + "/slow-spans?" + SCOPE + "&limit=0").then().statusCode(400);
    given().get(BASE + "/traces?" + SCOPE + "&limit=0").then().statusCode(400);
    given().get(BASE + "/logs?" + SCOPE + "&limit=1000").then().statusCode(200);
  }

  // --- the service filter on the three that lacked it -------------------------------------------

  @Test
  void serviceNarrowsErrorsSlowSpansAndMetrics() {
    given()
        .get(BASE + "/errors?" + SCOPE + "&service=svc")
        .then()
        .statusCode(200)
        .body("groups", hasSize(1));
    given()
        .get(BASE + "/errors?" + SCOPE + "&service=other")
        .then()
        .statusCode(200)
        .body("groups", hasSize(0));
    given()
        .get(BASE + "/slow-spans?" + SCOPE + "&thresholdMs=0&service=other")
        .then()
        .statusCode(200)
        .body("spans", hasSize(0));
    given()
        .get(BASE + "/metrics?" + SCOPE + "&service=svc")
        .then()
        .statusCode(200)
        .body("metrics", hasSize(2));
    given()
        .get(BASE + "/metrics?" + SCOPE + "&service=other")
        .then()
        .statusCode(200)
        .body("metrics", hasSize(0));
  }
}
