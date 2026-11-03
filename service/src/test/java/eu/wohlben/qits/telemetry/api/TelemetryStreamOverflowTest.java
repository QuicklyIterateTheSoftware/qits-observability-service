package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.control.TelemetryLiveFeed;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Back-pressure: a reader that cannot keep up loses records, and is told how many. The queue is
 * shrunk to two frames so one ingest batch overruns it for certain.
 */
@QuarkusTest
@TestProfile(TelemetryStreamOverflowTest.SmallQueue.class)
@TestSecurity(user = "operator", roles = "qits:admin")
class TelemetryStreamOverflowTest {

  public static class SmallQueue implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.telemetry.stream.queue-size", "2");
    }
  }

  private static final Duration SOON = Duration.ofSeconds(10);
  private static final int BATCH = 500;

  @TestHTTPResource("/observability/stream")
  URI endpoint;

  @Inject TelemetryLiveFeed feed;

  private static byte[] flood(String service, int count, String prefix) {
    ScopeLogs.Builder scope = ScopeLogs.newBuilder();
    for (int i = 0; i < count; i++) {
      scope.addLogRecords(
          LogRecord.newBuilder()
              .setTimeUnixNano(1_000_000_000L + i)
              .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
              .setBody(AnyValue.newBuilder().setStringValue(prefix + i)));
    }
    return ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(
            ResourceLogs.newBuilder()
                .setResource(TelemetryFixtures.resource(service, null, null))
                .addScopeLogs(scope))
        .build()
        .toByteArray();
  }

  private static void ingestLogs(byte[] body) {
    given()
        .contentType(OtelReceiverResource.PROTOBUF)
        .body(body)
        .when()
        .post("/observability/api/otel/v1/logs")
        .then()
        .statusCode(200);
  }

  @Test
  void aSlowReaderIsToldHowManyRecordsItMissed() throws Exception {
    String service = "svc-flood-" + System.nanoTime();
    try (StreamClient client = StreamClient.dial(endpoint)) {
      long before = feed.subscribeFrames();
      client.subscribe(
          "[{\"conditions\":[{\"field\":\"service\",\"op\":\"exact\",\"value\":\"" + service + "\"}]}]");
      long deadline = System.nanoTime() + SOON.toNanos();
      while (feed.subscribeFrames() <= before) {
        assertTrue(System.nanoTime() < deadline, "the server never handled the subscribe frame");
        Thread.sleep(10);
      }

      ingestLogs(flood(service, BATCH, "line "));

      // A notice follows each time the queue runs empty, so one flood can bring several. Every
      // record is either sent or counted in one of them.
      int records = 0;
      long dropped = 0;
      int notices = 0;
      while (records + dropped < BATCH) {
        JsonNode frame = client.nextJson(SOON);
        assertNotNull(
            frame,
            "the stream went quiet with " + records + " sent and " + dropped + " counted as dropped");
        if (frame.has("dropped")) {
          assertTrue(frame.get("dropped").asLong() > 0, frame.toString());
          dropped += frame.get("dropped").asLong();
          notices++;
        } else {
          assertEquals("log", frame.get("kind").asText());
          records++;
        }
      }
      assertEquals(BATCH, records + dropped, "every record is either sent or counted");
      assertTrue(notices > 0 && dropped > 0, "the queue of 2 held a batch of " + BATCH);

      // The connection recovers: the next record arrives normally.
      ingestLogs(flood(service, 1, "after "));
      JsonNode after = client.nextJson(SOON);
      assertNotNull(after);
      assertEquals("after 0", after.get("record").get("body").asText());
    }
  }
}
