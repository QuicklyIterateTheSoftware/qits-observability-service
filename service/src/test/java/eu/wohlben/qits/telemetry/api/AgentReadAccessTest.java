package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code qits:agent}, a commissioned agent's own role: it reads the telemetry API and opens the live
 * stream. This service has no guarded write; ingest stays {@code @PermitAll}.
 *
 * <p>Each request names its identity in {@code X-Qits-User} / {@code X-Qits-Roles}, so the {@code
 * %test} dev user does not apply and the identity holds exactly the role sent.
 */
@QuarkusTest
class AgentReadAccessTest {

  private static final Map<String, String> AGENT =
      Map.of("X-Qits-User", "dyn-workspace-agent", "X-Qits-Roles", "qits:agent");

  /** An admin workspace's agent (qits-628 follow-up): admitted wherever {@code qits:admin} is. */
  private static final Map<String, String> ADMIN_AGENT =
      Map.of("X-Qits-User", "dyn-admin-workspace-agent", "X-Qits-Roles", "qits:admin-agent");

  private static final Map<String, String> READER =
      Map.of("X-Qits-User", "dyn-workspace-agent", "X-Qits-Roles", "qits:reader");

  @TestHTTPResource("/observability/stream")
  URI endpoint;

  @Test
  void anAgentReadsTheTelemetryApi() {
    for (String path :
        new String[] {
          "/observability/api/telemetry/store",
          "/observability/api/telemetry/sources",
          "/observability/api/telemetry/errors",
          "/observability/api/telemetry/traces",
          "/observability/api/telemetry/slow-spans",
          "/observability/api/telemetry/logs",
          "/observability/api/telemetry/metrics"
        }) {
      given().headers(AGENT).get(path).then().statusCode(200);
    }
  }

  @Test
  void anAgentOpensTheLiveStream() {
    assertEquals(101, StreamClient.refusal(endpoint, AGENT));
  }

  /**
   * {@code qits:admin-agent} alone — without {@code qits:admin} and without {@code qits:agent} —
   * is admitted wherever {@code qits:admin} is (qits-628 follow-up).
   */
  @Test
  void anAdminAgentReadsTheTelemetryApi() {
    for (String path :
        new String[] {
          "/observability/api/telemetry/store",
          "/observability/api/telemetry/sources",
          "/observability/api/telemetry/errors",
          "/observability/api/telemetry/traces",
          "/observability/api/telemetry/slow-spans",
          "/observability/api/telemetry/logs",
          "/observability/api/telemetry/metrics"
        }) {
      given().headers(ADMIN_AGENT).get(path).then().statusCode(200);
    }
  }

  @Test
  void anAdminAgentOpensTheLiveStream() {
    assertEquals(101, StreamClient.refusal(endpoint, ADMIN_AGENT));
  }

  @Test
  void aRoleOutsideTheBoundaryIsStillRefused() {
    given().headers(READER).get("/observability/api/telemetry/sources").then().statusCode(403);
    assertEquals(403, StreamClient.refusal(endpoint, READER));
  }
}
