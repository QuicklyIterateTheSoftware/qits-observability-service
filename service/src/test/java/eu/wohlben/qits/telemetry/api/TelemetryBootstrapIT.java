package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.servicemock.MockService;
import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b>, beside the parent qits it tees to — the deployed
 * posture no {@code @QuarkusTest} here can reach, and the one the two existing ITs each cover half
 * of. {@link OtelReceiverIT} proves the wire format survives compilation; {@link PackagedSurfaceIT}
 * proves the client is served. Neither says what this service is <em>for</em>, which is a whole
 * round trip: an exporter with no identity at all puts a batch in, a named operator with the edge's
 * roles takes it out, and a parent collector gets the same bytes on the way past.
 *
 * <p>Three things about that trip are properties of the built artifact plus its configuration, so
 * nothing short of launching it can show them:
 *
 * <ul>
 *   <li><b>The two doors are different.</b> {@code OtelReceiverResource} is {@code @PermitAll} and
 *       {@code WorkspaceTelemetryController} is {@code @RolesAllowed("qits:admin")}, and the
 *       difference only exists in a <b>prod</b> launch: under {@code @QuarkusTest} qits-auth-core's
 *       {@code %test} dev-user hands every request {@code qits:admin} before either annotation is
 *       consulted, so a suite cannot tell the doors apart. Here nothing is injected, {@code
 *       ForwardAuthMechanism} is {@code LaunchMode.NORMAL}, and the identity is the header or
 *       nothing.
 *   <li><b>The tee is real.</b> {@link OtelForwarder} reads {@code otel.exporter.otlp.endpoint} —
 *       the env-var-shaped key a supervising qits injects as {@code OTEL_EXPORTER_OTLP_ENDPOINT} —
 *       and forwards through an {@link java.net.http.HttpClient} built in {@code @PostConstruct}
 *       rather than a static field, precisely so the native image will have it. A tee that fell
 *       over in the binary would leave the local store filling and every assertion in
 *       {@code OtelTeeTest} still green.
 *   <li><b>An empty answer is an answer.</b> The read surface refuses to invent a 404 for a bucket
 *       the store cannot distinguish from an evicted one, and hands out {@code /sources} and
 *       {@code /store} so a screen can name which empty it is looking at.
 * </ul>
 *
 * <p>The far side is a {@link MockService} impersonating the parent qits-observability, so the
 * forward is assertable on <b>both ends</b>: the receiver answered 200 and the parent really was
 * posted to. What it cannot assert is the bytes — the mock records method, path and headers, not
 * bodies — and it does not need to: {@code OtelTeeTest} pins byte-verbatim relay against the
 * suite's JVM. What is proven here and nowhere else is that the relay happens <em>at all</em> from
 * the packaged process, and that the headers it depends on travel with it.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code service/target/userstories/} with the interactions drawn as a sequence diagram. Both
 * stories are browserless (an {@code Interactions} parameter and no {@code Flow}), so the
 * framework's transitive Playwright never launches anything — which is what lets this run in a step
 * container with no browser in it.
 *
 * <p><b>The module does not opt back into ITs for this.</b> {@code skipITs} stays true in the root
 * pom, because {@link PackagedSurfaceIT} is half about the CLIENT — the base href, the deep links,
 * the fallback that must not swallow a machine path — and the userflow pipeline builds without
 * Quinoa, so flipping the property would make a quinoa-less run red on a test that is right.
 * {@code .config/qits/ci-event-userflows.yml} names this class instead:
 * {@code -DskipITs=false "-Dit.test=TelemetryBootstrapIT"}. A {@code -Dnative} build still runs
 * every IT, this one included, against the binary.
 */
@QuarkusIntegrationTest
@TestProfile(TelemetryBootstrapIT.PackagedBesideAParentCollector.class)
public class TelemetryBootstrapIT {

  static final String CATEGORY = "telemetry";
  static final String ROUND_TRIP_SLUG =
      "a-workspace-s-export-fills-the-buffer-and-reaches-the-parent-collector";
  static final String EMPTY_SLUG = "an-unknown-source-is-empty-and-an-unnamed-reader-is-refused";

  /**
   * The service the mock impersonates — also the {@link MockService#ensureStarted} key. It is this
   * same application one tier up: a managed qits forwards to its supervisor, which is another
   * qits-observability, so the name says which end of the tee it is rather than inventing a
   * different product.
   */
  static final String PARENT = "parent-qits-observability";

