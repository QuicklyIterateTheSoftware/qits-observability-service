package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.control.TelemetryLiveFeed;
import eu.wohlben.qits.telemetry.control.TelemetryStore;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The live stream end to end: a real WebSocket at {@code /observability/stream}, real OTLP protobuf
 * posted to the ingest route, frames read back off the socket. The test identity carries {@code
 * qits:admin} ({@code @TestSecurity}), so the upgrade is allowed; {@code security/BearerAuthTest}
 * covers the door.
 *
 * <p>Where a test says "only this arrives", it posts the unwanted records first and the wanted one
 * last. One dispatcher thread and one queue per connection keep the order, so the first frame must
 * be the wanted one. That is a proof, where a short wait for silence is only a hint.
 */
@QuarkusTest
@TestSecurity(user = "operator", roles = "qits:admin")
class TelemetryStreamSocketTest {

  private static final Duration SOON = Duration.ofSeconds(10);
  private static final Duration BRIEFLY = Duration.ofMillis(500);

  @TestHTTPResource("/observability/stream")
  URI endpoint;

  @Inject TelemetryLiveFeed feed;

  @Inject TelemetryStore store;

  @BeforeEach
  void reset() {
    store.clear();
  }

  // --- helpers --------------------------------------------------------------------------------

  private static String unique(String label) {
    return "svc-" + label + "-" + System.nanoTime();
  }

  private static String cond(String field, String op, String value) {
    return "{\"field\":\"" + field + "\",\"op\":\"" + op + "\",\"value\":\"" + value + "\"}";
  }

  private static String groups(String... groups) {
    return "[" + String.join(",", groups) + "]";
  }

  private static String group(String... conditions) {
    return "{\"conditions\":[" + String.join(",", conditions) + "]}";
  }

  /** Send a subscribe frame and wait until the server has handled it. */
  private void subscribe(StreamClient client, String groups) throws Exception {
    long before = feed.subscribeFrames();
    client.subscribe(groups);
    long deadline = System.nanoTime() + SOON.toNanos();
    while (feed.subscribeFrames() <= before) {
      assertTrue(System.nanoTime() < deadline, "the server never handled the subscribe frame");
      Thread.sleep(10);
    }
  }

  private static void ingest(String signal, byte[] body) {
    given()
        .contentType(OtelReceiverResource.PROTOBUF)
        .body(body)
        .when()
        .post("/observability/api/otel/v1/" + signal)
        .then()
        .statusCode(200);
  }

  private static void log(String service, SeverityNumber severity, String body) {
    ingest(
        "logs",
        TelemetryFixtures.logsRequest(
                service, null, null, severity, body, TelemetryFixtures.TRACE_ID_A)
            .toByteArray());
  }

  private static JsonNode next(StreamClient client) throws Exception {
    JsonNode frame = client.nextJson(SOON);
    assertNotNull(frame, "no frame arrived");
    return frame;
  }

  // --- tests ----------------------------------------------------------------------------------

  @Test
  void onlyTheMatchingRecordArrivesInTheEnvelope() throws Exception {
    String wanted = unique("wanted");
    String other = unique("other");
    try (StreamClient client = StreamClient.dial(endpoint)) {
      subscribe(
          client,
          groups(group(cond("service", "exact", wanted), cond("kind", "exact", "log"))));

      log(other, SeverityNumber.SEVERITY_NUMBER_ERROR, "another service");
      ingest(
          "traces",
          TelemetryFixtures.okTraceRequest(
                  wanted, null, null, TelemetryFixtures.TRACE_ID_A, TelemetryFixtures.SPAN_ID_A)
              .toByteArray());
      log(wanted, SeverityNumber.SEVERITY_NUMBER_ERROR, "the one that matches");

      JsonNode frame = next(client);
      List<String> fields = new ArrayList<>();
      frame.fieldNames().forEachRemaining(fields::add);
      assertEquals(List.of("kind", "receivedAtMillis", "source", "record"), fields);
      assertEquals("log", frame.get("kind").asText());
      assertTrue(frame.get("receivedAtMillis").asLong() > 0);
      assertEquals(TelemetryStore.SERVICE_KEY_PREFIX + wanted, frame.get("source").asText());

      JsonNode record = frame.get("record");
      assertEquals("the one that matches", record.get("body").asText());
      assertEquals(17, record.get("severityNumber").asInt());
      assertEquals(wanted, record.get("serviceName").asText());
      assertEquals(TelemetryFixtures.TRACE_ID_A, record.get("traceId").asText());
      assertEquals(wanted, record.get("resourceAttributes").get("service.name").asText());

      assertNull(client.next(BRIEFLY), "nothing else matched");
    }
  }

