package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.control.ErrorFingerprinter;
import eu.wohlben.qits.telemetry.control.TelemetryStore;
import eu.wohlben.qits.telemetry.dto.MetricPoint;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.dto.StoredSpan;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OtelReceiverResourceTest {

  private static final String PROTOBUF = "application/x-protobuf";
  private static final String REPO = "repo-1";
  private static final String WORKSPACE = "wt-1";

  @Inject TelemetryStore store;

  @BeforeEach
  void resetStore() {
    store.clear();
  }

  @Test
  void acceptsTraceExportAndStoresSpansWithResourceAttributes() {
    byte[] body =
        TelemetryFixtures.errorTraceRequest(
                "my-service",
                REPO,
                WORKSPACE,
                TelemetryFixtures.TRACE_ID_A,
                TelemetryFixtures.SPAN_ID_A)
            .toByteArray();

    byte[] response =
        given()
            .contentType(PROTOBUF)
            .body(body)
            .when()
            .post("/observability/api/otel/v1/traces")
            .then()
            .statusCode(200)
            .contentType(PROTOBUF)
            .extract()
            .asByteArray();
    assertEquals(0, response.length, "success response is the empty ExportTraceServiceResponse");

    List<StoredSpan> spans = store.spans(REPO, WORKSPACE);
    assertEquals(1, spans.size());
    assertEquals(TelemetryFixtures.TRACE_ID_A, spans.getFirst().traceId());
    assertEquals("my-service", spans.getFirst().serviceName());
    assertEquals(REPO, spans.getFirst().resourceAttributes().get("qits.repository.id"));
    assertTrue(spans.getFirst().hasExceptionEvent());
  }

  @Test
  void acceptsLogsExport() {
    byte[] body =
        TelemetryFixtures.logsRequest(
                "my-service",
                REPO,
                WORKSPACE,
                SeverityNumber.SEVERITY_NUMBER_ERROR,
                "it broke",
                TelemetryFixtures.TRACE_ID_A)
            .toByteArray();

    given()
        .contentType(PROTOBUF)
        .body(body)
        .when()
        .post("/observability/api/otel/v1/logs")
        .then()
        .statusCode(200)
        .contentType(PROTOBUF);

    List<StoredLog> logs = store.logs(REPO, WORKSPACE);
    assertEquals(1, logs.size());
    assertEquals("it broke", logs.getFirst().body());
    assertTrue(logs.getFirst().isError());
    // Workspace telemetry feeds no error issue, so the receiver stamps no fingerprint on it.
    assertFalse(
        logs.getFirst().attributes().containsKey(ErrorFingerprinter.FINGERPRINT_ATTRIBUTE));
  }

  @Test
  void acceptsMetricsExport() {
    byte[] body =
        TelemetryFixtures.metricsRequest("my-service", REPO, WORKSPACE, 12.5, 300).toByteArray();

    given()
        .contentType(PROTOBUF)
        .body(body)
        .when()
        .post("/observability/api/otel/v1/metrics")
        .then()
        .statusCode(200)
        .contentType(PROTOBUF);

    List<MetricPoint> metrics = store.metrics(REPO, WORKSPACE);
    assertEquals(2, metrics.size());
  }

  @Test
  void acceptsGzippedBodyByMagicBytes() {
    byte[] gzipped =
        TelemetryFixtures.gzip(
            TelemetryFixtures.okTraceRequest(
                    "my-service",
                    REPO,
                    WORKSPACE,
                    TelemetryFixtures.TRACE_ID_B,
                    TelemetryFixtures.SPAN_ID_B)
                .toByteArray());

    given()
        .contentType(PROTOBUF)
        .body(gzipped)
        .when()
        .post("/observability/api/otel/v1/traces")
        .then()
        .statusCode(200);

    assertEquals(1, store.trace(REPO, WORKSPACE, TelemetryFixtures.TRACE_ID_B).size());
  }

  @Test
  void rejectsGarbageWith400() {
    given()
        .contentType(PROTOBUF)
        .body(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x13})
        .when()
        .post("/observability/api/otel/v1/traces")
        .then()
        .statusCode(400);
    assertTrue(store.spans(REPO, WORKSPACE).isEmpty());
  }

  @Test
  void emptyBodyIsAValidEmptyExport() {
    given()
        .contentType(PROTOBUF)
        .body(new byte[0])
        .when()
        .post("/observability/api/otel/v1/traces")
        .then()
        .statusCode(200);
  }
}
