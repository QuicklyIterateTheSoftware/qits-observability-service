package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code qits:agent}, a commissioned agent's own role: it reads the telemetry API and opens the live
 * stream. This service has no guarded write; ingest stays {@code @PermitAll}.
 *
 * <p>Each test names its identity with {@code @TestSecurity}, so the identity holds exactly the role
 * the test means and nothing else.
 */
@QuarkusTest
class AgentReadAccessTest {

  private static final String[] READS = {
    "/observability/api/telemetry/store",
    "/observability/api/telemetry/sources",
    "/observability/api/telemetry/errors",
    "/observability/api/telemetry/traces",
    "/observability/api/telemetry/slow-spans",
    "/observability/api/telemetry/logs",
    "/observability/api/telemetry/metrics"
  };

  @TestHTTPResource("/observability/stream")
  URI endpoint;

  @Test
  @TestSecurity(user = "dyn-workspace-agent", roles = "qits:agent")
  void anAgentReadsTheTelemetryApi() {
    for (String path : READS) {
      given().get(path).then().statusCode(200);
    }
  }

  @Test
  @TestSecurity(user = "dyn-workspace-agent", roles = "qits:agent")
  void anAgentOpensTheLiveStream() {
    assertEquals(101, StreamClient.refusal(endpoint, Map.of()));
  }

  /**
   * {@code qits:admin-agent} alone — without {@code qits:admin} and without {@code qits:agent} —
   * is admitted wherever {@code qits:admin} is (qits-628 follow-up).
   */
  @Test
  @TestSecurity(user = "dyn-admin-workspace-agent", roles = "qits:admin-agent")
  void anAdminAgentReadsTheTelemetryApi() {
    for (String path : READS) {
      given().get(path).then().statusCode(200);
    }
  }

  @Test
  @TestSecurity(user = "dyn-admin-workspace-agent", roles = "qits:admin-agent")
  void anAdminAgentOpensTheLiveStream() {
    assertEquals(101, StreamClient.refusal(endpoint, Map.of()));
  }

  /** {@code qits:desk-admin} (epic qits-1621): admitted wherever {@code qits:admin-agent} is. */
  @Test
  @TestSecurity(user = "dyn-desk-admin", roles = "qits:desk-admin")
  void aDeskAdminReadsTheTelemetryApi() {
    given().get("/observability/api/telemetry/store").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "dyn-desk-admin", roles = "qits:desk-admin")
  void aDeskAdminOpensTheLiveStream() {
    assertEquals(101, StreamClient.refusal(endpoint, Map.of()));
  }

  @Test
  @TestSecurity(user = "dyn-workspace-agent", roles = "qits:reader")
  void aRoleOutsideTheBoundaryIsStillRefused() {
    given().get("/observability/api/telemetry/sources").then().statusCode(403);
    assertEquals(403, StreamClient.refusal(endpoint, Map.of()));
  }
}