  @Test
  void aSpanFrameCarriesTheSpanDtoWithItsEvents() throws Exception {
    String service = unique("spans");
    try (StreamClient client = StreamClient.dial(endpoint)) {
      subscribe(
          client, groups(group(cond("service", "exact", service), cond("event", "exact", "exception"))));

      ingest(
          "traces",
          TelemetryFixtures.okTraceRequest(
                  service, null, null, TelemetryFixtures.TRACE_ID_B, TelemetryFixtures.SPAN_ID_B)
              .toByteArray());
      ingest(
          "traces",
          TelemetryFixtures.errorTraceRequest(
                  service, null, null, TelemetryFixtures.TRACE_ID_A, TelemetryFixtures.SPAN_ID_A)
              .toByteArray());

      JsonNode frame = next(client);
      assertEquals("span", frame.get("kind").asText());
      JsonNode record = frame.get("record");
      assertEquals(TelemetryFixtures.TRACE_ID_A, record.get("traceId").asText());
      assertEquals("ERROR", record.get("status").asText());
      assertEquals(250, record.get("durationMs").asLong());
      assertEquals("exception", record.get("events").get(0).get("name").asText());
      assertEquals(
          "java.lang.IllegalStateException",
          record.get("events").get(0).get("attributes").get("exception.type").asText());
    }
  }

  @Test
  void aMetricFrameCarriesTheMetricDto() throws Exception {
    String service = unique("metrics");
    try (StreamClient client = StreamClient.dial(endpoint)) {
      subscribe(
          client,
          groups(
              group(
                  cond("kind", "exact", "metric"),
                  cond("service", "exact", service),
                  cond("name", "exact", "jvm.memory.used"))));

      ingest(
          "metrics",
          TelemetryFixtures.metricsRequest(service, null, null, 123.5, 7).toByteArray());

      JsonNode frame = next(client);
      assertEquals("metric", frame.get("kind").asText());
      assertEquals("jvm.memory.used", frame.get("record").get("name").asText());
      assertEquals("GAUGE", frame.get("record").get("type").asText());
      assertEquals(123.5, frame.get("record").get("value").asDouble());
      assertNull(client.next(BRIEFLY), "the counter in the same batch did not match");
    }
  }

  @Test
  void aWorkspaceRecordNamesItsWorkspaceSource() throws Exception {
    String service = unique("workspace");
    try (StreamClient client = StreamClient.dial(endpoint)) {
      subscribe(client, groups(group(cond("service", "exact", service))));
      ingest(
          "logs",
          TelemetryFixtures.logsRequest(
                  service, "repo-7", "ws-9", SeverityNumber.SEVERITY_NUMBER_INFO, "hi", null)
              .toByteArray());
      assertEquals(TelemetryStore.key("repo-7", "ws-9"), next(client).get("source").asText());
    }
  }

  @Test
  void groupsAreOred() throws Exception {
    String one = unique("one");
    String two = unique("two");
    try (StreamClient client = StreamClient.dial(endpoint)) {
      subscribe(
          client,
          groups(group(cond("service", "exact", one)), group(cond("service", "exact", two))));
      log(one, SeverityNumber.SEVERITY_NUMBER_INFO, "first");
      log(two, SeverityNumber.SEVERITY_NUMBER_INFO, "second");
      assertEquals("first", next(client).get("record").get("body").asText());
      assertEquals("second", next(client).get("record").get("body").asText());
    }
  }

