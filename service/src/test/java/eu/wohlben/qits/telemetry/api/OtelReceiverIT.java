package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.TelemetryFixtures;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Ingest against the <em>packaged</em> process — the fast-jar under {@code -DskipITs=false}, the
 * GraalVM binary under {@code -Dnative}.
 *
 * <p>This exists because of native-image, and specifically because of protobuf. {@link
 * eu.wohlben.qits.telemetry.control.TelemetryDecoder} and {@link OtelReceiverResource} are the only
 * things here that touch {@code io.opentelemetry.proto.*}, and generated protobuf messages resolve
 * descriptors, builders and field accessors reflectively — the classic shape of a dependency that is
 * green all through {@code OtelReceiverResourceTest} on the JVM and throws at the first export in
 * the image. A boot check would not catch it: nothing loads a message class until a body arrives.
 *
 * <p>So every assertion here reads the export back out through the REST query surface rather than
 * stopping at the 200. A receiver that accepted the bytes and decoded nothing would answer 200 all
 * day; only the round trip proves the wire format survived compilation. {@code TelemetryStore} is in
 * the other process, so there is no injecting it and no {@code clear()} — each test carries its own
 * repository/workspace scope instead, which is exactly how the buckets are keyed anyway.
 */
@QuarkusIntegrationTest
class OtelReceiverIT {

  private static final String PROTOBUF = "application/x-protobuf";
  private static final String INGEST = "/observability/api/otel/v1";
  private static final String QUERY = "/observability/api/telemetry";

