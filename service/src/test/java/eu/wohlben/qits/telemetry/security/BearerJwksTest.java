package eu.wohlben.qits.telemetry.security;

import static eu.wohlben.qits.telemetry.security.BearerTokens.bearer;
import static eu.wohlben.qits.telemetry.security.BearerTokens.cliToken;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The shipped key path, end to end, with {@link JwksStub} in place of qits-platform-idp: discovery
 * off, {@code jwks-path=jwks}, the shipped issuer and audience, and {@code
 * jwks.resolve-early=false}.
 *
 * <p>What it pins: nothing but a bearer ever reaches the idp. Boot, header traffic, ingest and 401
 * challenges make no call. The first bearer fetches the key by its {@code kid}; later bearers use
 * the cache.
 *
 * <p>The order is load-bearing: the first test can only prove "no fetch yet" before any bearer.
 */
@QuarkusTest
@WithTestResource(JwksStub.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BearerJwksTest {

  private static final String STORE = "/observability/api/telemetry/store";

  @Test
  @Order(1)
  void bootHeaderTrafficIngestAndRefusalsNeverCallTheIdp() {
    given()
        .header("X-Qits-User", "alice")
        .header("X-Qits-Roles", "qits:admin")
        .when()
        .get(STORE)
        .then()
        .statusCode(200);
    given()
        .contentType("application/x-protobuf")
        .body(new byte[0])
        .when()
        .post("/observability/api/otel/v1/logs")
        .then()
        .statusCode(200);
    given().when().get(STORE).then().statusCode(401);
    assertEquals(0, JwksStub.fetches(), "only a bearer may reach the idp");
  }

  @Test
  @Order(2)
  void theFirstBearerFetchesTheKeyByItsKidAndIsAccepted() {
    given()
        .header("Authorization", bearer(cliToken("qits:admin")))
        .when()
        .get(STORE)
        .then()
        .statusCode(200);
    assertEquals(1, JwksStub.fetches());
  }

  @Test
  @Order(3)
  void laterBearersUseTheCachedKey() {
    for (int i = 0; i < 3; i++) {
      given()
          .header("Authorization", bearer(cliToken("qits:admin")))
          .when()
          .get(STORE)
          .then()
          .statusCode(200);
    }
    assertEquals(1, JwksStub.fetches());
  }

  @Test
  @Order(4)
  void aTokenFromAnotherIssuerIsUnauthorized() {
    // Correctly signed with the known key; only `iss` is wrong.
    given()
        .header(
            "Authorization",
            bearer(BearerTokens.tokenFrom("http://elsewhere:8080/idp", "qits:admin")))
        .when()
        .get(STORE)
        .then()
        .statusCode(401);
  }
}