  private static final String PROTOBUF = "application/x-protobuf";

  /** The prefixed wire paths, spelled in full — {@code quarkus.rest.path} is part of them. */
  private static final String INGEST = "/observability/api/otel/v1";

  private static final String QUERY = "/observability/api/telemetry";
  private static final String READY = "/observability/q/health/ready";

  /** The headers qits-gateway asserts and strips; qits-auth-core's defaults, unchanged here. */
  private static final String USER_HEADER = "X-Qits-User";

  private static final String ROLES_HEADER = "X-Qits-Roles";

  /** One workspace's bucket: the pair an exporter stamps, and the key it is therefore filed under. */
  static final String REPOSITORY = "uf-repository";

  static final String WORKSPACE = "uf-workspace";
  static final String SOURCE_KEY = REPOSITORY + "/" + WORKSPACE;
  static final String EXPORTING_SERVICE = "uf-widget-service";
  static final String ERROR_LOG = "the widget service could not reach its database";

  /** A pair nothing ever exported for. Its bucket does not exist, which is the point. */
  static final String ABSENT_REPOSITORY = "uf-repository-that-never-reported";

  static final String ABSENT_WORKSPACE = "uf-workspace-that-never-reported";

  /**
   * A third pair, for the one export the second story makes. It is separate from {@link
   * #REPOSITORY} on purpose: the two stories share one launched process and one in-memory buffer,
   * and the first story counts what its bucket holds. A story that appended to that bucket would
   * make the pair pass in one JUnit method order and fail in another.
   */
  static final String OPEN_DOOR_REPOSITORY = "uf-repository-at-the-open-door";

  static final String OPEN_DOOR_WORKSPACE = "uf-workspace-at-the-open-door";

  /**
   * Marks the stubs as registered, for the same reason {@code MockIdp} parks its keypair: a test
   * profile is instantiated in more than one classloader and a static field written by one copy is
   * not the field another reads, while the JVM has exactly one property table. {@link
   * MockService#ensureStarted} already makes the <em>server</em> singular; the stubs live on the
   * owning instance, so this is what keeps the second copy from trying (and failing) to re-register
   * them on an attached handle.
   */
  private static final String STUBBED_PROPERTY = "qits.observability.it.parent-stubbed";

