package eu.wohlben.qits.telemetry.control;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.entity.OutboxEvent;
import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.issues.ErrorIssueDelta;
import eu.wohlben.qits.telemetry.issues.persistence.ErrorIssueStore;
import eu.wohlben.qits.telemetry.testdb.ErrorIssueTables;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code ErrorIssueOpened} / {@code ErrorIssueRegressed} end to end (qits-2684): a real flush
 * against the suite's postgres, ending as rows in the real eventstream outbox.
 *
 * <p><b>The bus is aimed at a CLOSED PORT, and that is what makes this assertable</b> — the
 * qits-deployments {@code PdDeployPublishTest} way. {@code QitsEventBus.publish} attempts the PUT
 * inline and hands whatever does not land to the outbox, so against a port nothing answers (and with
 * the {@code qits} client off, as {@code %test} has it) every published event is exactly one row,
 * with the canonical payload it would have been sent with. A row IS the publish, from this side.
 * The scheduler is off, so no sweeper moves a row under an assertion.
 *
 * <p>A class of its own because a bus that is on is a different process configuration, and
 * {@code @TestProfile} is per class.
 */
@QuarkusTest
@TestProfile(ErrorIssueAnnouncerTest.BusEnabled.class)
class ErrorIssueAnnouncerTest {

  public static class BusEnabled implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.eventstream.enabled", "true",
          // Nothing answers here: a refused connection is immediate, so the event lands in the
          // outbox rather than on a socket.
          "qits.events.url", "http://localhost:1",
          "quarkus.scheduler.enabled", "false",
          "qits.eventstream.catchup-at-startup", "false");
    }
  }

  private static final String OPENED = "ErrorIssueOpened";
  private static final String REGRESSED = "ErrorIssueRegressed";

  @Inject ErrorIssueFlushTransaction flush;
  @Inject ErrorIssueAggregator aggregator;
  @Inject ErrorIssueStore issues;
  @Inject ErrorIssueTables tables;
  @Inject TelemetryStore store;

  /** Read through its own persistence unit: the entity comes from the qits-eventstream jar. */
  @Inject
  @PersistenceUnit("eventstream")
  EntityManager outbox;

  @BeforeEach
  void reset() {
    aggregator.flush();
    tables.clear();
    store.clear();
    clearOutbox();
  }

  private void clearOutbox() {
    QuarkusTransaction.requiringNew()
        .run(() -> outbox.createQuery("delete from OutboxEvent").executeUpdate());
  }

  private List<OutboxEvent> rows() {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                outbox
                    .createQuery(
                        "from OutboxEvent e where e.name in (:names) order by e.createdAt, e.id",
                        OutboxEvent.class)
                    .setParameter("names", List.of(OPENED, REGRESSED))
                    .getResultList());
  }

  private static JsonNode payload(OutboxEvent row) {
    return CanonicalJson.parse(row.payload);
  }

  private static final Instant T0 = Instant.parse("2026-11-26T09:00:00.123456Z");

  private static ErrorIssueDelta delta(
      String fingerprint, Instant at, String version, String traceId, long occurrences) {
    Instant hour = at.truncatedTo(ChronoUnit.HOURS);
    return new ErrorIssueDelta(
        fingerprint,
        "qits-announced",
        "java.lang.IllegalStateException",
        "eu.wohlben.Announced.run",
        "announced failed: widget <n>",
        "announced failed: widget 42",
        "java.lang.IllegalStateException: announced failed\n\tat eu.wohlben.Announced.run",
        traceId,
        at,
        at.plusSeconds(1),
        version,
        version,
        occurrences,
        new TreeMap<>(Map.of(hour, occurrences)));
  }

  @Test
  void anInsertedIssueIsAnnouncedOpenedOnce() {
    String fingerprint = "a0000000000000000000000000000001";
    flush.write(List.of(delta(fingerprint, T0, "2026.1126.1", "4bf92f3577b34da6a3ce929d0e0e4736", 3)));

    List<OutboxEvent> rows = rows();
    assertEquals(1, rows.size(), "one fingerprint inserted, one event");
    OutboxEvent row = rows.getFirst();
    assertEquals(OPENED, row.name);
    assertEquals(T0, row.occurredAt, "first_seen");
    JsonNode payload = payload(row);
    assertEquals(fingerprint, payload.get("fingerprint").asText());
    assertEquals("qits-announced", payload.get("serviceName").asText());
    assertEquals("2026.1126.1", payload.get("serviceVersion").asText());
    assertEquals("java.lang.IllegalStateException", payload.get("exceptionType").asText());
    assertEquals("eu.wohlben.Announced.run", payload.get("location").asText());
    assertEquals("announced failed: widget <n>", payload.get("message").asText());
    assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", payload.get("traceId").asText());
    assertFalse(row.payload.contains("\tat "), "no stack trace on the wire");
  }

  @Test
  void aCountIncreaseOnAnOpenIssuePublishesNothing() {
    String fingerprint = "a0000000000000000000000000000002";
    flush.write(List.of(delta(fingerprint, T0, "2026.1126.1", null, 1)));
    clearOutbox();

    flush.write(List.of(delta(fingerprint, T0.plusSeconds(60), "2026.1126.1", null, 4)));

    assertEquals(5L, tables.occurrences(fingerprint), "the row moved");
    assertTrue(rows().isEmpty(), "and nothing was announced");
  }

  @Test
  void aDismissedIssueSeenAgainIsAnnouncedRegressedOnce() {
    String fingerprint = "a0000000000000000000000000000003";
    flush.write(List.of(delta(fingerprint, T0, "2026.1126.1", null, 2)));
    Instant dismissedAt = issues.dismiss(fingerprint, "operator", T0.plusSeconds(30)).orElseThrow()
        .dismissedAt();
    clearOutbox();

    Instant again = T0.plusSeconds(120);
    flush.write(List.of(delta(fingerprint, again, "2026.1126.2", null, 1)));

    List<OutboxEvent> rows = rows();
    assertEquals(1, rows.size(), "one regression, one event");
    OutboxEvent row = rows.getFirst();
    assertEquals(REGRESSED, row.name);
    assertEquals(again, row.occurredAt, "the regressing occurrence's time");
    JsonNode payload = payload(row);
    assertEquals(fingerprint, payload.get("fingerprint").asText());
    assertEquals("2026.1126.2", payload.get("serviceVersion").asText());
    assertEquals(dismissedAt, Instant.parse(payload.get("dismissedAt").asText()));
    assertEquals(1, payload.get("regressions").asInt());
    assertEquals(3L, payload.get("occurrences").asLong());
    assertFalse(payload.has("traceId"), "the occurrence carried none");

    // Seen again while OPEN: a count increase, nothing more.
    clearOutbox();
    flush.write(List.of(delta(fingerprint, again.plusSeconds(60), "2026.1126.2", null, 1)));
    assertTrue(rows().isEmpty());
  }

  @Test
  void aRolledBackFlushLeavesNeitherTheRowNorTheEvent() {
    String fingerprint = "a0000000000000000000000000000004";
    assertThrows(
        QuarkusTransactionException.class,
        () ->
            QuarkusTransaction.requiringNew()
                .run(
                    () -> {
                      flush.write(List.of(delta(fingerprint, T0, "2026.1126.1", null, 1)));
                      QuarkusTransaction.setRollbackOnly();
                    }),
        "the commit refused: the transaction was marked rollback-only after the flush wrote");

    assertNull(tables.occurrences(fingerprint), "no issue row");
    assertTrue(rows().isEmpty(), "and no event");
  }

  /** The real path: OTLP in, the aggregator's flush, one event per fingerprint per flush. */
  @Test
  void oneFlushAnnouncesEachFingerprintOnce() {
    for (int i = 0; i < 3; i++) {
      given()
          .contentType("application/x-protobuf")
          .body(
              TelemetryFixtures.canaryLogsRequest("canary started", "canary failed: widget " + i)
                  .toByteArray())
          .when()
          .post("/observability/api/otel/v1/logs")
          .then()
          .statusCode(200);
    }
    assertEquals(1, aggregator.flush(), "three occurrences, one fingerprint");

    List<OutboxEvent> rows = rows();
    assertEquals(1, rows.size());
    assertEquals(OPENED, rows.getFirst().name);
    JsonNode payload = payload(rows.getFirst());
    assertEquals(TelemetryFixtures.CANARY_SERVICE, payload.get("serviceName").asText());
    assertEquals(TelemetryFixtures.CANARY_VERSION, payload.get("serviceVersion").asText());
    assertEquals(TelemetryFixtures.CANARY_TRACE_ID, payload.get("traceId").asText());
    assertEquals("canary failed: widget <n>", payload.get("message").asText());
  }
}
