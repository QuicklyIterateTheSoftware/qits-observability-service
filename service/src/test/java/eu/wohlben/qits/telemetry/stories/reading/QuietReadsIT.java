package eu.wohlben.qits.telemetry.stories.reading;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <b>A source nobody reported for is empty — and no read of it, or of anything else, ever leaves
 * this process.</b>
 *
 * <p>Two claims, and the second is the one a presence check cannot make. Every other story here
 * draws an arrow to the parent collector; this one draws none, and that absence is the assertion:
 * {@code assertNoEdgesTo}. A read is answered from the in-memory buffer and from nowhere else — no
 * upstream lookup to fill a gap, no "ask the parent whether it has the trace we evicted", no
 * fan-out. It matters because the temptation is real and would be invisible: a supervisor tier holds
 * a longer window, and a middle tier that quietly consulted it would make every query as slow, as
 * fragile and as chatty as the worst link above it, while looking identical from the outside.
 *
 * <p>The first claim is the flip side of a buffer that owns nothing. Everything this service can say
 * about what exists, it says on the strength of what was exported to it — so the ways of having
 * nothing to show must not be confused with each other, or with the ways of being asked a question
 * that makes no sense.
 *
 * <ul>
 *   <li><b>An unknown bucket is 200 and EMPTY, never a 404.</b> The store genuinely cannot tell
 *       "never arrived" from "evicted an hour ago", and a 404 would invent a distinction it does not
 *       hold. What makes the empty <i>nameable</i> is the pair of endpoints beside it — {@code
 *       /sources}, which simply does not carry the key, and {@code /store}, whose eviction counters
 *       say whether anything was dropped at all.
 *   <li><b>A question that cannot be answered is a 400, never an empty answer.</b> A misspelt
 *       severity that quietly stopped filtering would show a screen full of INFO under a heading
 *       that says ERROR — the one wrong answer a log filter must never give — and a limit above the
 *       maximum is refused rather than silently clamped, because a caller that asked for more than
 *       it can have should hear so.
 * </ul>
 *
 * <p>The two 400s land on two different routes on purpose, so the diagram carries both. Two refusals
 * on one route would be one arrow — the same route and the same status — and the distinction would
 * live only in the notes, which is right but says less.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class QuietReadsIT {

  static final String CATEGORY = "reading";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY =
      "An unreported source is empty, and no read ever leaves this process";

  static final String SLUG = Slugs.slug(STORY);

  /** A pair nothing ever exported for. Its bucket does not exist, which is the point. */
  private static final String ABSENT_REPOSITORY = "uf-nobody";

  private static final String ABSENT_WORKSPACE = "uf-never-reported";

  private static final String ABSENT_KEY = ABSENT_REPOSITORY + "/" + ABSENT_WORKSPACE;

  /** Above {@code MAX_LIMIT}. Refused, not clamped. */
  private static final int OVER_THE_LIMIT = 1001;

  /** A severity band that does not exist. OTel's scale has six names and the numbers 1–24. */
  private static final String NOT_A_SEVERITY = "CRITICAL";

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      An operator opens a workspace that has never reported. Everything they ask answers 200 with
      nothing in it — the errors feed, the log tail, the trace list, and a trace fetched by id,
      which is the sharpest of the four because a fetch by identity is exactly where a 404 would
      feel natural. It is not one: this store cannot tell "never arrived" from "evicted an hour
      ago", and a surface that invented that distinction would be lying about which it knows.

      What makes the empty nameable is the two endpoints beside it. The source listing does not
      carry the key, so the bucket was never created; the store state says what has been evicted, so
      a screen can say "nothing has reported yet" instead of "you are looking at a window that has
      already scrolled past". Neither answer alone is enough, which is why both exist.

      Two questions get a different treatment, and it is the right one. A severity band that does
      not exist is a 400 — a filter that silently stopped filtering would show INFO records under a
      heading that says ERROR, and that is worse than any refusal. A limit above the maximum is a
      400 too, not a silent trim, because a caller that asked for more than it can have should hear
      so rather than believe it got everything.

      And nothing above left this process. There is no arrow to the parent collector on this
      diagram, which is the claim: a read is answered from the in-memory buffer and from nowhere
      else. The supervisor tier holds a longer window and a middle tier that quietly consulted it
      would make every query as slow and as fragile as the worst link above it — while looking, from
      the outside, exactly like this.
      """)
  @UserflowRunsAfter(TelemetryBootstrapIT.class)
  void anEmptyBucketAnswersAndNothingIsAskedUpstream(Interactions story) {
    NetworkCapture.actor(StoryTarget.OPERATOR);

    // (a) the four empties, asked through the same workspace lens a full bucket is read with — so
    // the only difference between this and the round-trip story is what was exported.
    StoryTarget.operator()
        .queryParam("repositoryId", ABSENT_REPOSITORY)
        .queryParam("workspaceId", ABSENT_WORKSPACE)
        .get(StoryTarget.ERRORS)
        .then()
        .statusCode(200)
        .body("groups", hasSize(0))
        .body("total", equalTo(0))
        .body("truncated", equalTo(false));
    StoryTarget.operator()
        .queryParam("repositoryId", ABSENT_REPOSITORY)
        .queryParam("workspaceId", ABSENT_WORKSPACE)
        .get(StoryTarget.LOGS)
        .then()
        .statusCode(200)
        .body("logs", hasSize(0))
        .body("total", equalTo(0));
    StoryTarget.operator()
        .queryParam("repositoryId", ABSENT_REPOSITORY)
        .queryParam("workspaceId", ABSENT_WORKSPACE)
        .get(StoryTarget.TRACES)
        .then()
        .statusCode(200)
        .body("traces", hasSize(0))
        .body("total", equalTo(0));
    story
        .note(
            "a bucket nobody ever exported for answers 200 and EMPTY on every listing — the store"
                + " cannot tell \"never arrived\" from \"evicted an hour ago\", and a 404 would"
                + " invent a distinction it does not hold")
        .as("an-unreported-source-is-empty");

    StoryTarget.operator()
        .queryParam("repositoryId", ABSENT_REPOSITORY)
        .queryParam("workspaceId", ABSENT_WORKSPACE)
        .get(StoryTarget.trace(TelemetryFixtures.TRACE_ID_B))
        .then()
        .statusCode(200)
        .body("trace.traceId", equalTo(TelemetryFixtures.TRACE_ID_B))
        .body("trace.spans", hasSize(0))
        .body("trace.logs", hasSize(0));
    story
        .note(
            "a trace id never seen answers the same way, and it is the sharper case: a fetch by"
                + " identity is exactly where a 404 would feel natural")
        .as("an-unknown-trace-is-empty");

    // (b) the two endpoints that make the empty nameable.
    StoryTarget.operator()
        .get(StoryTarget.SOURCES)
        .then()
        .statusCode(200)
        // The listing works — other buckets are in it — and simply does not carry this key.
        .body("sources.size()", greaterThan(0))
        .body("sources.find { it.key == '" + ABSENT_KEY + "' }", nullValue());
    StoryTarget.operator()
        .get(StoryTarget.STORE)
        .then()
        .statusCode(200)
        .body("startedAt", notNullValue())
        .body("caps.spansPerSource", equalTo(2000))
        .body("caps.logsPerSource", equalTo(10000));
    story
        .note(
            "the listing does not carry the key — while carrying every source that HAS reported, so"
                + " this is an absence and not a broken listing — and /store says what the buffer"
                + " has dropped. Together those two say \"nothing has reported yet\" rather than"
                + " \"the window has scrolled past\"")
        .as("the-empty-is-nameable");

    // (c) the two questions that cannot be answered. Both 400, on two routes, so both are drawn.
    String severity =
        StoryTarget.operator()
            .queryParam("repositoryId", ABSENT_REPOSITORY)
            .queryParam("workspaceId", ABSENT_WORKSPACE)
            .queryParam("minSeverity", NOT_A_SEVERITY)
            .get(StoryTarget.LOGS)
            .then()
            .statusCode(400)
            .extract()
            .asString();
    assertTrue(
        severity.contains("TRACE, DEBUG, INFO, WARN, ERROR, FATAL"),
        "a refused severity must list the ones that work: " + severity);
    story
        .note(
            "a severity band that does not exist is a 400 that names the ones that do — a filter"
                + " which silently stopped filtering would show INFO records under a heading that"
                + " says ERROR, and that is the one wrong answer a log filter must never give")
        .as("a-misspelt-filter-is-refused");

    String limit =
        StoryTarget.operator()
            .queryParam("repositoryId", ABSENT_REPOSITORY)
            .queryParam("workspaceId", ABSENT_WORKSPACE)
            .queryParam("limit", OVER_THE_LIMIT)
            .get(StoryTarget.ERRORS)
            .then()
            .statusCode(400)
            .extract()
            .asString();
    assertTrue(
        limit.contains("limit must be between 1 and 1000"),
        "a refused limit must say what the ceiling is: " + limit);
    story
        .note(
            "and a limit above the maximum is refused rather than quietly trimmed: a caller that"
                + " asked for more than it can have should hear so, not be handed a page it thinks"
                + " is everything")
        .as("an-oversized-limit-is-refused");

    story
        .note(
            "nothing above left this process. There is no arrow to the parent on this diagram, and"
                + " that absence IS the claim: a read is answered from the in-memory buffer and"
                + " nowhere else — never by asking the supervisor for the window this tier no"
                + " longer holds")
        .as("no-read-left-this-process");
  }

  @AfterAll
  static void theQuietReadsStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    in(StoryTarget.read(StoryTarget.ERRORS, 200));
    in(StoryTarget.read(StoryTarget.ERRORS, 400));
    in(StoryTarget.read(StoryTarget.LOGS, 200));
    in(StoryTarget.read(StoryTarget.LOGS, 400));
    in(StoryTarget.read(StoryTarget.TRACES, 200));
    in(StoryTarget.read(StoryTarget.trace(TelemetryFixtures.TRACE_ID_B), 200));
    in(StoryTarget.read(StoryTarget.SOURCES, 200));
    in(StoryTarget.read(StoryTarget.STORE, 200));

    // THE NEGATIVE HALF, and the reason this story exists. Not "there is no edge I asserted" — an
    // explicit claim that nothing on this diagram reached the supervisor.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryParent.SERVICE_NAME);
    ReportAssertions.assertNoEdgesFrom(CATEGORY_SLUG, SLUG, StoryTarget.SERVICE);
    ReportAssertions.assertOnlyEdgesFrom(CATEGORY_SLUG, SLUG, List.of(StoryTarget.OPERATOR));

    // EIGHT: six questions answered and two refused, and nothing else at all.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 8);

    for (String step :
        List.of(
            "an-unreported-source-is-empty",
            "an-unknown-trace-is-empty",
            "the-empty-is-nameable",
            "a-misspelt-filter-is-refused",
            "an-oversized-limit-is-refused",
            "no-read-left-this-process")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, step);
    }
  }

  private static void in(String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, SLUG, NetworkEdge.HTTP, StoryTarget.OPERATOR, StoryTarget.SERVICE, label);
  }
}
