package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * Ingest takes an export from nobody in particular — the deployed posture: the request carries no
 * credential and no test identity, so it really is anonymous.
 *
 * <p>The callers of {@code /observability/api/otel/v1/*} are OTel SDK exporters, which carry no
 * identity; a guard here is a guard against every sender at once. This was once {@code
 * @RolesAllowed("qits:system")} and the store stayed empty for five days with nothing logged
 * anywhere, which is exactly the failure this test exists to make loud: it posts the smallest valid
 * export (an empty {@code ExportTraceServiceRequest}) with no header and expects the receiver, not
 * the security layer, to answer.
 */
@QuarkusTest
class OtelIngestAnonymousTest {

  @Test
  void anAnonymousExporterIsAccepted() {
    given()
        .contentType(OtelReceiverResource.PROTOBUF)
        .body(new byte[0])
        .when()
        .post("/observability/api/otel/v1/traces")
        .then()
        .statusCode(200);
  }

  @Test
  void logsAndMetricsTakeTheSameAnonymousExport() {
    for (String signal : new String[] {"logs", "metrics"}) {
      given()
          .contentType(OtelReceiverResource.PROTOBUF)
          .body(new byte[0])
          .when()
          .post("/observability/api/otel/v1/" + signal)
          .then()
          .statusCode(200);
    }
  }
}