  /**
   * Hands the launched artifact its config the way a deployment does — and there is very little of
   * it, which is this service's shape rather than an omission: it owns no tables, so {@code
   * .config/qits/deployments.yml} declares no {@code resources:} at all and there are no generic
   * triples to supply. A qits-observability deployment is a process and two addresses.
   *
   * <p>Both keys are <b>runtime</b> keys. A packaged process takes its configuration as {@code -D}
   * arguments on an artifact that was already built, so a build-time key here would be silently
   * ignored and the test would prove something other than what it says. Everything that makes this
   * service what it is — {@code quarkus.rest.path}, the MCP root path, the Quinoa ignore list, the
   * 64M body limit, the four OTel logging keys — is left exactly as it ships.
   */
  public static class PackagedBesideAParentCollector implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
      MockService parent = parentStartedAndStubbed();
      return Map.of(
          // The one seam this test moves: WHERE the parent is. The key is deliberately the
          // env-var-shaped `otel.exporter.otlp.endpoint` and not a quarkus.* one — that is the
          // spelling OtelForwarder reads and the spelling a supervising qits injects, so a rename
          // on either side fails here rather than in production.
          "otel.exporter.otlp.endpoint",
          parent.baseUrl(),
          // Dark outside a deployment, like %dev/%test. This is a NEUTRALISATION, not tidiness:
          // the shipped config points this service's own SDK at
          // http://qits-observability:8080/observability/api/otel — a hostname that resolves on
          // qits-net and nowhere else — so a launched artifact would spend the run retrying an
          // export to a host that is not there. Disabling the SDK also makes the mock parent's
          // recordings unambiguous: the only thing that can post to it is the tee.
          "quarkus.otel.sdk.disabled",
          "true");
    }
  }

  /**
   * Start the mock of the parent collector once per JVM and stub the two OTLP routes the tee posts
   * to.
   *
   * <p>Both answer 200 with an empty JSON object. A real collector answers a protobuf {@code
   * ExportTraceServiceResponse}, and it makes no difference to anything under test: {@link
   * OtelForwarder} discards the upstream body and only debug-logs a status, which is the whole of
   * "telemetry is best-effort upstream". {@code OtelTeeTest} already proves the harder half of that
   * — an upstream 400 does not disturb the local ingest.
   */
  static synchronized MockService parentStartedAndStubbed() {
    if (System.getProperty(STUBBED_PROPERTY) != null) {
      return MockService.attach(PARENT);
    }
    MockService parent = MockService.ensureStarted(PARENT);
    parent.stub("POST", "/v1/traces", Map.of());
    parent.stub("POST", "/v1/logs", Map.of());
    System.setProperty(STUBBED_PROPERTY, "true");
    return parent;
  }

  @UserStory(
      value = "A workspace's export fills the buffer and reaches the parent collector",
      category = "telemetry")
  @UserStoryDescription(
      """
      A container inside a workspace exports its spans and logs over OTLP. It carries no identity
      and cannot be given one — it is an SDK, not a session — so the ingest route is open, and
      what keeps one project's telemetry out of another's is the scope an exporter stamped, never
      the caller.

      Three things then have to be true at once, and only a deployed process can be all three.
      The batch lands in the workspace's own bucket, keyed on the repository/workspace pair the
      resource carried. It is relayed onward to the parent qits — under the exporter's own
      content type, on its own connection, without the local store waiting for it — because a
      managed qits is watched from both tiers and a receiver that swallowed the copy would leave
      the supervisor blind. And a platform operator, named by the edge and carrying `qits:admin`,
      reads the very same records back through the query surface: the source listing knows the
      bucket, the errors feed puts the failed span and the error log on one trace, and the trace
      page holds both.
      """)
  void anExportIsBufferedRelayedUpstreamAndReadBackByAnOperator(Interactions story) {
    MockService parent = MockService.attach(PARENT);

    story.note(
        "qits-observability starts as a managed service, beside the parent qits it forwards to");
    given().get(READY).then().statusCode(200).body("status", equalTo("UP"));

    // --- (1) the exporter's end. No identity, no headers, no session: OtelReceiverResource is
    // @PermitAll and this launch has no dev-user behind it, so a 200 here is the allow-list itself
    // under test rather than a synthetic identity's courtesy.
    otlp()
        .body(
            TelemetryFixtures.errorTraceRequest(
                    EXPORTING_SERVICE,
                    REPOSITORY,
                    WORKSPACE,
                    TelemetryFixtures.TRACE_ID_A,
                    TelemetryFixtures.SPAN_ID_A)
                .toByteArray())
        .when()
        .post(INGEST + "/traces")
        .then()
        .statusCode(200)
        .contentType(PROTOBUF);
    story
        .happened(
            "a workspace exporter",
            "qits-observability",
            "POST /observability/api/otel/v1/traces (protobuf, no identity)")
        .as("spans-exported");

    otlp()
        .body(
            TelemetryFixtures.logsRequest(
                    EXPORTING_SERVICE,
                    REPOSITORY,
                    WORKSPACE,
                    SeverityNumber.SEVERITY_NUMBER_ERROR,
                    ERROR_LOG,
                    TelemetryFixtures.TRACE_ID_A)
                .toByteArray())
        .when()
        .post(INGEST + "/logs")
        .then()
        .statusCode(200);
    story
        .happened(
            "a workspace exporter",
            "qits-observability",
            "POST /observability/api/otel/v1/logs (protobuf, no identity)")
        .as("logs-exported");

    // --- (2) the parent's end. The forward is async fire-and-forget — deliberately, so ingest is
    // never held up by a slow supervisor — so the assertion polls rather than reading once.
    //
    // The Content-Type is asserted BARE, without a charset, and that is why every post above goes
    // through otlp(): a real exporter sends `application/x-protobuf` and OtelForwarder relays what
    // it received, so a charset here would mean rest-assured wrote the header rather than the
    // service relaying it, and the assertion would be about the test.
    assertEquals(
        PROTOBUF,
        awaitForward(parent, "/v1/traces"),
        "the parent must be posted the trace export with the exporter's own content type");
    assertEquals(PROTOBUF, awaitForward(parent, "/v1/logs"));
    story
        .happened(
            "qits-observability",
            "parent qits-observability",
            "POST /v1/traces (relayed as received)")
        .as("traces-teed");
    story
        .happened(
            "qits-observability", "parent qits-observability", "POST /v1/logs (relayed as received)")
        .as("logs-teed");

    // --- (3) the operator's end. A name and a role, both asserted by the edge from one session;
    // this process authenticates nothing and reads the headers. The bucket is named first, because
    // `key` is opaque and the listing is the only place a caller may get one.
    operator()
        .get(QUERY + "/sources")
        .then()
        .statusCode(200)
        .body("sources.find { it.key == '" + SOURCE_KEY + "' }.kind", equalTo("WORKSPACE"))
        .body("sources.find { it.key == '" + SOURCE_KEY + "' }.repositoryId", equalTo(REPOSITORY))
        .body("sources.find { it.key == '" + SOURCE_KEY + "' }.workspaceId", equalTo(WORKSPACE))
        .body("sources.find { it.key == '" + SOURCE_KEY + "' }.spans", equalTo(1))
        .body("sources.find { it.key == '" + SOURCE_KEY + "' }.logs", equalTo(1))
        .body(
            "sources.find { it.key == '" + SOURCE_KEY + "' }.services[0].name",
            equalTo(EXPORTING_SERVICE))
        .body("sources.find { it.key == '" + SOURCE_KEY + "' }.oldestReceivedAt", notNullValue());
    story
        .happened(
            "a platform operator",
            "qits-observability",
            "GET /observability/api/telemetry/sources (X-Qits-Roles: qits:admin)")
        .as("sources-listed");

    // The errors feed is the reading that justifies buffering both signals: the failed span and the
    // ERROR log correlate on one trace id and come back as ONE group, which is the answer a screen
    // shows and neither signal can give alone.
    operator()
        .queryParam("repositoryId", REPOSITORY)
        .queryParam("workspaceId", WORKSPACE)
        .get(QUERY + "/errors")
        .then()
        .statusCode(200)
        .body("groups", hasSize(1))
        .body("groups[0].traceId", equalTo(TelemetryFixtures.TRACE_ID_A))
        .body("groups[0].serviceName", equalTo(EXPORTING_SERVICE))
        .body("groups[0].errorSpans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_A))
        .body("groups[0].errorSpans[0].name", equalTo("GET /boom"))
        .body(
            "groups[0].errorSpans[0].events[0].attributes.'exception.type'",
            equalTo("java.lang.IllegalStateException"))
        .body("groups[0].errorLogs", hasSize(1))
        .body("groups[0].errorLogs[0].body", equalTo(ERROR_LOG))
        .body("groups[0].errorLogs[0].severityText", equalTo("ERROR"));
    story
        .happened(
            "a platform operator",
            "qits-observability",
            "GET /observability/api/telemetry/errors (one trace, span and log together)")
        .as("errors-read");

    // …and the page the operator opens next: the trace itself, with the log that was emitted
    // inside it alongside the span. The pair reaches this endpoint as filters, not path segments —
    // this context owns neither a repository nor a workspace, it only files by what it was told.
    operator()
        .queryParam("repositoryId", REPOSITORY)
        .queryParam("workspaceId", WORKSPACE)
        .get(QUERY + "/traces/" + TelemetryFixtures.TRACE_ID_A)
        .then()
        .statusCode(200)
        .body("trace.traceId", equalTo(TelemetryFixtures.TRACE_ID_A))
        .body("trace.spans", hasSize(1))
        .body("trace.spans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_A))
        .body("trace.logs", hasSize(1))
        .body("trace.logs[0].body", equalTo(ERROR_LOG));
    story
        .happened(
            "a platform operator",
            "qits-observability",
            "GET /observability/api/telemetry/traces/{traceId} (spans and correlated logs)")
        .as("trace-page-read");
  }

  @UserStory(
      value = "An unknown source is empty, and an unnamed reader is refused",
      category = "telemetry")
  @UserStoryDescription(
      """
      The flip side of a buffer that owns nothing. Everything this service can say about what
      exists, it says on the strength of what was exported to it — so the ways of having nothing
      to show must not be confused with the ways of not being allowed to look.

      A bucket nobody exported for is EMPTY, with a 200: the store genuinely cannot tell "never
      arrived" from "evicted an hour ago", and a 404 would be inventing a distinction it does not
      hold. What makes the empty nameable is the pair of endpoints beside it — the source listing,
      which simply does not carry the key, and the store state, whose eviction counters say
      whether anything was ever dropped. A screen reading those two can say "nothing has reported
      yet" instead of guessing.

      Being refused is a different sentence entirely, and it comes in two. A reader the edge never
      named is challenged, because the read surface is `qits:admin` and there is nobody to check.
      A reader who IS named but carries the wrong roles is forbidden — the identity was fine, the
      grant was not. Meanwhile the exporter, which carries no identity at all and never could, is
      still served: one process, two doors, and getting them the wrong way round costs the
      platform either its telemetry or its isolation.
      """)
  void anAbsentBucketIsEmptyAndAnUnnamedReaderNeverGetsThatFar(Interactions story) {
    // (a) the bucket that never existed. Empty, bounded, and a 200 — asserted through the same
    // workspace lens the previous story read a full bucket with, so the only difference is what was
    // exported.
    operator()
        .queryParam("repositoryId", ABSENT_REPOSITORY)
        .queryParam("workspaceId", ABSENT_WORKSPACE)
        .get(QUERY + "/errors")
        .then()
        .statusCode(200)
        .body("groups", hasSize(0))
        .body("total", equalTo(0))
        .body("truncated", equalTo(false));
    operator()
        .queryParam("repositoryId", ABSENT_REPOSITORY)
        .queryParam("workspaceId", ABSENT_WORKSPACE)
        .get(QUERY + "/logs")
        .then()
        .statusCode(200)
        .body("logs", hasSize(0))
        .body("total", equalTo(0));
    story
        .happened(
            "a platform operator",
            "qits-observability",
            "GET /observability/api/telemetry/errors (a source nothing reported to) -> 200, empty")
        .as("unknown-source-is-empty");

    // A trace id that was never seen answers the same way, and it is the sharper case: a fetch by
    // identity is exactly where a 404 would feel natural.
    operator()
        .queryParam("repositoryId", ABSENT_REPOSITORY)
        .queryParam("workspaceId", ABSENT_WORKSPACE)
        .get(QUERY + "/traces/" + TelemetryFixtures.TRACE_ID_B)
        .then()
        .statusCode(200)
        .body("trace.traceId", equalTo(TelemetryFixtures.TRACE_ID_B))
        .body("trace.spans", hasSize(0))
        .body("trace.logs", hasSize(0));
    story
        .happened(
            "a platform operator",
            "qits-observability",
            "GET /observability/api/telemetry/traces/{traceId} (never seen) -> 200, empty trace")
        .as("unknown-trace-is-empty");

    // …and the two endpoints that make the empty nameable. The listing does not carry the key, and
    // the buffer has dropped nothing — together those say "nothing has reported yet" rather than
    // "you are looking at a window that has already scrolled past".
    operator()
        .get(QUERY + "/sources")
        .then()
        .statusCode(200)
        .body(
            "sources.find { it.key == '" + ABSENT_REPOSITORY + "/" + ABSENT_WORKSPACE + "' }",
            nullValue());
    operator()
        .get(QUERY + "/store")
        .then()
        .statusCode(200)
        .body("startedAt", notNullValue())
        .body("evictedSpans", equalTo(0))
        .body("evictedLogs", equalTo(0))
        .body("droppedMetricSeries", equalTo(0));
    story
        .happened(
            "a platform operator",
            "qits-observability",
            "GET /observability/api/telemetry/store (the buffer has dropped nothing)")
        .as("nothing-was-dropped");

    // (b) the door. No header at all: ForwardAuthMechanism yields no identity in a NORMAL launch —
    // the %dev/%test dev-user is scoped away AND LaunchMode-guarded — so @RolesAllowed challenges.
    // This status is unreachable from any @QuarkusTest in this repository.
    given().get(QUERY + "/sources").then().statusCode(401);
    story
        .happened(
            "an unnamed reader",
            "qits-observability",
            "GET /observability/api/telemetry/sources (no X-Qits-User) -> 401")
        .as("unnamed-reader-challenged");

    // Named by the edge, but not granted: authenticated and forbidden, which is a different answer
    // and must stay one. Collapsing 403 into 401 would tell an operator to log in again.
    given()
        .header(USER_HEADER, "mallory")
        .header(ROLES_HEADER, "qits:reader")
        .get(QUERY + "/sources")
        .then()
        .statusCode(403);
    story
        .happened(
            "a reader without the grant",
            "qits-observability",
            "GET /observability/api/telemetry/sources (X-Qits-Roles: qits:reader) -> 403")
        .as("ungranted-reader-refused");

    // And the exporter, on the same process and the same port, with no identity whatsoever: still
    // served. This is the assertion that makes the two above mean something — a service that had
    // simply been locked would pass both of them.
    otlp()
        .body(
            TelemetryFixtures.okTraceRequest(
                    EXPORTING_SERVICE,
                    OPEN_DOOR_REPOSITORY,
                    OPEN_DOOR_WORKSPACE,
                    TelemetryFixtures.TRACE_ID_B,
                    TelemetryFixtures.SPAN_ID_B)
                .toByteArray())
        .when()
        .post(INGEST + "/traces")
        .then()
        .statusCode(200);
    story
        .happened(
            "a workspace exporter",
            "qits-observability",
            "POST /observability/api/otel/v1/traces (still open to an SDK) -> 200")
        .as("exporter-still-served");
  }

  /**
   * An OTLP post shaped like a real exporter's: the content type BARE, with no charset parameter.
   * rest-assured appends one by default, and {@link OtelForwarder} relays the header it received —
   * so without this the tee assertion would be checking rest-assured's spelling rather than the
   * service's relay. {@code OtelTeeTest} disables the same default for the same reason.
   */
  private static RequestSpecification otlp() {
    return given()
        .config(
            RestAssured.config()
                .encoderConfig(
                    encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false)))
        .contentType(PROTOBUF);
  }

  /** A read as the edge presents one: a name and the roles asserted from the same session. */
  private static RequestSpecification operator() {
    return given().header(USER_HEADER, "ops").header(ROLES_HEADER, "qits:admin");
  }

  /**
   * Wait for the tee to reach the parent on {@code path} and answer with the {@code Content-Type}
   * it arrived with. The forward is fire-and-forget on another thread, so the ingest response is
   * back before the upstream request is — polling is the shape of the contract, not flake
   * tolerance.
   */
  private static String awaitForward(MockService parent, String path) {
    long deadline = System.currentTimeMillis() + 10_000;
    while (true) {
      MockService.RecordedRequest forwarded =
          parent.recordedRequests().stream()
              .filter(request -> "POST".equals(request.method()) && path.equals(request.path()))
              .findFirst()
              .orElse(null);
      if (forwarded != null) {
        return forwarded.headers().get("Content-Type");
      }
      if (System.currentTimeMillis() > deadline) {
        return fail("the parent collector was never posted " + path);
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return fail("interrupted waiting for the forward of " + path);
      }
    }
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, ROUND_TRIP_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        ROUND_TRIP_SLUG,
        "qits-observability",
        "parent qits-observability",
        "POST /v1/traces (relayed as received)");
    ReportAssertions.assertStepId(CATEGORY, ROUND_TRIP_SLUG, "spans-exported");
    ReportAssertions.assertStepId(CATEGORY, ROUND_TRIP_SLUG, "logs-exported");
    ReportAssertions.assertStepId(CATEGORY, ROUND_TRIP_SLUG, "traces-teed");
    ReportAssertions.assertStepId(CATEGORY, ROUND_TRIP_SLUG, "logs-teed");
    ReportAssertions.assertStepId(CATEGORY, ROUND_TRIP_SLUG, "sources-listed");
    ReportAssertions.assertStepId(CATEGORY, ROUND_TRIP_SLUG, "errors-read");
    ReportAssertions.assertStepId(CATEGORY, ROUND_TRIP_SLUG, "trace-page-read");

    ReportAssertions.assertComplete(CATEGORY, EMPTY_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, EMPTY_SLUG, "unknown-source-is-empty");
    ReportAssertions.assertStepId(CATEGORY, EMPTY_SLUG, "unknown-trace-is-empty");
    ReportAssertions.assertStepId(CATEGORY, EMPTY_SLUG, "nothing-was-dropped");
    ReportAssertions.assertStepId(CATEGORY, EMPTY_SLUG, "unnamed-reader-challenged");
    ReportAssertions.assertStepId(CATEGORY, EMPTY_SLUG, "ungranted-reader-refused");
    ReportAssertions.assertStepId(CATEGORY, EMPTY_SLUG, "exporter-still-served");
  }
}
