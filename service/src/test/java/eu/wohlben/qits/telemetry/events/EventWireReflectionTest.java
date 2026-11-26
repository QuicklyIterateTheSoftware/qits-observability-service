package eu.wohlben.qits.telemetry.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Guards {@link EventWireReflection}'s <em>completeness</em>, the part a JVM test can reach. On a
 * JVM every record reflects whether or not anyone registered it, so nothing here proves the native
 * binary can serialise an event — only the binary running does, and the library's {@code
 * eventstream-publish} readiness check self-tests that path at boot. What is checkable is that the
 * registration names exactly this service's event records, that it leaves the library's own wire
 * types to the library, and that each record crosses the wire with the components the dossier page
 * "Events on qits-events" names and nothing else. Plain JUnit: no Quarkus boot.
 */
class EventWireReflectionTest {

  private static final Instant AT = Instant.parse("2026-11-26T10:15:30.123456Z");

  private static Set<Class<?>> targets() {
    RegisterForReflection registration =
        EventWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class; without it this file is a no-op");
    return Set.of(registration.targets());
  }

  @Test
  void theRegisteredTargetsAreExactlyTheEventsThisServicePublishes() {
    assertEquals(
        Set.of(ErrorIssueOpened.class, ErrorIssueRegressed.class),
        targets(),
        "a third event type is added here and in EventWireReflection in the same commit");
    for (Class<?> target : targets()) {
      assertTrue(QitsEvent.class.isAssignableFrom(target), target + " is a QitsEvent");
      assertTrue(target.isRecord(), target + " is a record");
    }
  }

  @Test
  void theLibrarysOwnWireTypesAreLeftToTheEventstreamExtension() {
    RegisterForReflection registration =
        EventWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertEquals(0, registration.classNames().length);
    assertFalse(targets().contains(EventEnvelope.class));
    assertFalse(targets().contains(EventFrame.class));
  }

  /** {@code name} and {@code signature} are QitsEvent's own accessors, never a component. */
  @Test
  void noRecordComponentShadowsTheEnvelope() {
    for (Class<?> target : targets()) {
      for (RecordComponent component : target.getRecordComponents()) {
        assertFalse(
            Set.of("name", "signature").contains(component.getName()),
            target.getSimpleName() + "." + component.getName());
      }
    }
  }

  /**
   * The payload carries every component but the two the envelope owns — {@code eventId} and {@code
   * occurredAt} travel beside it, hidden by the library's mix-in — and the canonical form drops a
   * null, so the field sets are asserted on events with every component set.
   */
  @Test
  void openedCrossesTheWireWithExactlyItsFields() {
    ErrorIssueOpened event =
        new ErrorIssueOpened(
            null,
            AT,
            "0123456789abcdef0123456789abcdef",
            "qits-canary",
            "2026.1126.1",
            "java.lang.IllegalStateException",
            "eu.wohlben.Canary.run",
            "canary failed: widget <n>",
            "4bf92f3577b34da6a3ce929d0e0e4736");
    assertNotNull(event.eventId(), "a null id is minted, as every estate event does");

    JsonNode payload = roundTrip(event);
    assertEquals(
        fields(
            "fingerprint",
            "serviceName",
            "serviceVersion",
            "exceptionType",
            "location",
            "message",
            "traceId"),
        names(payload));

    ErrorIssueOpened back = CanonicalJson.payloadTo(payload.toString(), ErrorIssueOpened.class);
    assertEquals(event.fingerprint(), back.fingerprint());
    assertEquals(event.serviceVersion(), back.serviceVersion());
    assertEquals(event.message(), back.message());
    assertEquals(event.traceId(), back.traceId());
  }

  @Test
  void aMissingTraceIdIsLeftOutRatherThanSent() {
    ErrorIssueOpened event =
        new ErrorIssueOpened(null, AT, "f", "qits-canary", null, null, null, "boom", null);
    JsonNode payload = roundTrip(event);
    assertFalse(payload.has("traceId"));
    assertTrue(payload.has("fingerprint"));
  }

  @Test
  void regressedCrossesTheWireWithExactlyItsFields() {
    ErrorIssueRegressed event =
        new ErrorIssueRegressed(
            null,
            AT,
            "0123456789abcdef0123456789abcdef",
            "qits-canary",
            "2026.1126.2",
            "java.lang.IllegalStateException",
            "eu.wohlben.Canary.run",
            "canary failed",
            "4bf92f3577b34da6a3ce929d0e0e4736",
            AT.minusSeconds(3600),
            2,
            17L);

    JsonNode payload = roundTrip(event);
    assertEquals(
        fields(
            "fingerprint",
            "serviceName",
            "serviceVersion",
            "exceptionType",
            "location",
            "message",
            "traceId",
            "dismissedAt",
            "regressions",
            "occurrences"),
        names(payload));
    assertEquals(2, payload.get("regressions").asInt());
    assertEquals(17L, payload.get("occurrences").asLong());

    ErrorIssueRegressed back =
        CanonicalJson.payloadTo(payload.toString(), ErrorIssueRegressed.class);
    assertEquals(event.dismissedAt(), back.dismissedAt());
    assertEquals(event.regressions(), back.regressions());
    assertEquals(event.occurrences(), back.occurrences());
  }

  /** The envelope as the PUT sends it: its name and time, and its payload parsed back. */
  private static JsonNode roundTrip(QitsEvent event) {
    EventEnvelope envelope = EventEnvelope.of(event);
    JsonNode body = CanonicalJson.parse(CanonicalJson.envelope(envelope));
    assertEquals(event.getClass().getSimpleName(), body.get("name").asText());
    assertEquals(AT, Instant.parse(body.get("occurredAt").asText()));
    assertTrue(body.get("payload").isTextual(), "the payload is a string the server stores");
    return CanonicalJson.parse(body.get("payload").asText());
  }

  private static Set<String> names(JsonNode payload) {
    Set<String> names = new TreeSet<>();
    for (Iterator<String> it = payload.fieldNames(); it.hasNext(); ) {
      names.add(it.next());
    }
    return names;
  }

  private static Set<String> fields(String... names) {
    return Arrays.stream(names).collect(Collectors.toCollection(TreeSet::new));
  }
}