  @Test
  void aSubscribeFrameReplacesTheFilters() throws Exception {
    String before = unique("before");
    String after = unique("after");
    try (StreamClient client = StreamClient.dial(endpoint)) {
      subscribe(client, groups(group(cond("service", "exact", before))));
      subscribe(client, groups(group(cond("service", "exact", after))));

      log(before, SeverityNumber.SEVERITY_NUMBER_INFO, "no longer wanted");
      log(after, SeverityNumber.SEVERITY_NUMBER_INFO, "wanted now");
      assertEquals("wanted now", next(client).get("record").get("body").asText());
    }
  }

  @Test
  void aMalformedFrameIsAnsweredAndTheOldFiltersStay() throws Exception {
    String kept = unique("kept");
    String other = unique("other");
    try (StreamClient client = StreamClient.dial(endpoint)) {
      subscribe(client, groups(group(cond("service", "exact", kept))));

      client.send("{\"subscribe\":[{\"conditions\":[{\"field\":\"level\",\"op\":\"exact\",\"value\":\"x\"}]}]}");
      JsonNode unknownField = next(client);
      assertTrue(
          unknownField.get("error").asText().contains("unknown field 'level'"),
          unknownField.toString());

      client.send("not json at all");
      assertTrue(next(client).get("error").asText().startsWith("not JSON"));

      client.send("{\"subscribe\":\"everything\"}");
      assertTrue(next(client).has("error"));

      log(other, SeverityNumber.SEVERITY_NUMBER_INFO, "not asked for");
      log(kept, SeverityNumber.SEVERITY_NUMBER_INFO, "still asked for");
      JsonNode frame = next(client);
      assertEquals("still asked for", frame.get("record").get("body").asText());
      assertTrue(client.isOpen(), "a bad frame costs the frame, not the connection");
    }
  }

  @Test
  void aNewConnectionIsSubscribedToNothing() throws Exception {
    String service = unique("silent");
    try (StreamClient client = StreamClient.dial(endpoint)) {
      log(service, SeverityNumber.SEVERITY_NUMBER_ERROR, "nobody asked");
      assertNull(client.next(BRIEFLY));
    }
  }

  @Test
  void anEmptySubscribeMeansNothing() throws Exception {
    String service = unique("emptied");
    try (StreamClient client = StreamClient.dial(endpoint)) {
      subscribe(client, groups(group(cond("service", "exact", service))));
      subscribe(client, "[]");
      log(service, SeverityNumber.SEVERITY_NUMBER_ERROR, "no longer asked");
      assertNull(client.next(BRIEFLY));
    }
  }

  @Test
  void anEmptyGroupMeansEverything() throws Exception {
    String service = unique("everything");
    try (StreamClient client = StreamClient.dial(endpoint)) {
      subscribe(client, groups(group()));
      log(service, SeverityNumber.SEVERITY_NUMBER_DEBUG, "anything at all");
      JsonNode frame = next(client);
      assertEquals(service, frame.get("record").get("serviceName").asText());
    }
  }

  @Test
  void twoConnectionsEachGetOnlyWhatTheyAskedFor() throws Exception {
    String mine = unique("mine");
    String yours = unique("yours");
    try (StreamClient one = StreamClient.dial(endpoint);
        StreamClient two = StreamClient.dial(endpoint)) {
      subscribe(one, groups(group(cond("service", "exact", mine))));
      subscribe(two, groups(group(cond("service", "exact", yours))));
      log(mine, SeverityNumber.SEVERITY_NUMBER_INFO, "for one");
      log(yours, SeverityNumber.SEVERITY_NUMBER_INFO, "for two");
      assertEquals("for one", next(one).get("record").get("body").asText());
      assertEquals("for two", next(two).get("record").get("body").asText());
      assertNull(one.next(BRIEFLY));
    }
  }

  @Test
  void ingestIsUnaffectedByAClosedReader() throws Exception {
    String service = unique("gone");
    StreamClient client = StreamClient.dial(endpoint);
    subscribe(client, groups(group(cond("service", "exact", service))));
    client.close();
    log(service, SeverityNumber.SEVERITY_NUMBER_ERROR, "nobody is left to read this");
    assertFalse(store.logsIn(TelemetryStore.SERVICE_KEY_PREFIX + service).isEmpty());
  }
}
