package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.control.ErrorIssueAggregator;
import eu.wohlben.qits.telemetry.control.ErrorIssuePurge;
import eu.wohlben.qits.telemetry.control.TelemetryStore;
import eu.wohlben.qits.telemetry.issues.ErrorIssueDelta;
import eu.wohlben.qits.telemetry.issues.ErrorIssues;
import eu.wohlben.qits.telemetry.issues.persistence.ErrorIssueStore;
import eu.wohlben.qits.telemetry.testdb.ErrorIssueTables;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The error-issue store end to end against the suite's real postgres (zonky): an ERROR log posted to
 * the OTLP receiver, the aggregator's flush, and the three doors — with the roles that may use them,
 * the regression rule, durability past the in-memory buffer, and the hourly purge.
 */
@QuarkusTest
class ErrorIssueControllerTest {

  private static final String PROTOBUF = "application/x-protobuf";
  private static final String INGEST = "/observability/api/otel/v1/logs";
  private static final String ISSUES = "/observability/api/error-issues";

  @Inject TelemetryStore store;
  @Inject ErrorIssueAggregator aggregator;
  @Inject ErrorIssueStore issues;
  @Inject ErrorIssueTables tables;
  @Inject ErrorIssuePurge purge;

  @BeforeEach
  void reset() {
    // Whatever other test classes ingested is still pending: write it, then empty the tables.
    aggregator.flush();
    tables.clear();
    store.clear();
  }

  private static void ingestCanaryError() {
    given()
        .contentType(PROTOBUF)
        .body(
            TelemetryFixtures.canaryLogsRequest("canary started", "canary failed: widget 42")
                .toByteArray())
        .when()
        .post(INGEST)
        .then()
        .statusCode(200);
  }

  /** Ingest one canary ERROR, flush, and answer the one issue's fingerprint. */
  private String ingestAndFlush() {
    ingestCanaryError();
    assertEquals(1, aggregator.flush(), "the window held one fingerprint");
    return given()
        .get(ISSUES)
        .then()
        .statusCode(200)
        .body("issues", hasSize(1))
        .extract()
        .path("issues[0].fingerprint");
  }

  private static ValidatableResponse issue(String fingerprint) {
    return given().get(ISSUES + "/" + fingerprint).then();
  }

  @Test
  @TestSecurity(user = "agent", roles = "qits:agent")
  void ingestThenFlushStoresAnIssueTheDoorsServe() {
    ingestCanaryError();
    // Nothing reaches postgres until the flush: ingest never waited on it.
    given().get(ISSUES).then().statusCode(200).body("issues", hasSize(0)).body("total", equalTo(0));
    assertEquals(1, aggregator.flush());

    String fingerprint =
        given()
            .get(ISSUES)
            .then()
            .statusCode(200)
            .body("total", equalTo(1))
            .body("truncated", equalTo(false))
            .body("issues[0].state", equalTo("OPEN"))
            .body("issues[0].serviceName", equalTo(TelemetryFixtures.CANARY_SERVICE))
            .body("issues[0].exceptionType", equalTo(TelemetryFixtures.CANARY_EXCEPTION_TYPE))
            .body("issues[0].location", notNullValue())
            .body("issues[0].message", equalTo("canary failed: widget <n>"))
            .body("issues[0].sampleMessage", equalTo("canary failed: widget 42"))
            .body("issues[0].sampleTraceId", equalTo(TelemetryFixtures.CANARY_TRACE_ID))
            .body("issues[0].firstVersion", equalTo(TelemetryFixtures.CANARY_VERSION))
            .body("issues[0].lastVersion", equalTo(TelemetryFixtures.CANARY_VERSION))
            .body("issues[0].occurrences", equalTo(1))
            .body("issues[0].regressions", equalTo(0))
            .body("issues[0].firstSeen", notNullValue())
            .extract()
            .path("issues[0].fingerprint");

    issue(fingerprint)
        .statusCode(200)
        .body("fingerprint", equalTo(fingerprint))
        .body("sampleStacktrace", equalTo(TelemetryFixtures.CANARY_STACKTRACE))
        .body("hourly", hasSize(1))
        .body("hourly[0].count", equalTo(1));

    // The stored log carries the same id, so the issue reaches its buffered lines.
    given()
        .get("/observability/api/telemetry/logs?fingerprint=" + fingerprint)
        .then()
        .statusCode(200)
        .body("logs", hasSize(1));

    // A second occurrence adds up on the same row.
    ingestCanaryError();
    aggregator.flush();
    issue(fingerprint).statusCode(200).body("occurrences", equalTo(2)).body("hourly[0].count",
        equalTo(2));
  }

