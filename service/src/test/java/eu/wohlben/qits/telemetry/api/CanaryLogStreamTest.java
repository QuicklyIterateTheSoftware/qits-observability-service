package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.control.TelemetryStore;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.zip.GZIPOutputStream;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The consumer surface, end to end, for one realistic canary batch — the log-streaming plan's LB
 * workstream.
 *
 * <p>Everything here goes in as OTLP protobuf bytes over HTTP and comes back out through the routes
 * the SPA and the qits CLI read. Nothing hand-assembles store state, and nothing calls the decoder
 * directly: those are covered a layer down ({@code TelemetryDecoderTest}, {@code
 * OtelReceiverResourceTest}), and what is left unproven by them is the question a producer rollout
 * actually asks — <em>does a service that exports its logs here become answerable?</em> A receiver
 * can decode every field correctly and still bucket the batch somewhere no query names, or drop the
 * severity on the way to the wire DTO, and both would be green one layer down.
 *
 * <p>The batch is {@link TelemetryFixtures#canaryLogsRequest}: {@code service.name=qits-canary} plus
 * an instance attribute, one INFO and one ERROR carrying the OTel exception attributes, both inside
 * the canary's span. {@link OtelReceiverIT} runs the same batch through the packaged artifact once,
 * which is where native-image failures live.
 */
@QuarkusTest
class CanaryLogStreamTest {

  private static final String PROTOBUF = "application/x-protobuf";
  private static final String INGEST = "/observability/api/otel/v1";
  private static final String QUERY = "/observability/api/telemetry";

  /** The bucket the canary's resource attributes name. Opaque to callers; asserted, never built. */
  private static final String SOURCE = "_service/" + TelemetryFixtures.CANARY_SERVICE;

  private static final String INFO_BODY = "canary handled GET /canary";
  private static final String ERROR_BODY = "canary failed to reach the widget service";

  /**
   * The HTTP request ceiling this process ships, {@code quarkus.http.limits.max-body-size}=64M, in
   * bytes. The 413 test checks the configured value first, so this copy cannot drift away from the
   * one the packaged process enforces without the test saying so.
   */
  private static final long CEILING_BYTES = 64L * 1024 * 1024;

  @Inject TelemetryStore store;

  /** The ingest route's real address: the test port is ephemeral, so it cannot be spelled here. */
  @TestHTTPResource(INGEST + "/logs")
  URL logsEndpoint;

  @BeforeEach
  void resetStore() {
    store.clear();
  }

  private static void postCanaryLogs() {
    postCanaryLogs(INFO_BODY, ERROR_BODY);
  }

  private static void postCanaryLogs(String infoBody, String errorBody) {
    given()
        .contentType(PROTOBUF)
        .body(TelemetryFixtures.canaryLogsRequest(infoBody, errorBody).toByteArray())
        .when()
        .post(INGEST + "/logs")
        .then()
        .statusCode(200)
        .contentType(PROTOBUF);
  }

  private static void postCanarySpan() {
    given()
        .contentType(PROTOBUF)
        .body(TelemetryFixtures.canaryTraceRequest().toByteArray())
        .when()
        .post(INGEST + "/traces")
        .then()
        .statusCode(200);
  }

  /**
   * The identity question, and the one that fails silently: a batch carrying only {@code
   * service.name} used to land in {@code _unscoped}, where it was stored, counted and unreachable by
   * any query. The source list naming the canary is what makes every assertion below addressable.
   */
  @Test
  void theSourceListNamesTheCanaryRatherThanUnscopingIt() {
    postCanaryLogs();

    given()
        .get(QUERY + "/sources")
        .then()
        .statusCode(200)
        .body("sources.find { it.key == '" + SOURCE + "' }.kind", equalTo("SERVICE"))
        .body(
            "sources.find { it.key == '" + SOURCE + "' }.label",
            equalTo(TelemetryFixtures.CANARY_SERVICE))
        .body("sources.find { it.key == '" + SOURCE + "' }.logs", equalTo(2))
        .body(
            "sources.find { it.key == '" + SOURCE + "' }.services[0].name",
            equalTo(TelemetryFixtures.CANARY_SERVICE))
        .body("sources.key", not(hasItem(TelemetryStore.UNSCOPED_KEY)));
  }

  /**
   * Both records on the log tail with their severity intact. Severity is the field a screen filters
   * and colours on, and it survives two translations — the protobuf enum to the stored number, and
   * the stored record to the wire DTO — so it is asserted as the number <em>and</em> the text.
   */
  @Test
  void bothRecordsAreOnTheLogsEndpointWithTheirSeverityIntact() {
    postCanaryLogs();

    given()
        .get(QUERY + "/logs?source=" + SOURCE)
        .then()
        .statusCode(200)
        .body("logs", hasSize(2))
        .body("total", equalTo(2))
        .body("truncated", equalTo(false))
        // Oldest first: the INFO the canary logged, then the ERROR it failed with.
        .body("logs[0].body", equalTo(INFO_BODY))
        .body("logs[0].severityText", equalTo("INFO"))
        .body("logs[0].severityNumber", equalTo(9))
        .body("logs[0].serviceName", equalTo(TelemetryFixtures.CANARY_SERVICE))
        .body("logs[0].traceId", equalTo(TelemetryFixtures.CANARY_TRACE_ID))
        .body("logs[0].spanId", equalTo(TelemetryFixtures.CANARY_SPAN_ID))
        .body("logs[1].body", equalTo(ERROR_BODY))
        .body("logs[1].severityText", equalTo("ERROR"))
        .body("logs[1].severityNumber", equalTo(17))
        .body(
            "logs[1].attributes.'exception.type'",
            equalTo(TelemetryFixtures.CANARY_EXCEPTION_TYPE));

    // The buffer reports it kept both — a non-zero eviction counter is the difference between
    // "this is everything that arrived" and "this is what survived".
    given().get(QUERY + "/store").then().statusCode(200).body("evictedLogs", equalTo(0));
  }

  /**
   * Which build emitted the record. The platform stamps {@code service.version}, {@code
   * deployment.environment.name} and {@code service.instance.id} into every cd-deployed container's
   * resource, and the store has kept them all along — but the read surface used to drop them, so a
   * reader could see that qits-canary logged something and never which release did.
   *
   * <p>Asserted on all three reads that answer with a record, because they are three separate DTO
   * paths over the same stored log and a field wired into one is not wired into the others. The span
   * on the trace page carries it too: a log names its build, so its span has to name the same one or
   * the page contradicts itself.
   *
   * <p>The map arrives whole, so {@code service.name} is in it as well as beside it. That is the
   * point rather than a redundancy — nothing here filters the resource down to a list of attributes
   * someone thought of.
   */
  @Test
  void everyReadCarriesTheResourceIdentityOfTheBuildThatEmittedIt() {
    postCanarySpan();
    postCanaryLogs();

    given()
        .get(QUERY + "/logs?source=" + SOURCE)
        .then()
        .statusCode(200)
        .body(
            "logs[0].resourceAttributes.'service.version'",
            equalTo(TelemetryFixtures.CANARY_VERSION))
        .body(
            "logs[0].resourceAttributes.'deployment.environment.name'",
            equalTo(TelemetryFixtures.CANARY_ENVIRONMENT))
        .body(
            "logs[0].resourceAttributes.'service.instance.id'",
            equalTo(TelemetryFixtures.CANARY_INSTANCE_ID))
        .body(
            "logs[0].resourceAttributes.'service.name'",
            equalTo(TelemetryFixtures.CANARY_SERVICE));

    given()
        .get(QUERY + "/errors?source=" + SOURCE)
        .then()
        .statusCode(200)
        .body(
            "groups[0].errorLogs[0].resourceAttributes.'service.version'",
            equalTo(TelemetryFixtures.CANARY_VERSION))
        .body(
            "groups[0].errorLogs[0].resourceAttributes.'service.instance.id'",
            equalTo(TelemetryFixtures.CANARY_INSTANCE_ID));

    given()
        .get(QUERY + "/traces/" + TelemetryFixtures.CANARY_TRACE_ID + "?source=" + SOURCE)
        .then()
        .statusCode(200)
        .body(
            "trace.logs[1].resourceAttributes.'service.version'",
            equalTo(TelemetryFixtures.CANARY_VERSION))
        .body(
            "trace.spans[0].resourceAttributes.'service.version'",
            equalTo(TelemetryFixtures.CANARY_VERSION))
        .body(
            "trace.spans[0].resourceAttributes.'deployment.environment.name'",
            equalTo(TelemetryFixtures.CANARY_ENVIRONMENT));
  }

  /**
   * The errors feed is what an agent reads instead of scraping a log tail, so the stack trace has to
   * arrive whole rather than as a formatted message. The INFO record must not be in it: a feed that
   * groups by trace would otherwise carry every record the failing request also logged.
   */
  @Test
  void theErrorIsInTheErrorsFeedWithItsStackTrace() {
    postCanaryLogs();

    given()
        .get(QUERY + "/errors?source=" + SOURCE)
        .then()
        .statusCode(200)
        .body("groups", hasSize(1))
        .body("groups[0].traceId", equalTo(TelemetryFixtures.CANARY_TRACE_ID))
        .body("groups[0].serviceName", equalTo(TelemetryFixtures.CANARY_SERVICE))
        .body("groups[0].errorLogs", hasSize(1))
        .body("groups[0].errorLogs[0].body", equalTo(ERROR_BODY))
        .body(
            "groups[0].errorLogs[0].attributes.'exception.type'",
            equalTo(TelemetryFixtures.CANARY_EXCEPTION_TYPE))
        .body(
            "groups[0].errorLogs[0].attributes.'exception.message'",
            equalTo(TelemetryFixtures.CANARY_EXCEPTION_MESSAGE))
        .body(
            "groups[0].errorLogs[0].attributes.'exception.stacktrace'",
            containsString("CanaryResource.callWidgets(CanaryResource.java:42)"));
  }

  /**
   * The trace-scoped read: the span the canary was serving, and beside it the records it logged
   * while serving it. This is the correlation the whole OTLP choice was made for — a log links to
   * its trace by first-class ids, not by a convention parsed out of formatted text.
   */
  @Test
  void theCorrelatedLogsAppearOnTheirTracePage() {
    postCanarySpan();
    postCanaryLogs();

    given()
        .get(QUERY + "/traces/" + TelemetryFixtures.CANARY_TRACE_ID + "?source=" + SOURCE)
        .then()
        .statusCode(200)
        .body("trace.traceId", equalTo(TelemetryFixtures.CANARY_TRACE_ID))
        .body("trace.spans", hasSize(1))
        .body("trace.spans[0].spanId", equalTo(TelemetryFixtures.CANARY_SPAN_ID))
        .body("trace.spans[0].name", equalTo("GET /canary"))
        .body("trace.logs", hasSize(2))
        .body("trace.logs[0].body", equalTo(INFO_BODY))
        .body("trace.logs[1].severityText", equalTo("ERROR"))
        .body(
            "trace.logs[1].attributes.'exception.stacktrace'",
            containsString("java.lang.IllegalStateException"));

    // And the trace is listed, so the page is reachable without knowing the id in advance.
    given()
        .get(QUERY + "/traces?source=" + SOURCE)
        .then()
        .statusCode(200)
        .body("traces", hasSize(1))
        .body("traces[0].traceId", equalTo(TelemetryFixtures.CANARY_TRACE_ID))
        .body("traces[0].rootName", equalTo("GET /canary"))
        .body("traces[0].rootService", equalTo(TelemetryFixtures.CANARY_SERVICE));
  }

  /**
   * A second batch after the first connection was closed. An exporter is a long-lived client that
   * goes idle between batches and reconnects when the pool drops the socket, and the receiver holds
   * no per-connection state — so the fact under test is that the second batch is accepted and
   * <em>accumulates</em> rather than replacing the first.
   *
   * <p>{@code Connection: close} makes the reconnect real instead of assumed: without it the client
   * would very likely reuse the pooled socket and the test would prove nothing about a new one.
   */
  @Test
  void aSecondBatchOnANewConnectionIsAcceptedAndAccumulates() {
    given()
        .contentType(PROTOBUF)
        .header("Connection", "close")
        .body(TelemetryFixtures.canaryLogsRequest(INFO_BODY, ERROR_BODY).toByteArray())
        .when()
        .post(INGEST + "/logs")
        .then()
        .statusCode(200);

    postCanaryLogs("canary handled GET /canary again", "canary failed again");

    given()
        .get(QUERY + "/logs?source=" + SOURCE)
        .then()
        .statusCode(200)
        .body("logs", hasSize(4))
        .body("logs.body", hasItem(INFO_BODY))
        .body("logs.body", hasItem("canary handled GET /canary again"))
        .body("logs.body", hasItem("canary failed again"));

    given()
        .get(QUERY + "/errors?source=" + SOURCE)
        .then()
        .statusCode(200)
        // Same trace id both times, so both errors group under it rather than making a second row.
        .body("groups", hasSize(1))
        .body("groups[0].errorLogs", hasSize(2));
  }

  /** Malformed protobuf is the client's fault and must stay a 400, not a 500 and not a silent 200. */
  @Test
  void malformedProtobufIsRejectedWith400AndStoresNothing() {
    given()
        .contentType(PROTOBUF)
        .body(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x13})
        .when()
        .post(INGEST + "/logs")
        .then()
        .statusCode(400);

    given().get(QUERY + "/logs?source=" + SOURCE).then().statusCode(200).body("logs", hasSize(0));
  }

  /**
   * A body over {@code quarkus.http.limits.max-body-size} is refused with 413 at the HTTP layer,
   * before {@link OtelReceiverResource} is entered — which is why the payload here is not even valid
   * protobuf: nothing gets far enough to parse it.
   *
   * <p>The ceiling is asserted against the shipped configuration first. A test that only sent "64
   * MiB + 1" would keep passing if the limit were raised, having quietly stopped measuring the
   * ceiling and started measuring an arbitrary large number.
   *
   * <p><b>A socket, not RestAssured, and not 64 MiB of real bytes.</b> The limit is enforced on the
   * declared {@code Content-Length} before a byte of body is read, and the server closes the
   * connection with the response — so a client that actually writes the payload is still writing
   * when the 413 arrives and reports a broken pipe instead of the status. That is what every pooled
   * client here does, RestAssured's included. Declaring the length is also what a real exporter's
   * oversized batch does, and it is the whole of what the receiver looks at.
   */
  @Test
  void aBodyOverTheCeilingIsRefusedWith413() throws Exception {
    assertEquals(
        "64M",
        ConfigProvider.getConfig().getValue("quarkus.http.limits.max-body-size", String.class),
        "the OTLP recommendation this receiver ships; the 413 below measures this value");

    assertTrue(
        statusLineForDeclaredBodyOf(CEILING_BYTES + 1).contains("413"),
        "a body over the ceiling is refused, not truncated and not accepted");
  }

  /**
   * The same ceiling, on the other side of the compression. A gzipped body is under the HTTP limit
   * by definition — that is what makes it worth compressing — so the wire check cannot speak for
   * what it inflates to, and a repeated byte gzips past 1000:1: the few kilobytes posted here expand
   * to more than 64 MiB. Before the receiver counted, that was a {@code readAllBytes()} into heap.
   *
   * <p>Compression itself is not what is refused, which is why the batch above it has to be accepted
   * first: the cap is a ceiling on the inflated size, not a rule against gzip.
   */
  @Test
  void aGzipBombIsRefusedWith413RatherThanInflatedWithoutBound() throws IOException {
    given()
        .contentType(PROTOBUF)
        .body(
            TelemetryFixtures.gzip(
                TelemetryFixtures.canaryLogsRequest(INFO_BODY, ERROR_BODY).toByteArray()))
        .when()
        .post(INGEST + "/logs")
        .then()
        .statusCode(200);
    given().get(QUERY + "/logs?source=" + SOURCE).then().statusCode(200).body("logs", hasSize(2));

    given()
        .contentType(PROTOBUF)
        .body(gzipBombPastTheCeiling())
        .when()
        .post(INGEST + "/logs")
        .then()
        .statusCode(413);

    // Refused whole: a bomb must not leave behind whatever decoded before the ceiling was reached.
    given().get(QUERY + "/logs?source=" + SOURCE).then().statusCode(200).body("logs", hasSize(2));
  }

  /**
   * A few kilobytes that inflate past the ceiling. Built by streaming a repeated chunk through the
   * compressor rather than compressing one huge array: the test has to stay as bounded in memory as
   * the receiver it is testing, and only the compressed form is ever held.
   */
  private static byte[] gzipBombPastTheCeiling() throws IOException {
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    byte[] chunk = new byte[64 * 1024];
    try (GZIPOutputStream gz = new GZIPOutputStream(compressed)) {
      for (long written = 0; written <= CEILING_BYTES; written += chunk.length) {
        gz.write(chunk);
      }
    }
    return compressed.toByteArray();
  }

  /** The status line of a POST that declares — and never sends — a body of {@code declaredBytes}. */
  private String statusLineForDeclaredBodyOf(long declaredBytes) throws IOException {
    try (Socket socket = new Socket(logsEndpoint.getHost(), logsEndpoint.getPort())) {
      socket.setSoTimeout(10_000);
      String head =
          "POST "
              + logsEndpoint.getPath()
              + " HTTP/1.1\r\nHost: "
              + logsEndpoint.getHost()
              + ':'
              + logsEndpoint.getPort()
              + "\r\nContent-Type: "
              + PROTOBUF
              + "\r\nContent-Length: "
              + declaredBytes
              + "\r\nExpect: 100-continue\r\nConnection: close\r\n\r\n";
      socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      return new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
          .readLine();
    }
  }

  /**
   * Restart truth. The store is ephemeral by design, and the number that carries that on the wire is
   * {@code startedAt}: a UI says "held since …" from it, so it must move when the buffer empties
   * rather than staying pinned to the first export it ever saw.
   *
   * <p>{@link TelemetryStore#clear()} is the restart's own code path — it drops the records, resets
   * the eviction counters and re-stamps {@code startedAt}, which is exactly what a fresh process
   * reports. A real process restart is not reproducible inside a {@code @QuarkusTest}; what the
   * packaged artifact can see of it is asserted in {@link OtelReceiverIT}.
   */
  @Test
  void anEmptiedBufferReportsANewStartedAtAndNothingRetained() {
    postCanaryLogs();
    String before =
        given().get(QUERY + "/store").then().statusCode(200).extract().path("startedAt");

    store.clear();

    String after =
        given()
            .get(QUERY + "/store")
            .then()
            .statusCode(200)
            .body("sourceCount", equalTo(0))
            .body("totalBytes", equalTo(0))
            .body("evictedLogs", equalTo(0))
            .body("evictedSpans", equalTo(0))
            .extract()
            .path("startedAt");
    assertTrue(
        Instant.parse(after).isAfter(Instant.parse(before)),
        "an emptied buffer has held nothing since now, and must say so: " + after + " vs " + before);

    // And the canary is gone from every read, not merely from the counters.
    given().get(QUERY + "/logs?source=" + SOURCE).then().statusCode(200).body("logs", hasSize(0));
    given().get(QUERY + "/sources").then().statusCode(200).body("sources", hasSize(0));
  }
}
