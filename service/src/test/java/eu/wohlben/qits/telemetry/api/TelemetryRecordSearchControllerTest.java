package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.control.TelemetryDecoder;
import eu.wohlben.qits.telemetry.control.TelemetryStore;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code POST …/telemetry/records/search} over HTTP: the body, the answer's shape and the 400s. The
 * window, truncation, the metric rule and live parity are pinned in {@code TelemetryRecordSearchTest}
 * against a store built by hand.
 */
@QuarkusTest
class TelemetryRecordSearchControllerTest {

  private static final String REPO = "repo-search";
  private static final String WORKSPACE = "wt-search";
  private static final String URL = "/observability/api/telemetry/records/search";

  private static final String ERRORS =
      "[{\"conditions\":[{\"field\":\"severity\",\"op\":\"min\",\"value\":\"ERROR\"}]}]";

  @Inject TelemetryStore store;

  @Inject TelemetryDecoder decoder;

  @BeforeEach
  void seed() {
    store.clear();
    long now = System.currentTimeMillis();
    store.addLogs(
        decoder.decodeLogs(
            TelemetryFixtures.logsRequest(
                "svc",
                REPO,
                WORKSPACE,
                SeverityNumber.SEVERITY_NUMBER_ERROR,
                "search error log",
                TelemetryFixtures.TRACE_ID_A),
            now));
    store.addLogs(
        decoder.decodeLogs(
            TelemetryFixtures.logsRequest(
                "svc",
                REPO,
                WORKSPACE,
                SeverityNumber.SEVERITY_NUMBER_INFO,
                "search info log",
                TelemetryFixtures.TRACE_ID_A),
            now));
    store.addMetrics(
        decoder.decodeMetrics(
            TelemetryFixtures.metricsRequest("svc", REPO, WORKSPACE, 1.5, 3), now));
  }

  private static String body(String subscribe, String rest) {
    return "{\"subscribe\":" + subscribe + (rest.isEmpty() ? "" : "," + rest) + "}";
  }

  @Test
  void answersTheLiveFramesTheFilterMatches() {
    given()
        .contentType(ContentType.JSON)
        .body(
            body(
                ERRORS,
                "\"since\":\"1970-01-01T00:00:00Z\",\"until\":\"2100-01-01T00:00:00Z\","
                    + "\"limit\":10,\"source\":\""
                    + TelemetryStore.key(REPO, WORKSPACE)
                    + "\""))
        .post(URL)
        .then()
        .statusCode(200)
        .body("records", hasSize(1))
        .body("records[0].kind", equalTo("log"))
        .body("records[0].source", equalTo(TelemetryStore.key(REPO, WORKSPACE)))
        .body("records[0].receivedAtMillis", notNullValue())
        .body("records[0].record.body", equalTo("search error log"))
        .body("truncated", equalTo(false))
        .body("bufferedSince", notNullValue());
  }

  @Test
  void onlySubscribeIsRequiredAndTheLimitTruncates() {
    given()
        .contentType(ContentType.JSON)
        .body(body("[{\"conditions\":[]}]", "\"limit\":1"))
        .post(URL)
        .then()
        .statusCode(200)
        .body("records", hasSize(1))
        .body("truncated", equalTo(true));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1001})
  void aLimitOutsideTheRangeIsRefused(int limit) {
    given()
        .contentType(ContentType.JSON)
        .body(body(ERRORS, "\"limit\":" + limit))
        .post(URL)
        .then()
        .statusCode(400)
        .body("message", containsString("limit must be between 1 and 1000"));
  }

  @Test
  void anUnreadableFilterIsRefusedNamingWhatIsWrong() {
    given()
        .contentType(ContentType.JSON)
        .body(body("[{\"conditions\":[{\"field\":\"colour\",\"op\":\"exact\",\"value\":\"x\"}]}]", ""))
        .post(URL)
        .then()
        .statusCode(400)
        .body("message", containsString("unknown field 'colour'"));
  }

  @Test
  void aMissingSubscribeIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"limit\":10}")
        .post(URL)
        .then()
        .statusCode(400)
        .body("message", containsString("'subscribe' must be an array of groups"));
  }

  @Test
  void anUnreadableInstantIsRefusedNamingTheField() {
    given()
        .contentType(ContentType.JSON)
        .body(body(ERRORS, "\"since\":\"1h\""))
        .post(URL)
        .then()
        .statusCode(400)
        .body("message", containsString("since must be an ISO-8601 instant"));
  }

  @Test
  void sinceAfterUntilIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .body(
            body(
                ERRORS,
                "\"since\":\"2026-10-01T19:00:00Z\",\"until\":\"2026-10-01T18:00:00Z\""))
        .post(URL)
        .then()
        .statusCode(400)
        .body("message", containsString("since must not be after until"));
  }
}
