package eu.wohlben.qits.telemetry.stories.refusals;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.telemetry.TelemetryBootstrapIT;
import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.stories.support.StoryNetwork;
import eu.wohlben.qits.telemetry.stories.support.StoryParent;
import eu.wohlben.qits.telemetry.stories.support.StoryProfile;
import eu.wohlben.qits.telemetry.stories.support.StoryTarget;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>One port, two doors — and getting them the wrong way round costs the platform either its
 * telemetry or its isolation.</b>
 *
 * <p>This is the story no {@code @QuarkusTest} in this repository can tell. {@code
 * OtelReceiverResource} is {@code @PermitAll} and {@code WorkspaceTelemetryController} is {@code
 * @RolesAllowed("qits:admin")}, and under {@code @QuarkusTest} qits-auth-core's {@code %test}
 * dev-user hands every request {@code qits:admin} before either annotation is consulted. So a suite
 * cannot tell the doors apart: it sees 200 either way, and would see 200 either way if the
 * annotations were swapped. A {@code NORMAL} launch has no dev-user, {@code ForwardAuthMechanism} is
 * {@code LaunchMode}-guarded, and the identity is the header or nothing.
 *
 * <p><b>It is not a hypothetical.</b> This class once carried {@code @RolesAllowed("qits:system")} —
 * caught by the 2026-08-15 "protect observability APIs" sweep, which meant the UI routes. Every
 * export answered 401, the SDK retried silently, nothing logged, and the store stayed empty for five
 * days on the live platform. The cost of the opposite mistake is the mirror image: a read surface
 * left open on a service that holds every project's telemetry in one process.
 *
 * <p>Three callers, one route, three statuses — and on the wire the only difference between them is
 * two headers a diagram must not print. The <b>actor</b> and the <b>status</b> are what tell them
 * apart, which is exactly why a story names its initiators before it calls.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class OneDoorEachIT {

  static final String CATEGORY = "refusals";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "A reader is challenged at the door an exporter walks through";

  static final String SLUG = Slugs.slug(STORY);

  private static final String REPOSITORY = "uf-open-door";

  private static final String WORKSPACE = "uf-workspace-open-door";

  private static final String SOURCE_KEY = REPOSITORY + "/" + WORKSPACE;

  private static final String EXPORTING_SERVICE = "uf-open-door-service";

  private static final String TRACE = "cc33cc33cc33cc33cc33cc33cc33cc33";

  private static final String SPAN = "cc33cc33cc33cc33";

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      Three callers ask this process for the source listing and get three different answers, and a
      fourth carries no identity at all and is served.

      A reader the edge never named is CHALLENGED. The read surface is `qits:admin` and there is
      nobody to check — no `X-Qits-User`, no identity, 401. This status is unreachable from any
      @QuarkusTest in this repository, because the test profile's dev-user hands every request the
      admin role before the annotation is consulted.

      A reader the edge DID name but did not grant is FORBIDDEN. The identity was fine; the grant
      was not, and collapsing 403 into 401 would tell an operator to log in again when logging in
      again is precisely what will not help.

      A named operator carrying the role is served.

      And the exporter, on the SAME process and the SAME port, carrying no identity whatsoever, is
      served too — its batch stored and teed on exactly as any other. That last arm is what makes
      the first two mean anything: a service that had simply been locked would pass both refusals
      and fail the platform, silently, for as long as nobody noticed the store was empty. It has
      happened here — every export answered 401 for five days while the SDK retried without logging
      a word.

      Neither refusal reached the supervisor, and the count says so: one export, one forward. A
      refusal is this tier's own answer, and it costs the tier above nothing.
      """)
  @UserflowRunsAfter(TelemetryBootstrapIT.class)
  void theReadDoorRefusesWhereTheIngestDoorServes(Interactions story) {
    long tracesForwarded = StoryParent.forwardCount(StoryParent.TRACES);

    // (a) no header at all. ForwardAuthMechanism yields no identity in a NORMAL launch — the
    // %dev/%test dev-user is scoped away AND LaunchMode-guarded — so @RolesAllowed challenges.
    NetworkCapture.actor(StoryTarget.UNNAMED_READER);
    given().get(StoryTarget.SOURCES).then().statusCode(401);
    story
        .note(
            "a reader the edge never named is CHALLENGED: the read surface is qits:admin and there"
                + " is nobody to check. A status no @QuarkusTest in this repository can reach")
        .as("an-unnamed-reader-is-challenged");

    // (b) named, not granted. A different answer, and it must stay one.
    NetworkCapture.actor(StoryTarget.UNGRANTED_READER);
    StoryTarget.ungranted().get(StoryTarget.SOURCES).then().statusCode(403);
    story
        .note(
            "a reader the edge DID name but did not grant is FORBIDDEN — the identity was fine, the"
                + " grant was not, and collapsing 403 into 401 would tell an operator to log in"
                + " again when logging in again is exactly what will not help")
        .as("an-ungranted-reader-is-forbidden");

    // (c) named and granted.
    NetworkCapture.actor(StoryTarget.OPERATOR);
    StoryTarget.operator().get(StoryTarget.SOURCES).then().statusCode(200);
    story
        .note(
            "and a named operator carrying qits:admin is served — three callers, one route, three"
                + " statuses, and on the wire the only difference between them is two headers a"
                + " diagram must not print")
        .as("a-granted-operator-is-served");

    // (d) the door that is open on purpose, on the same process and the same port.
    NetworkCapture.actor(StoryTarget.EXPORTER);
    StoryTarget.otlp()
        .body(
            TelemetryFixtures.okTraceRequest(EXPORTING_SERVICE, REPOSITORY, WORKSPACE, TRACE, SPAN)
                .toByteArray())
        .when()
        .post(StoryTarget.TRACE_INGEST)
        .then()
        .statusCode(200);
    assertEquals(
        200,
        StoryParent.awaitForward(StoryParent.TRACES, tracesForwarded).status(),
        "an export through the open door is relayed upstream like any other");
    story
        .note(
            "the exporter, on the same process and the same port, carrying no identity whatsoever,"
                + " is STILL SERVED — and its batch is teed on exactly as any other. Ingest is"
                + " unauthenticated by doctrine: its callers are SDKs inside workspace containers on"
                + " qits-net, which carry no identity and could not be given one")
        .as("the-exporter-is-still-served");

    // …and it really was stored, not merely accepted: a locked-shut receiver could answer 200 and
    // keep nothing, which is the failure mode this whole story is about.
    NetworkCapture.actor(StoryTarget.OPERATOR);
    StoryTarget.operator()
        .queryParam("repositoryId", REPOSITORY)
        .queryParam("workspaceId", WORKSPACE)
        .get(StoryTarget.trace(TRACE))
        .then()
        .statusCode(200)
        .body("trace.spans", hasSize(1))
        .body("trace.spans[0].spanId", equalTo(SPAN));
    StoryTarget.operator()
        .get(StoryTarget.SOURCES)
        .then()
        .statusCode(200)
        .body("sources.find { it.key == '" + SOURCE_KEY + "' }.spans", equalTo(1));
    story
        .note(
            "and the batch is really in the buffer, not merely acknowledged: a receiver that had"
                + " been locked shut could answer 200 and keep nothing, which is the failure this"
                + " story exists to make loud — it cost the platform five days of empty telemetry"
                + " once, with the SDK retrying silently and nothing logged anywhere")
        .as("the-batch-is-really-buffered");

    // An absence is an assertion, never an edge: neither refusal reached the supervisor.
    assertEquals(
        tracesForwarded + 1,
        StoryParent.forwardCount(StoryParent.TRACES),
        "one export, one forward — a refused read costs the tier above nothing");
    story
        .note(
            "neither refusal reached the supervisor: one export, one forward, and no other traffic"
                + " left this process at all. A refusal is this tier's own answer")
        .as("a-refusal-costs-the-parent-nothing");
  }

  @AfterAll
  static void theDoorStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    // The same route, three times, from three actors — and that triple IS the story.
    in(StoryTarget.UNNAMED_READER, StoryTarget.read(StoryTarget.SOURCES, 401));
    in(StoryTarget.UNGRANTED_READER, StoryTarget.read(StoryTarget.SOURCES, 403));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.SOURCES, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.trace(TRACE), 200));
    in(StoryTarget.EXPORTER, StoryTarget.exported(StoryTarget.TRACE_INGEST, 200));

    out(StoryParent.posted(StoryParent.TRACES));

    // SIX, and the one outgoing arrow belongs to the export. A refusal that had reached upstream —
    // to check a role, to log an attempt — would be a seventh.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 6);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        SLUG,
        List.of(
            StoryTarget.UNNAMED_READER,
            StoryTarget.UNGRANTED_READER,
            StoryTarget.OPERATOR,
            StoryTarget.EXPORTER,
            StoryTarget.SERVICE));

    for (String step :
        List.of(
            "an-unnamed-reader-is-challenged",
            "an-ungranted-reader-is-forbidden",
            "a-granted-operator-is-served",
            "the-exporter-is-still-served",
            "the-batch-is-really-buffered",
            "a-refusal-costs-the-parent-nothing")) {
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
