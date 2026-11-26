package eu.wohlben.qits.telemetry.contracts;

import eu.wohlben.qits.eventstream.contracts.EventstreamPact;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>What qits-eventstream asks qits-events on this service's behalf</b> (qits-1163, qits-2684):
 * the outbox's publish of {@code ErrorIssueOpened} / {@code ErrorIssueRegressed}, from {@code
 * control/ErrorIssueAnnouncer}. This service has no listener, so there are no catch-up reads. The
 * rows come from the pinned library's kit and are committed as {@code
 * pacts/qits-observability-service_qits-eventstream-javalib_qits-events-service.json}; the guard
 * fails naming any listener or {@code QitsEventBus} user in {@code target/classes} this class does
 * not account for. {@code domain/} holds neither, and does not have the jar on its classpath.
 */
class EventstreamPactTest {

  static final EventstreamPact PACT =
      EventstreamPact.forConsumer("qits-observability-service")
          .publishes(true)
          .guard(Path.of("target/classes"));

  @TestFactory
  Stream<DynamicTest> rows() {
    return PACT.tests();
  }

  @Test
  void pactFile() {
    PACT.compareOrWritePactFile();
  }
}