  @Test
  void traceExportDecodesIntoTheQueryableStore() {
    String repo = "it-traces";
    String workspace = "wt-traces";

    given()
        .contentType(PROTOBUF)
        .body(
            TelemetryFixtures.errorTraceRequest(
                    "it-service",
                    repo,
                    workspace,
                    TelemetryFixtures.TRACE_ID_A,
                    TelemetryFixtures.SPAN_ID_A)
                .toByteArray())
        .when()
        .post(INGEST + "/traces")
        .then()
        .statusCode(200)
        .contentType(PROTOBUF);

    // The span, its status, its resource attributes and its nested exception event all came out of
    // the protobuf — hex-encoded ids included, which is ByteString round-tripping through the image.
    given()
        .get(QUERY + "/errors?repositoryId=" + repo + "&workspaceId=" + workspace)
        .then()
        .statusCode(200)
        .body("groups", hasSize(1))
        .body("groups[0].traceId", equalTo(TelemetryFixtures.TRACE_ID_A))
        .body("groups[0].serviceName", equalTo("it-service"))
        .body("groups[0].errorSpans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_A))
        .body("groups[0].errorSpans[0].name", equalTo("GET /boom"))
        .body(
            "groups[0].errorSpans[0].events[0].attributes.'exception.type'",
            equalTo("java.lang.IllegalStateException"));
  }

  @Test
  void logExportDecodesIntoTheQueryableStore() {
    String repo = "it-logs";
    String workspace = "wt-logs";

    given()
        .contentType(PROTOBUF)
        .body(
            TelemetryFixtures.logsRequest(
                    "it-service",
                    repo,
                    workspace,
                    SeverityNumber.SEVERITY_NUMBER_ERROR,
                    "it broke in the binary",
                    TelemetryFixtures.TRACE_ID_A)
                .toByteArray())
        .when()
        .post(INGEST + "/logs")
        .then()
        .statusCode(200);

    given()
        .get(QUERY + "/logs?repositoryId=" + repo + "&workspaceId=" + workspace)
        .then()
        .statusCode(200)
        .body("logs", hasSize(1))
        .body("logs[0].body", equalTo("it broke in the binary"))
        .body("logs[0].severityText", equalTo("ERROR"))
        .body("logs[0].traceId", equalTo(TelemetryFixtures.TRACE_ID_A));
  }

  @Test
  void metricExportDecodesBothGaugeAndSum() {
    String repo = "it-metrics";
    String workspace = "wt-metrics";

    given()
        .contentType(PROTOBUF)
        .body(TelemetryFixtures.metricsRequest("it-service", repo, workspace, 12.5, 300).toByteArray())
        .when()
        .post(INGEST + "/metrics")
        .then()
        .statusCode(200);

    // The oneof discriminators (Gauge vs Sum, asDouble vs asInt) are read through the generated
    // has*/get* accessors, so a wrong answer here means the message classes decoded partially.
    given()
        .get(QUERY + "/metrics?repositoryId=" + repo + "&workspaceId=" + workspace)
        .then()
        .statusCode(200)
        .body("metrics", hasSize(2))
        .body("metrics.name", hasItem("jvm.memory.used"))
        .body("metrics.find { it.name == 'jvm.memory.used' }.value", equalTo(12.5f))
        .body("metrics.find { it.name == 'jvm.memory.used' }.type", equalTo("GAUGE"))
        .body("metrics.find { it.name == 'http.server.requests' }.value", equalTo(300.0f))
        .body("metrics.find { it.name == 'http.server.requests' }.type", equalTo("COUNTER"));
  }

  @Test
  void gzippedExportIsDetectedByMagicBytes() {
    String repo = "it-gzip";
    String workspace = "wt-gzip";

    given()
        .contentType(PROTOBUF)
        .body(
            TelemetryFixtures.gzip(
                TelemetryFixtures.okTraceRequest(
                        "it-service",
                        repo,
                        workspace,
                        TelemetryFixtures.TRACE_ID_B,
                        TelemetryFixtures.SPAN_ID_B)
                    .toByteArray()))
        .when()
        .post(INGEST + "/traces")
        .then()
        .statusCode(200);

    given()
        .get(
            QUERY
                + "/traces/"
                + TelemetryFixtures.TRACE_ID_B
                + "?repositoryId="
                + repo
                + "&workspaceId="
                + workspace)
        .then()
        .statusCode(200)
        .body("trace.spans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_B));
  }

  /**
   * The path every process on this platform actually takes: an export carrying a {@code
   * service.name} and no qits attributes at all. It has to land in a bucket the sources listing
   * names and {@code ?source=} can reach — before this existed such an export was accepted, stored
   * and then unreachable by any combination of query parameters.
   *
   * <p>This is also where the new read surface meets the image. {@code sources} and {@code store}
   * serialise records the older endpoints never returned — an enum, an {@code Instant}, a nested
   * caps object — and reflective serialisation is exactly the class of thing that is green on the
   * JVM and empty in the binary.
   */
  @Test
  void anUnscopedExportIsBucketedByServiceAndReadableThroughSource() {
    String service = "it-unbucketed-service";
    String source = "_service/" + service;
    String traceId = "9cf7651916cd43dd8448eb211c80319e";
    String spanId = "d9ad6b7169203339";

    given()
        .contentType(PROTOBUF)
        .body(
            TelemetryFixtures.errorTraceRequest(service, null, null, traceId, spanId).toByteArray())
        .when()
        .post(INGEST + "/traces")
        .then()
        .statusCode(200);

    given()
        .get(QUERY + "/sources")
        .then()
        .statusCode(200)
        .body("sources.find { it.key == '" + source + "' }.kind", equalTo("SERVICE"))
        .body("sources.find { it.key == '" + source + "' }.label", equalTo(service))
        .body("sources.find { it.key == '" + source + "' }.spans", equalTo(1))
        .body("sources.find { it.key == '" + source + "' }.services[0].name", equalTo(service))
        .body("sources.find { it.key == '" + source + "' }.oldestReceivedAt", notNullValue());

    given()
        .get(QUERY + "/store")
        .then()
        .statusCode(200)
        .body("startedAt", notNullValue())
        .body("caps.spansPerSource", equalTo(2000))
        .body("maxTotalBytes", equalTo(268435456))
        .body("maxBytesPerSource", equalTo(15728640))
        .body("sourceCount", greaterThan(0));

    given()
        .get(QUERY + "/traces?source=" + source)
        .then()
        .statusCode(200)
        .body("traces.find { it.traceId == '" + traceId + "' }.rootName", equalTo("GET /boom"))
        .body("traces.find { it.traceId == '" + traceId + "' }.rootService", equalTo(service))
        .body("traces.find { it.traceId == '" + traceId + "' }.errorSpanCount", equalTo(1))
        .body("traces.find { it.traceId == '" + traceId + "' }.hasException", equalTo(true))
        .body("traces.find { it.traceId == '" + traceId + "' }.rootMissing", equalTo(false));

    given()
        .get(QUERY + "/traces/" + traceId + "?source=" + source)
        .then()
        .statusCode(200)
        .body("trace.spans[0].spanId", equalTo(spanId))
        .body(
            "trace.spans[0].events[0].attributes.'exception.type'",
            equalTo("java.lang.IllegalStateException"));

    given().get(QUERY + "/logs?source=" + source + "&limit=0").then().statusCode(400);
  }

  /**
   * The log-streaming plan's canary batch, once, through the real artifact — the same payload {@link
   * CanaryLogStreamTest} takes apart assertion by assertion against the suite's JVM. One method
   * rather than seven: the store lives in the other process and cannot be cleared between tests, so
   * splitting this would only make each part depend on the ones before it.
   *
   * <p>What is proven here and nowhere else is that a realistic log export survives native-image.
   * The stack trace is the sharpest of these — a multi-line string travelling through a generated
   * protobuf message, into an attribute map, out through Jackson — and a batch whose severity, trace
   * correlation or exception attributes came back empty would still have answered 200 at ingest.
   */
  @Test
  void theCanaryBatchIsAnswerableThroughTheWholeQuerySurface() {
    String source = "_service/" + TelemetryFixtures.CANARY_SERVICE;

    given()
        .contentType(PROTOBUF)
        .body(TelemetryFixtures.canaryTraceRequest().toByteArray())
        .when()
        .post(INGEST + "/traces")
        .then()
        .statusCode(200);
    given()
        .contentType(PROTOBUF)
        .body(
            TelemetryFixtures.canaryLogsRequest("canary is alive", "canary hit the widget service")
                .toByteArray())
        .when()
        .post(INGEST + "/logs")
        .then()
        .statusCode(200)
        .contentType(PROTOBUF);

    // 1 — the canary is its own source, named by service.name rather than swept into _unscoped.
    given()
        .get(QUERY + "/sources")
        .then()
        .statusCode(200)
        .body("sources.find { it.key == '" + source + "' }.kind", equalTo("SERVICE"))
        .body(
            "sources.find { it.key == '" + source + "' }.label",
            equalTo(TelemetryFixtures.CANARY_SERVICE))
        .body("sources.find { it.key == '" + source + "' }.logs", equalTo(2));

    // 2 — both records, with the severity a screen filters on.
    given()
        .get(QUERY + "/logs?source=" + source)
        .then()
        .statusCode(200)
        .body("logs", hasSize(2))
        .body("logs[0].severityText", equalTo("INFO"))
        .body("logs[0].severityNumber", equalTo(9))
        .body("logs[1].severityText", equalTo("ERROR"))
        .body("logs[1].severityNumber", equalTo(17))
        .body("logs[1].traceId", equalTo(TelemetryFixtures.CANARY_TRACE_ID))
        // …and which build wrote them: a nested map from the resource, serialised by the packaged
        // process rather than the suite's JVM.
        .body(
            "logs[0].resourceAttributes.'service.version'",
            equalTo(TelemetryFixtures.CANARY_VERSION))
        .body(
            "logs[0].resourceAttributes.'service.instance.id'",
            equalTo(TelemetryFixtures.CANARY_INSTANCE_ID));

    // 3 — the error, with its stack trace whole.
    given()
        .get(QUERY + "/errors?source=" + source)
        .then()
        .statusCode(200)
        .body("groups", hasSize(1))
        .body("groups[0].errorLogs", hasSize(1))
        .body(
            "groups[0].errorLogs[0].attributes.'exception.type'",
            equalTo(TelemetryFixtures.CANARY_EXCEPTION_TYPE))
        .body(
            "groups[0].errorLogs[0].attributes.'exception.stacktrace'",
            containsString("CanaryResource.callWidgets(CanaryResource.java:42)"));

    // 4 — and both records on the page of the trace they were emitted inside.
    given()
        .get(QUERY + "/traces/" + TelemetryFixtures.CANARY_TRACE_ID + "?source=" + source)
        .then()
        .statusCode(200)
        .body("trace.spans", hasSize(1))
        .body("trace.spans[0].spanId", equalTo(TelemetryFixtures.CANARY_SPAN_ID))
        .body("trace.spans[0].name", equalTo("GET /canary"))
        .body("trace.logs", hasSize(2));

    // 5 — a later batch is accepted on its own connection and accumulates rather than replacing.
    given()
        .contentType(PROTOBUF)
        .header("Connection", "close")
        .body(
            TelemetryFixtures.canaryLogsRequest("canary is still alive", "canary failed again")
                .toByteArray())
        .when()
        .post(INGEST + "/logs")
        .then()
        .statusCode(200);
    given()
        .get(QUERY + "/logs?source=" + source)
        .then()
        .statusCode(200)
        .body("logs", hasSize(4))
        .body("logs.body", hasItem("canary is still alive"));

    // 7 — restart truth, as far as a packaged fixture can see it: the artifact is launched once, so
    // there is no restart to observe, only whether startedAt is this process's own stamp. A constant
    // or a first-export stamp would fail here, and both are what the UI's "held since" must not say.
    String startedAt =
        given().get(QUERY + "/store").then().statusCode(200).body("evictedLogs", equalTo(0))
            .extract()
            .path("startedAt");
    Instant started = Instant.parse(startedAt);
    Instant now = Instant.now();
    assertTrue(
        started.isBefore(now.plusSeconds(60)) && started.isAfter(now.minus(Duration.ofHours(1))),
        "startedAt must be this process's start, not a constant; got " + startedAt);
  }

  @Test
  void malformedProtobufIsA400AndNotA500() {
    given()
        .contentType(PROTOBUF)
        .body(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x13})
        .when()
        .post(INGEST + "/traces")
        .then()
        .statusCode(400);
  }

  /**
   * The MCP server refuses to start without {@code quarkus.mcp.server.observability.http.root-path},
   * so this route answering at all is what says the extension came up in the packaged process rather
   * than the request falling through to the router's 404.
   */
  @Test
  void mcpEndpointIsMountedUnderTheObservabilitySegment() {
    given()
        .contentType("application/json")
        .accept("application/json, text/event-stream")
        .header("MCP-Protocol-Version", "2026-07-28")
        .header("Mcp-Method", "server/discover")
        .body(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{"
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"it\",\"version\":\"1\"},"
                + "\"io.modelcontextprotocol/clientCapabilities\":{}}}}")
        .when()
        .post("/observability/mcp")
        .then()
        .statusCode(200)
        .body("result.supportedVersions", org.hamcrest.Matchers.hasItem("2026-07-28"));
  }

  /** The framework's own surface moved with {@code quarkus.http.non-application-root-path}. */
  @Test
  void openApiAndSwaggerUiAreServedUnderTheObservabilitySegment() {
    given().get("/observability/q/openapi").then().statusCode(200);
    given().get("/observability/q/swagger-ui/").then().statusCode(200);
  }
}
