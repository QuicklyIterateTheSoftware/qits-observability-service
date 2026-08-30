package eu.wohlben.qits.telemetry;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.telemetry.stories.support.StoryNetwork;
import eu.wohlben.qits.telemetry.stories.support.StoryParent;
import eu.wohlben.qits.telemetry.stories.support.StoryProfile;
import eu.wohlben.qits.telemetry.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The round trip, and the boot behind it.</b> The whole service as it is <b>packaged</b>, beside
 * the parent qits it tees to — the deployed posture no {@code @QuarkusTest} in this repository can
 * reach, and the one the two older ITs each cover half of. {@code OtelReceiverIT} proves the wire
 * format survives compilation; {@code PackagedSurfaceIT} proves the client is served. Neither says
 * what this service is <em>for</em>, which is a whole round trip: an exporter with no identity at
 * all puts a batch in, a copy goes on to the supervisor, and a named operator with the edge's roles
 * takes the same records back out.
 *
 * <p>Three things about that trip are properties of the built artifact plus its configuration, so
 * nothing short of launching it can show them:
 *
 * <ul>
 *   <li><b>The two doors are different.</b> {@code OtelReceiverResource} is {@code @PermitAll} and
 *       {@code WorkspaceTelemetryController} is {@code @RolesAllowed("qits:admin")}, and the
 *       difference only exists in a <b>prod</b> launch: under {@code @QuarkusTest} qits-auth-core's
 *       {@code %test} dev-user hands every request {@code qits:admin} before either annotation is
 *       consulted. The refusals story states that one; this one relies on it by carrying real
 *       headers on every read.
 *   <li><b>The tee is real.</b> {@code OtelForwarder} reads {@code otel.exporter.otlp.endpoint} and
 *       forwards through an {@link java.net.http.HttpClient} built in {@code @PostConstruct} rather
 *       than a static field, precisely so the native image will have it. A tee that fell over in the
 *       binary would leave the local store filling and every assertion in {@code OtelTeeTest} still
 *       green.
 *   <li><b>An empty buffer is an answer.</b> The read surface hands out {@code /store} and {@code
 *       /sources} so a screen can name which empty it is looking at — and this story reads {@code
 *       /store} <i>before</i> anything has exported, which is a sentence only the first story of a
 *       launched process can say.
 * </ul>
 *
 * <h2>This class owns the boot, and its edge count is what says the boot was quiet</h2>
 *
 * <p>{@link StoryParent}'s recording is cumulative with no floor, and this class sorts first of every
 * story class in the fork ({@code …telemetry.TelemetryBootstrapIT} before {@code
 * …telemetry.stories.*}), so anything the launched process posted the parent before any story ran
 * would land in this story's diagram. Exactly two outgoing arrows is therefore a claim about startup
 * as much as about the export: <b>starting a qits-observability costs its supervisor nothing.</b> It
 * has nothing to dial for — no key to fetch, no credential to mint, no registry to reconcile — and
 * the buffer it starts with is empty and says so.
 *
 * <h2>The diagram is OBSERVED, never narrated</h2>
 *
 * <p>{@link Interactions} records notes and nothing else. The edges come from two taps wired by
 * {@link StoryNetwork}: the framework's shipped RestAssured tap for what a story sends <i>into</i>
 * this process, and the parent collector's own access log for what the tee sent <i>out</i>. A story
 * method therefore asserts and notes; it draws nothing, which is what makes the tee evidence rather
 * than a claim.
 *
 * <p><b>The tee is asynchronous, and the stories are written so the diagram can see it anyway.</b> A
 * forward is fire-and-forget on another thread — deliberately, so ingest is never held up by a slow
 * supervisor — so a story that merely posted and returned would race the framework's drain and emit
 * an edge in one run and not the next. Every export below is followed by an {@code awaitForward}
 * that polls the parent's recording <b>before the story ends</b>.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class TelemetryBootstrapIT {

  public static final String CATEGORY = "ingest";

  public static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  public static final String STORY =
      "A workspace's export fills the buffer and reaches the parent collector";

  public static final String SLUG = Slugs.slug(STORY);

  /** One workspace's bucket: the pair an exporter stamps, and the key it is therefore filed under. */
  static final String REPOSITORY = "uf-widgets";

  static final String WORKSPACE = "uf-workspace-widgets";

  static final String SOURCE_KEY = REPOSITORY + "/" + WORKSPACE;

  static final String EXPORTING_SERVICE = "uf-widget-service";

  static final String ERROR_LOG = "the widget service could not reach its database";

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A container inside a workspace exports its spans and logs over OTLP. It carries no identity
      and cannot be given one — it is an SDK, not a session — so the ingest door is open, and what
      keeps one project's telemetry out of another's is the scope an exporter stamped, never the
      caller.

      Four things then have to be true at once, and only a deployed process can be all four.

      The buffer starts EMPTY and says so: this is the first thing that happens to a launched
      receiver, and `/store` reports no sources, nothing retained and nothing evicted. That is the
      honest lower bound a screen needs before it can say "held since" anything.

      The batch lands in the workspace's own bucket, keyed on the repository/workspace pair the
      resource carried — not on the caller, who has no name.

      It is relayed onward to the parent qits, under the exporter's own content type and its own
      content encoding, on this service's own connection, without the local store waiting for it.
      A managed qits is watched from both tiers, and a receiver that swallowed the copy would leave
      the supervisor blind. The logs go up STILL COMPRESSED, because that is what the exporter sent
      and the tee relays what it received rather than what it decoded.

      And a platform operator, named by the edge and carrying `qits:admin`, reads the very same
      records back: the source listing knows the bucket, the errors feed puts the failed span and
      the error log on one trace, and the trace page holds both.

      This is also the first story of the run, and the parent's recording has no floor under it — so
      the two outgoing arrows below are every arrow that existed by the time it finished. Starting
      a qits-observability costs its supervisor nothing.
      """)
  void anExportIsBufferedRelayedUpstreamAndReadBackByAnOperator(Interactions story) {
    // What the parent had already been posted when this story began. Every await below is for ONE
    // MORE than this, which is what keeps the stories order-independent while still pinning each
    // forward to the story that caused it.
    long tracesForwarded = StoryParent.forwardCount(StoryParent.TRACES);
    long logsForwarded = StoryParent.forwardCount(StoryParent.LOGS);

    story.note(
        "qits-observability starts as a managed service, beside the parent qits it forwards to");
    given().get(StoryTarget.READY).then().statusCode(200).body("status", equalTo("UP"));

    // --- (1) the buffer before anything reported. The actor is set BEFORE the call: the tap sees a
    // request, never a narrative role, and the framework resets the actor at every story start, so
    // nothing can leak in from a story that ran before.
    NetworkCapture.actor(StoryTarget.OPERATOR);
    StoryTarget.operator()
        .get(StoryTarget.STORE)
        .then()
        .statusCode(200)
        .body("startedAt", notNullValue())
        .body("sourceCount", equalTo(0))
        .body("totalBytes", equalTo(0))
        .body("evictedSpans", equalTo(0))
        .body("evictedLogs", equalTo(0))
        .body("droppedMetricSeries", equalTo(0))
        // The caps the deployment really runs under, read rather than assumed: the eviction story
        // measures these very numbers.
        .body("caps.spansPerSource", equalTo(2000))
        .body("caps.logsPerSource", equalTo(10000))
        .body("caps.metricSeriesPerSource", equalTo(500));
    story
        .note(
            "the buffer a launched receiver starts with is EMPTY, and it says so: no sources,"
                + " nothing retained, nothing evicted — which is the honest lower bound behind"
                + " every \"held since\" a screen prints")
        .as("the-buffer-starts-empty");

    // --- (2) the exporter's end. No identity, no headers, no session: OtelReceiverResource is
    // @PermitAll and this launch has no dev-user behind it, so a 200 here is the allow-list itself
    // under test rather than a synthetic identity's courtesy.
    NetworkCapture.actor(StoryTarget.EXPORTER);
    StoryTarget.otlp()
        .body(
            TelemetryFixtures.errorTraceRequest(
                    EXPORTING_SERVICE,
                    REPOSITORY,
                    WORKSPACE,
                    TelemetryFixtures.TRACE_ID_A,
                    TelemetryFixtures.SPAN_ID_A)
                .toByteArray())
        .when()
        .post(StoryTarget.TRACE_INGEST)
        .then()
        .statusCode(200)
        .contentType(StoryTarget.PROTOBUF);
    story
        .note(
            "an SDK inside a workspace posts protobuf spans with NO identity at all — the ingest"
                + " door is open, and the scope an exporter stamped is what files the batch")
        .as("spans-exported");

    // Compressed, and declared as such, because that is what a real exporter does with a batch: the
    // receiver decodes it by the gzip magic bytes rather than by trusting the header, and the tee
    // relays the header it received.
    StoryTarget.otlp()
        .header("Content-Encoding", "gzip")
        .body(
            TelemetryFixtures.gzip(
                TelemetryFixtures.logsRequest(
                        EXPORTING_SERVICE,
                        REPOSITORY,
                        WORKSPACE,
                        SeverityNumber.SEVERITY_NUMBER_ERROR,
                        ERROR_LOG,
                        TelemetryFixtures.TRACE_ID_A)
                    .toByteArray()))
        .when()
        .post(StoryTarget.LOG_INGEST)
        .then()
        .statusCode(200);
    story
        .note(
            "its logs follow on the same open door, GZIPPED the way an exporter really sends a"
                + " batch — decoded here by the magic bytes, never by trusting the header")
        .as("logs-exported");

    // --- (3) the parent's end. The forward is async fire-and-forget, so the assertion polls rather
    // than reading once — and it runs BEFORE the story ends, which is what puts the forward into
    // this story's diagram.
    //
    // The Content-Type is asserted BARE, without a charset, and that is why every post above goes
    // through StoryTarget.otlp(): a real exporter sends `application/x-protobuf` and OtelForwarder
    // relays what it received, so a charset here would mean RestAssured wrote the header rather
    // than the service relaying it, and the assertion would be about the test.
    StoryParent.Forward traces = StoryParent.awaitForward(StoryParent.TRACES, tracesForwarded);
    assertEquals(
        StoryTarget.PROTOBUF,
        traces.type(),
        "the parent must be posted the trace export with the exporter's own content type");
    assertEquals(200, traces.status());
    story
        .note(
            "the spans are relayed to the parent qits AS RECEIVED — the exporter's own content"
                + " type, on this service's own connection, with the local store never waiting for"
                + " it")
        .as("traces-teed");

    StoryParent.Forward logs = StoryParent.awaitForward(StoryParent.LOGS, logsForwarded);
    assertEquals(StoryTarget.PROTOBUF, logs.type());
    assertEquals(
        "gzip",
        logs.encoding(),
        "the tee relays what it RECEIVED, so a compressed batch travels on still compressed");
    story
        .note(
            "and the logs with them, still carrying the exporter's own Content-Encoding: a managed"
                + " qits is watched from both tiers, and the copy is the bytes that arrived rather"
                + " than the records this process decoded")
        .as("logs-teed");

    // --- (4) the operator's end. A name and a role, both asserted by the edge from one session;
    // this process authenticates nothing and reads the headers. The bucket is named first, because
    // `key` is opaque and the listing is the only place a caller may get one.
    NetworkCapture.actor(StoryTarget.OPERATOR);
    StoryTarget.operator()
        .get(StoryTarget.SOURCES)
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
        .note(
            "an operator the edge named and granted qits:admin lists the sources, and the"
                + " workspace's bucket is there with one span and one log in it — the compressed"
                + " batch decoded on arrival")
        .as("sources-listed");

    // The errors feed is the reading that justifies buffering both signals: the failed span and the
    // ERROR log correlate on one trace id and come back as ONE group, which is the answer a screen
    // shows and neither signal can give alone.
    StoryTarget.operator()
        .queryParam("repositoryId", REPOSITORY)
        .queryParam("workspaceId", WORKSPACE)
        .get(StoryTarget.ERRORS)
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
        .note(
            "the errors feed returns ONE group: the failed span and the ERROR log correlated on one"
                + " trace id, which is the answer neither signal can give alone")
        .as("errors-read");

    // …and the page the operator opens next: the trace itself, with the log that was emitted inside
    // it alongside the span. The pair reaches this endpoint as filters, not path segments — this
    // context owns neither a repository nor a workspace, it only files by what it was told.
    StoryTarget.operator()
        .queryParam("repositoryId", REPOSITORY)
        .queryParam("workspaceId", WORKSPACE)
        .get(StoryTarget.trace(TelemetryFixtures.TRACE_ID_A))
        .then()
        .statusCode(200)
        .body("trace.traceId", equalTo(TelemetryFixtures.TRACE_ID_A))
        .body("trace.spans", hasSize(1))
        .body("trace.spans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_A))
        .body("trace.logs", hasSize(1))
        .body("trace.logs[0].body", equalTo(ERROR_LOG));
    story
        .note(
            "and the trace page itself holds both: the span, and the log emitted inside it. The"
                + " repository/workspace pair reaches this route as FILTERS, not path segments —"
                + " this context owns neither")
        .as("trace-page-read");
    story
        .note(
            "these eight arrows are all there is: the parent's recording has no floor under it, so"
                + " a boot that had fetched a key, minted a credential or announced itself would be"
                + " drawn right here")
        .as("the-boot-was-quiet");
  }

  @AfterAll
  static void theRoundTripStoryIsComplete() {
    // The extension emits the report in its afterEach, so it is on disk before @AfterAll runs.
    // assertComplete also proves the network section: the sidecar's edges are canonical, the
    // networkHash recomputes from them, and every mermaid line is in the markdown.
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    // --- in: the exporter's two posts and the operator's four reads, told apart by actor and route.
    // The readiness probe drew nothing — a `/q/` segment is what the shipped tap skips.
    in(StoryTarget.EXPORTER, StoryTarget.exported(StoryTarget.TRACE_INGEST, 200));
    in(StoryTarget.EXPORTER, StoryTarget.exported(StoryTarget.LOG_INGEST, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.STORE, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.SOURCES, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.ERRORS, 200));
    // The trace id is 32 lowercase hex characters and a whole segment, so the default scrubber
    // rewrites it to {digest} — the label is a template rather than this run's fixture, which is
    // what keeps the story's networkHash stable.
    in(
        StoryTarget.OPERATOR,
        StoryTarget.read(StoryTarget.trace(TelemetryFixtures.TRACE_ID_A), 200));

    // --- out: the tee, drained from the parent's own recording — the half no near-side tap can see.
    out(StoryParent.posted(StoryParent.TRACES));
    out(StoryParent.posted(StoryParent.LOGS));

    // EXACTLY those eight. A stray edge is invisible to presence checks — a probe the tap's skip
    // missed, or this process's own OTel SDK reaching the parent behind the tee, which the profile's
    // quarkus.otel.sdk.disabled exists to prevent. And with no floor under the parent's recording,
    // this is also the assertion that the boot was quiet.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 8);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        SLUG,
        List.of(StoryTarget.EXPORTER, StoryTarget.OPERATOR, StoryTarget.SERVICE));

    for (String step :
        List.of(
            "the-buffer-starts-empty",
            "spans-exported",
            "logs-exported",
            "traces-teed",
            "logs-teed",
            "sources-listed",
            "errors-read",
            "trace-page-read",
            "the-boot-was-quiet")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, step);
    }
  }

  private static void in(String actor, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, SLUG, NetworkEdge.HTTP, actor, StoryTarget.SERVICE, label);
  }

  private static void out(String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, SLUG, NetworkEdge.HTTP, StoryTarget.SERVICE, StoryParent.SERVICE_NAME, label);
  }
}