  @Test
  @TestSecurity(user = "agent", roles = "qits:agent")
  void readsRefuseWhatTheyCannotAnswer() {
    issue("0123456789abcdef0123456789abcdef").statusCode(404);
    issue("not-a-fingerprint").statusCode(400);
    issue("0123456789ABCDEF0123456789ABCDEF").statusCode(400);
    given().get(ISSUES + "/0123456789abcdef0123456789abcdef?hours=0").then().statusCode(400);
    given().get(ISSUES + "/0123456789abcdef0123456789abcdef?hours=721").then().statusCode(400);
    given().get(ISSUES + "?limit=0").then().statusCode(400);
    given().get(ISSUES + "?limit=1001").then().statusCode(400);
    given().get(ISSUES + "?state=CLOSED").then().statusCode(400);
    given().get(ISSUES + "?state=ALL").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "agent", roles = "qits:agent")
  void aCommissionedAgentCannotDismiss() {
    String fingerprint = ingestAndFlush();
    given().post(ISSUES + "/" + fingerprint + "/dismiss").then().statusCode(403);
    issue(fingerprint).statusCode(200).body("state", equalTo("OPEN"));
  }

  @Test
  @TestSecurity(user = "alice", roles = "qits:admin")
  void aPersonDismissesAndARecurrenceReopensAsARegression() {
    String fingerprint = ingestAndFlush();

    String dismissedAt =
        given()
            .post(ISSUES + "/" + fingerprint + "/dismiss")
            .then()
            .statusCode(200)
            .body("state", equalTo("DISMISSED"))
            .body("dismissedBy", equalTo("alice"))
            .body("dismissedAt", notNullValue())
            .extract()
            .path("dismissedAt");
    // Idempotent: a second dismiss changes nothing.
    given()
        .post(ISSUES + "/" + fingerprint + "/dismiss")
        .then()
        .statusCode(200)
        .body("dismissedAt", equalTo(dismissedAt));
    given().get(ISSUES).then().body("issues", hasSize(0));
    given().get(ISSUES + "?state=DISMISSED").then().body("issues", hasSize(1));
    given().post(ISSUES + "/0123456789abcdef0123456789abcdef/dismiss").then().statusCode(404);

    ingestCanaryError();
    aggregator.flush();
    issue(fingerprint)
        .statusCode(200)
        .body("state", equalTo("OPEN"))
        .body("regressions", equalTo(1))
        .body("regressedAt", notNullValue())
        .body("dismissedAt", equalTo(dismissedAt))
        .body("dismissedBy", equalTo("alice"))
        .body("occurrences", equalTo(2));
  }

  @Test
  @TestSecurity(user = "desk", roles = "qits:desk-admin")
  void theDeskMayDismiss() {
    String fingerprint = ingestAndFlush();
    given().post(ISSUES + "/" + fingerprint + "/dismiss").then().statusCode(200);
  }

  @Test
  @TestSecurity(user = "agent", roles = "qits:agent")
  void anIssueLivesInPostgresNotInTheBuffer() {
    String fingerprint = ingestAndFlush();
    store.clear();
    assertEquals(0, aggregator.pendingFingerprints());

    assertEquals(1L, tables.occurrences(fingerprint), "read back by SQL, past the store");
    assertEquals(1L, tables.hourRows(fingerprint));
    issue(fingerprint).statusCode(200).body("occurrences", equalTo(1));
  }

  @Test
  @TestSecurity(user = "agent", roles = "qits:agent")
  void thePurgeDropsOldHoursAndKeepsTheIssue() {
    Instant now = Instant.now();
    Instant old = ErrorIssues.hourOf(now.minus(Duration.ofDays(31)));
    Instant recent = ErrorIssues.hourOf(now);
    String fingerprint = "abcdefabcdefabcdefabcdefabcdef00";
    issues.apply(
        List.of(
            new ErrorIssueDelta(
                fingerprint,
                "qits-purged",
                null,
                null,
                "old and new",
                "old and new",
                null,
                null,
                old,
                recent,
                null,
                null,
                5,
                new TreeMap<>(Map.of(old, 4L, recent, 1L)))),
        now);
    assertEquals(2L, tables.hourRows(fingerprint));

    assertTrue(purge.purge() >= 1);

    assertEquals(1L, tables.hourRows(fingerprint), "the hour inside the retention stays");
    issue(fingerprint)
        .statusCode(200)
        .body("occurrences", equalTo(5))
        .body("hourly", hasSize(1))
        .body("regressedAt", nullValue());
  }
}
