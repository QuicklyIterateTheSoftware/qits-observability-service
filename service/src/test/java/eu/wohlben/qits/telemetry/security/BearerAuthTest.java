package eu.wohlben.qits.telemetry.security;

import static eu.wohlben.qits.telemetry.security.BearerTokens.bearer;
import static eu.wohlben.qits.telemetry.security.BearerTokens.cliToken;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.api.StreamClient;
import eu.wohlben.qits.telemetry.control.TelemetryLiveFeed;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A person's bearer token, checked by the real quarkus-oidc against a local key, beside the
 * forward-auth headers a browser session keeps arriving with.
 *
 * <p>Why tokens reach this service at all: {@code qits observe} calls through the edge with the
 * person's token, and the edge removes the {@code X-Qits-*} headers from any request that carries
 * one. So the token is the only thing that says who the person is, and its {@code groups} claim is
 * what the {@code @RolesAllowed} boundaries read, on the REST API and on the socket upgrade alike.
 */
@QuarkusTest
@TestProfile(BearerAuthProfile.class)
class BearerAuthTest {

  private static final String STORE = "/observability/api/telemetry/store";

  /** What the edge sends for a signed-in browser session. */
  private static final Map<String, String> SESSION =
      Map.of("X-Qits-User", "alice", "X-Qits-Roles", "qits:admin");

  @TestHTTPResource("/observability/stream")
  URI socket;

  @Inject TelemetryLiveFeed feed;

  // --- a person's CLI token -----------------------------------------------------------------------

  @Test
  void aCliTokenBecomesTheIdentityWithItsGroupsAsRoles() {
    given()
        .header("Authorization", bearer(cliToken("qits:admin")))
        .when()
        .get("/observability/api/test-identity")
        .then()
        .statusCode(200)
        .body("anonymous", equalTo(false))
        .body("principal", equalTo(BearerTokens.SUBJECT))
        .body("roles", contains("qits:admin"));
  }

  @Test
  void aCliTokenReadsTheQueryApi() {
    given()
        .header("Authorization", bearer(cliToken("qits:admin")))
        .when()
        .get(STORE)
        .then()
        .statusCode(200);
  }

  @Test
  void aCliTokenOpensTheStreamAndReceivesWhatItAskedFor() throws Exception {
    String service = "svc-bearer-" + System.nanoTime();
    try (StreamClient client =
        StreamClient.dial(socket, Map.of("Authorization", bearer(cliToken("qits:admin"))))) {
      long before = feed.subscribeFrames();
      client.subscribe(
          "[{\"conditions\":[{\"field\":\"service\",\"op\":\"exact\",\"value\":\""
              + service
              + "\"}]}]");
      long deadline = System.nanoTime() + 10_000_000_000L;
      while (feed.subscribeFrames() <= before) {
        assertTrue(System.nanoTime() < deadline, "the server never handled the subscribe frame");
        Thread.sleep(10);
      }

      // The exporter posts with no credential at all, tenant on or not.
      given()
          .contentType("application/x-protobuf")
          .body(
              TelemetryFixtures.logsRequest(
                      service, null, null, SeverityNumber.SEVERITY_NUMBER_WARN, "seen", null)
                  .toByteArray())
          .when()
          .post("/observability/api/otel/v1/logs")
          .then()
          .statusCode(200);

      JsonNode frame = client.nextJson(Duration.ofSeconds(10));
      assertNotNull(frame, "the subscribed stream said nothing");
      assertEquals("seen", frame.get("record").get("body").asText());
    }
  }

  @Test
  void aPeerServicesMachineTokenIsAcceptedToo() {
    // qits-platform-idp stamps `qits-platform` on every token it mints, a machine token included, so
    // a peer service reaches this one and its roles decide from there.
    given()
        .header("Authorization", bearer(BearerTokens.machineToken("qits:admin")))
        .when()
        .get(STORE)
        .then()
        .statusCode(200);
  }

  // --- tokens that do not get in ------------------------------------------------------------------

  @Test
  void aTokenWithoutTheRoleIsForbidden() {
    // It authenticates, so the answer is 403 and not 401: the person is known, the role is not
    // granted.
    String reader = bearer(cliToken("qits:reader"));
    given().header("Authorization", reader).when().get(STORE).then().statusCode(403);
    assertEquals(403, StreamClient.refusal(socket, Map.of("Authorization", reader)));
  }

  @Test
  void aTokenAddressedOutsideThisPlatformIsUnauthorized() {
    // Not a peer service: a peer's token carries `qits-platform` too and gets in. What the audience
    // check refuses is a token minted for something that is not this platform at all.
    given()
        .header("Authorization", bearer(BearerTokens.tokenFor("some-other-platform", "qits:admin")))
        .when()
        .get(STORE)
        .then()
        .statusCode(401);
  }

  @Test
  void aTokenFromAnUntrustedSignerIsUnauthorized() {
    String foreign = bearer(BearerTokens.foreignToken("qits:admin"));
    given().header("Authorization", foreign).when().get(STORE).then().statusCode(401);
    assertEquals(401, StreamClient.refusal(socket, Map.of("Authorization", foreign)));
  }

  @Test
  void aBadTokenIsUnauthorizedEvenBesideForwardAuthHeaders() {
    // The token decides whenever there is one: OIDC's mechanism runs before forward-auth.
    given()
        .header("Authorization", bearer(BearerTokens.foreignToken("qits:admin")))
        .headers(SESSION)
        .when()
        .get(STORE)
        .then()
        .statusCode(401);
  }

  // --- forward-auth headers, unchanged ------------------------------------------------------------

  @Test
  void aSessionWithHeadersAndNoTokenStillReadsTheApi() {
    given().headers(SESSION).when().get(STORE).then().statusCode(200);
  }

  @Test
  void aSessionWithHeadersAndNoTokenStillOpensTheStream() throws Exception {
    try (StreamClient client = StreamClient.dial(socket, SESSION)) {
      assertTrue(client.isOpen());
    }
  }

  @Test
  void headersWithoutTheRoleAreRefusedTheStream() {
    assertEquals(
        403,
        StreamClient.refusal(socket, Map.of("X-Qits-User", "bob", "X-Qits-Roles", "qits:reader")));
  }

  // --- no credential at all -----------------------------------------------------------------------

  @Test
  void noCredentialIsUnauthorized() {
    given().when().get(STORE).then().statusCode(401);
    assertEquals(401, StreamClient.refusal(socket, Map.of()));
  }

  @Test
  void ingestStillTakesAnAnonymousExport() {
    for (String signal : new String[] {"traces", "logs", "metrics"}) {
      given()
          .contentType("application/x-protobuf")
          .body(new byte[0])
          .when()
          .post("/observability/api/otel/v1/" + signal)
          .then()
          .statusCode(200);
    }
  }
}
