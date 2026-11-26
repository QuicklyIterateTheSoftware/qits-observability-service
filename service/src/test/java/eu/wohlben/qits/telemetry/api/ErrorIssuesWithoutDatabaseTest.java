package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.control.ErrorIssueAggregator;
import eu.wohlben.qits.telemetry.control.ErrorIssuePurge;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What {@code %dev} runs as: the {@code observability} datasource switched off, and with it the
 * persistence unit and Flyway. The process still boots and ingests; the aggregator holds nothing,
 * the purge does nothing, and the error-issue doors answer 503.
 */
@QuarkusTest
@TestProfile(ErrorIssuesWithoutDatabaseTest.NoDatabase.class)
@TestSecurity(user = "operator", roles = "qits:admin")
class ErrorIssuesWithoutDatabaseTest {

  /** The keys {@code %dev} sets. */
  public static class NoDatabase implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "quarkus.datasource.observability.active", "false",
          "quarkus.hibernate-orm.observability.active", "false",
          "quarkus.flyway.observability.active", "false");
    }
  }

  @Inject ErrorIssueAggregator aggregator;
  @Inject ErrorIssuePurge purge;

  @Test
  void ingestStillWorksAndTheDoorsSayThereIsNoStore() {
    given()
        .contentType("application/x-protobuf")
        .body(TelemetryFixtures.canaryLogsRequest("up", "down").toByteArray())
        .when()
        .post("/observability/api/otel/v1/logs")
        .then()
        .statusCode(200);
    given()
        .get("/observability/api/telemetry/logs?service=" + TelemetryFixtures.CANARY_SERVICE)
        .then()
        .statusCode(200)
        .body("logs", hasSize(2));

    assertEquals(0, aggregator.pendingFingerprints());
    assertEquals(0, aggregator.flush());
    assertEquals(0, purge.purge());
    given().get("/observability/api/error-issues").then().statusCode(503);
    given()
        .get("/observability/api/error-issues/0123456789abcdef0123456789abcdef")
        .then()
        .statusCode(503);
  }
}
