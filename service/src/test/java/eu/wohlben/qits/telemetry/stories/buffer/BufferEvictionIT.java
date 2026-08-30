package eu.wohlben.qits.telemetry.stories.buffer;

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
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The buffer is a window, and the honest thing it can do is say when the window has moved.</b>
 *
 * <p>This context owns no tables. Everything it holds it holds in memory, bounded per source and
 * bounded again by a global byte ceiling, and a restart empties it — which is the feature rather than
 * a limitation. The cost of that choice is exactly one thing: an answer can be <em>short because the
 * buffer scrolled</em> rather than because little happened, and those two are indistinguishable from
 * the answer alone. {@code /store}'s eviction counters are the whole of the difference, and this
 * story is the one that moves them.
 *
 * <p><b>The cap is the shipped one, not a story's.</b> {@link StoryProfile} overrides no {@code
 * qits.telemetry.max-*} key, so the 2,000-span figure below is the number a deployment really runs
 * with — read off {@code /store} by the bootstrap story before anything exported, and measured here.
 * A story that had shrunk the cap to three would have proved that a configured number is honoured;
 * this proves that the configuration a deployment ships is.
 *
 * <p><b>One batch, deliberately.</b> A real exporter would send this as four or five, and nothing
 * about the cap cares: the store enforces it per append, inside the bucket monitor. Sending one is
 * what makes the arithmetic exact — 2,001 in, 2,000 held, one evicted, and no window in which a
 * second batch could have raced the first.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class BufferEvictionIT {

  static final String CATEGORY = "buffer";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "The buffer is a window, and it says when the window has moved";

  static final String SLUG = Slugs.slug(STORY);

  /** This story's own bucket. No other story reads it, and no other story writes to it. */
  private static final String REPOSITORY = "uf-chatty";

  private static final String WORKSPACE = "uf-workspace-chatty";

  private static final String SOURCE_KEY = REPOSITORY + "/" + WORKSPACE;

  private static final String EXPORTING_SERVICE = "uf-chatty-service";

  /** {@code qits.telemetry.max-spans-per-workspace}'s shipped default — see the class javadoc. */
  private static final int SPAN_CAP = 2000;

  /** One more than the cap, which is the smallest batch that can evict anything. */
  private static final int BATCH = SPAN_CAP + 1;

  /** What a list answers with when the caller does not choose — {@code DEFAULT_LIMIT}. */
  private static final int DEFAULT_LIMIT = 200;

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A chatty service exports one batch of 2,001 spans — one more than the per-source cap this
      deployment ships with. Everything about what happens next is a deliberate choice this context
      made, and each half of it is visible from a different endpoint.

      The batch is relayed upstream FIRST, whole. The tee runs before the decode and before the
      store, so the span the local buffer is about to drop has already left this process — which is
      most of why the tee exists at all: the supervisor's window is a different window.

      The bucket then holds exactly the cap, oldest-out. The evicted span is not merely absent from
      a count: its trace page is empty, which is the sharpest form of the problem, because a fetch
      by identity is exactly where a reader would read "never happened" into "no longer held".

      `/store` is what makes the two sayable apart. `evictedSpans` moves by one, and a screen that
      reads it can say "this is what survived" instead of "this is what arrived". Zero would have
      been a promise; one is a warning, and the buffer owes the reader the warning.

      And the list is bounded on top of the buffer: 2,000 traces answer 200 rows with `total` and
      `truncated` beside them, so a screen says "showing 200 of 2,000" rather than implying that 200
      is the whole story. Two different limits, two different truths — the buffer's and the page's —
      and a reader has to be able to tell which one shortened the answer.
      """)
  @UserflowRunsAfter(TelemetryBootstrapIT.class)
  void theOldestSpanLeavesAndTheStoreSaysSo(Interactions story) {
    long tracesForwarded = StoryParent.forwardCount(StoryParent.TRACES);

    NetworkCapture.actor(StoryTarget.OPERATOR);
    long evictedBefore =
        number(
            StoryTarget.operator()
                .get(StoryTarget.STORE)
                .then()
                .statusCode(200)
                .body("caps.spansPerSource", equalTo(SPAN_CAP))
                .extract()
                .path("evictedSpans"));
    story
        .note(
            "the buffer this deployment ships holds 2,000 spans per source — read off /store rather"
                + " than assumed, because the number below is a measurement of it")
        .as("the-cap-is-the-shipped-one");

    NetworkCapture.actor(StoryTarget.EXPORTER);
    StoryTarget.otlp()
        .body(oneBatchOf(BATCH).toByteArray())
        .when()
        .post(StoryTarget.TRACE_INGEST)
        .then()
        .statusCode(200);
    story
        .note(
            "a chatty service exports 2,001 spans in one batch — one more than the bucket can hold")
        .as("an-oversized-batch-arrives");

    // Awaited before anything else is asserted, and before the story ends: the forward is
    // fire-and-forget on another thread, and the framework drains the parent's recording at story
    // end. It is also the claim itself — the copy left BEFORE the cap was applied, because the tee
    // runs ahead of the decode.
    assertEquals(
        200,
        StoryParent.awaitForward(StoryParent.TRACES, tracesForwarded).status(),
        "the whole batch is relayed upstream before the local cap is applied");
    story
        .note(
            "the whole batch went upstream first, whole: the tee runs before the decode and before"
                + " the store, so the span this buffer is about to drop has already reached the"
                + " supervisor — whose window is a different window")
        .as("the-copy-left-before-the-cap");

    NetworkCapture.actor(StoryTarget.OPERATOR);
    StoryTarget.operator()
        .get(StoryTarget.SOURCES)
        .then()
        .statusCode(200)
        .body("sources.find { it.key == '" + SOURCE_KEY + "' }.spans", equalTo(SPAN_CAP))
        .body(
            "sources.find { it.key == '" + SOURCE_KEY + "' }.services[0].name",
            equalTo(EXPORTING_SERVICE));
    story
        .note("the bucket holds exactly the cap: 2,000 of the 2,001 spans that arrived")
        .as("the-bucket-holds-the-cap");

    // The evicted span, fetched by identity. This is the sharp case: an empty trace page is where a
    // reader is most tempted to conclude "it never happened".
    StoryTarget.operator()
        .queryParam("repositoryId", REPOSITORY)
        .queryParam("workspaceId", WORKSPACE)
        .get(StoryTarget.trace(traceId(0)))
        .then()
        .statusCode(200)
        .body("trace.spans", hasSize(0))
        .body("trace.logs", hasSize(0));
    // …and the one after it, which is still there — so the empty page above is eviction and not a
    // route that answers empty for everything.
    StoryTarget.operator()
        .queryParam("repositoryId", REPOSITORY)
        .queryParam("workspaceId", WORKSPACE)
        .get(StoryTarget.trace(traceId(1)))
        .then()
        .statusCode(200)
        .body("trace.spans", hasSize(1))
        .body("trace.spans[0].name", equalTo("GET /chatty/1"));
    story
        .note(
            "the oldest span's trace page is EMPTY while its successor's is not — eviction is"
                + " oldest-out, and an empty page is the shape it takes to a reader who came with an"
                + " id")
        .as("the-oldest-trace-is-gone");

    long evictedAfter =
        number(
            StoryTarget.operator().get(StoryTarget.STORE).then().statusCode(200).extract()
                .path("evictedSpans"));
    assertEquals(
        evictedBefore + 1,
        evictedAfter,
        "one span over the cap is one eviction, counted — not a silent drop");
    story
        .note(
            "and /store says so: evictedSpans moved by exactly one. That counter is the whole"
                + " difference between \"this is what arrived\" and \"this is what survived\", and"
                + " an in-memory buffer owes its reader the warning")
        .as("the-eviction-is-counted");

    // The page limit is a second, unrelated bound, and a reader has to be able to tell them apart.
    StoryTarget.operator()
        .queryParam("repositoryId", REPOSITORY)
        .queryParam("workspaceId", WORKSPACE)
        .get(StoryTarget.TRACES)
        .then()
        .statusCode(200)
        .body("traces", hasSize(DEFAULT_LIMIT))
        .body("total", equalTo(SPAN_CAP))
        .body("truncated", equalTo(true));
    story
        .note(
            "the trace list is bounded on top of all that — 200 rows of 2,000, with total and"
                + " truncated beside them. Two limits, two truths: the buffer's and the page's, and"
                + " a screen has to say which one shortened the answer")
        .as("the-page-is-bounded-too");
  }

  /**
   * One export request carrying {@code count} spans, each on a trace of its own.
   *
   * <p>Every span gets its own trace id so the eviction is observable by identity rather than only
   * by a count — and the ids are 32 lowercase hex characters, a whole path segment, so {@code Labels}
   * rewrites them to {@code {digest}} and no label carries this run's arithmetic.
   */
  private static ExportTraceServiceRequest oneBatchOf(int count) {
    Span[] spans = new Span[count];
    for (int i = 0; i < count; i++) {
      spans[i] =
          TelemetryFixtures.spanBuilder(traceId(i), spanId(i), "GET /chatty/" + i).build();
    }
    return TelemetryFixtures.traceRequest(
        TelemetryFixtures.resource(EXPORTING_SERVICE, REPOSITORY, WORKSPACE), spans);
  }

  /** A 16-byte trace id, spelled as the 32 lowercase hex characters the wire carries. */
  private static String traceId(int index) {
    return String.format("%032x", index);
  }

  /** An 8-byte span id, spelled as 16 lowercase hex characters. */
  private static String spanId(int index) {
    return String.format("%016x", index);
  }

  private static long number(Object jsonNumber) {
    return ((Number) jsonNumber).longValue();
  }

  @AfterAll
  static void theEvictionStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    in(StoryTarget.EXPORTER, StoryTarget.exported(StoryTarget.TRACE_INGEST, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.STORE, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.SOURCES, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.TRACES, 200));
    // Two trace pages, one held and one evicted, and they are ONE arrow: the route and the status
    // are identical and only what came back differs. That distinction is a note, never a label.
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.trace(traceId(0)), 200));

    out(StoryParent.posted(StoryParent.TRACES));

    // SIX: five questions asked here and one copy sent on. A retry, a second forward of the same
    // batch, or a read that fanned out upstream would be a seventh.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 6);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        SLUG,
        List.of(StoryTarget.EXPORTER, StoryTarget.OPERATOR, StoryTarget.SERVICE));

    for (String step :
        List.of(
            "the-cap-is-the-shipped-one",
            "an-oversized-batch-arrives",
            "the-copy-left-before-the-cap",
            "the-bucket-holds-the-cap",
            "the-oldest-trace-is-gone",
            "the-eviction-is-counted",
            "the-page-is-bounded-too")) {
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
