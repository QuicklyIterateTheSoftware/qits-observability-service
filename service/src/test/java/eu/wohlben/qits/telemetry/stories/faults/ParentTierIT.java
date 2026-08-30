package eu.wohlben.qits.telemetry.stories.faults;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>Three ways the supervisor can fail this receiver, and none of them reaches the exporter.</b>
 *
 * <p>"Telemetry is best-effort upstream" is a sentence in {@code OtelForwarder}'s javadoc. It is
 * also the single most consequential promise this service makes, because the thing on the other end
 * of an export is a workspace container's SDK: if a supervisor's outage propagated back to it, an
 * outage of the telemetry plane would become an outage of everything being observed. The promise is
 * therefore not "the forward usually works" but <b>"the forward is never in the exporter's way"</b>,
 * and that has to hold whether the parent is slow, refusing or silent.
 *
 * <p>Those three arrive at the forwarder by three different routes, and the outgoing labels are what
 * tell them apart — {@code -> 200} after a wait, {@code -> 503}, and {@code -> no answer}, which is
 * what the parent's own recording knows and no status could have said. To the exporter all three are
 * a 200 and a stored record, which is the story.
 *
 * <p><b>What is NOT here, honestly.</b> There is no queue and no retry: the forward is one {@code
 * sendAsync} whose failure is a debug log. A copy the parent refused is <em>gone</em> — this
 * service does not hold it for later and does not tell anybody it dropped it. That is a real
 * property with a real cost, and the counted assertion at the end of the story is what states it:
 * one export, exactly one attempt.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class ParentTierIT {

  static final String CATEGORY = "faults";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY =
      "A parent that is slow, refusing or silent never reaches the exporter";

  static final String SLUG = Slugs.slug(STORY);

  private static final String REPOSITORY = "uf-supervised";

  private static final String WORKSPACE = "uf-workspace-supervised";

  private static final String EXPORTING_SERVICE = "uf-supervised-service";

  /** The slow arm's export. Sixteen bytes of trace id and eight of span id, as the wire spells them. */
  private static final String SLOW_TRACE = "aa11aa11aa11aa11aa11aa11aa11aa11";

  private static final String SLOW_SPAN = "aa11aa11aa11aa11";

  /** The refused arm's export, whose span and log are what the operator reads back at the end. */
  private static final String REFUSED_TRACE = "bb22bb22bb22bb22bb22bb22bb22bb22";

  private static final String REFUSED_SPAN = "bb22bb22bb22bb22";

  private static final String SILENT_LOG = "the supervised service kept logging while nobody listened";

  /** How long the slow parent takes to answer. Long enough that "did not wait" is not a stopwatch. */
  private static final long PARENT_DELAY_MILLIS = 2_000;

  /** The ceiling the ingest response must come back under while the parent is still thinking. */
  private static final long INGEST_BUDGET_MILLIS = 1_000;

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  /**
   * Belt as well as braces. Every arming below is already in a {@code try}/{@code finally}; a fault
   * that outlived this story would be a broken parent in somebody else's diagram, and the two would
   * look exactly alike.
   */
  @AfterEach
  void theParentAnswersNormallyAgain() {
    StoryParent.answerNormally();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      The parent qits is a supervisor, not a dependency. Three times over, it fails — and three
      times over, the container that exported into this receiver cannot tell.

      The parent is SLOW. It takes two seconds to acknowledge a batch, and the exporter's own
      request comes back in a fraction of that: the forward is `sendAsync` on another thread and
      the ingest thread never joins it. This is the arm that a JVM test cannot state, because a
      forward that blocked would still be fast against a stub on the same heap; only a launched
      process beside a deliberately slow far side makes "did not wait" a measurement.

      The parent REFUSES. It is up and answers 503, and the copy is simply lost — the record is in
      this buffer and readable, the exporter got its 200, and the outgoing arrow carries the 503 so
      the diagram does not pretend the copy landed.

      The parent GOES SILENT. It accepts the connection and says nothing, which reaches the
      forwarder as an IOException rather than as a status it read. Same 200 to the exporter, same
      stored record, and a different arrow: `no answer`, which the parent's own recording knows and
      no status could have told us.

      And the count at the end is the part with a cost attached. Three exports, three forwards —
      no retry, no queue, no dead-letter. A copy the supervisor refused is gone, and this service
      neither holds it nor reports having dropped it. That is the price of "the forward is never in
      the exporter's way", and it is stated here rather than discovered later.
      """)
  @UserflowRunsAfter(TelemetryBootstrapIT.class)
  void everyWayTheParentFailsStopsAtThisProcess(Interactions story) {
    long tracesForwarded = StoryParent.forwardCount(StoryParent.TRACES);
    long logsForwarded = StoryParent.forwardCount(StoryParent.LOGS);

    // --- (a) the slow parent. Armed for the traces route only: an arm at /v1/ would catch every
    // signal, and the arms below have to stay separable.
    NetworkCapture.actor(StoryTarget.EXPORTER);
    long elapsed;
    try {
      StoryParent.answerSlowly(StoryParent.TRACES, PARENT_DELAY_MILLIS);
      long started = System.currentTimeMillis();
      StoryTarget.otlp()
          .body(
              TelemetryFixtures.okTraceRequest(
                      EXPORTING_SERVICE, REPOSITORY, WORKSPACE, SLOW_TRACE, SLOW_SPAN)
                  .toByteArray())
          .when()
          .post(StoryTarget.TRACE_INGEST)
          .then()
          .statusCode(200);
      elapsed = System.currentTimeMillis() - started;
      assertTrue(
          elapsed < INGEST_BUDGET_MILLIS,
          "ingest must not wait for the parent: the export took "
              + elapsed
              + "ms against a parent taking "
              + PARENT_DELAY_MILLIS
              + "ms");
      // Awaited inside the arm, because the arm is what makes it slow — and before the story ends,
      // because the framework drains the parent's recording there.
      assertEquals(200, StoryParent.awaitForward(StoryParent.TRACES, tracesForwarded).status());
    } finally {
      StoryParent.answerNormally();
    }
    story
        .note(
            "the parent takes two seconds to acknowledge the batch and the exporter is answered in"
                + " "
                + elapsed
                + "ms: the forward is sendAsync on another thread, and the ingest thread never"
                + " joins it")
        .as("a-slow-parent-is-not-waited-for");

    // --- (b) the parent that is up and cannot take the copy.
    try {
      StoryParent.refuse(StoryParent.TRACES);
      StoryTarget.otlp()
          .body(
              TelemetryFixtures.errorTraceRequest(
                      EXPORTING_SERVICE, REPOSITORY, WORKSPACE, REFUSED_TRACE, REFUSED_SPAN)
                  .toByteArray())
          .when()
          .post(StoryTarget.TRACE_INGEST)
          .then()
          .statusCode(200);
      assertEquals(
          StoryParent.REFUSED_STATUS,
          StoryParent.awaitForward(StoryParent.TRACES, tracesForwarded + 1).status(),
          "the parent refused the copy, and the diagram must carry the status it refused with");
    } finally {
      StoryParent.answerNormally();
    }
    story
        .note(
            "the parent refuses the next batch with a 503 — and the exporter is told 200 all the"
                + " same, because a supervisor's outage must not become an outage of everything it"
                + " supervises")
        .as("a-refusing-parent-is-still-a-200");

    // --- (c) the parent that accepts and says nothing. A different arm of the same failure: it
    // reaches the forwarder as an IOException rather than as a status.
    try {
      StoryParent.hangUp(StoryParent.LOGS);
      StoryTarget.otlp()
          .body(
              TelemetryFixtures.logsRequest(
                      EXPORTING_SERVICE,
                      REPOSITORY,
                      WORKSPACE,
                      SeverityNumber.SEVERITY_NUMBER_WARN,
                      SILENT_LOG,
                      REFUSED_TRACE)
                  .toByteArray())
          .when()
          .post(StoryTarget.LOG_INGEST)
          .then()
          .statusCode(200);
      assertTrue(
          StoryParent.awaitForward(StoryParent.LOGS, logsForwarded).unanswered(),
          "the parent took the connection and never answered it");
    } finally {
      StoryParent.answerNormally();
    }
    story
        .note(
            "and a parent that accepts the connection and then says nothing is the same 200 to the"
                + " exporter and a different arrow on the diagram — `no answer`, which the parent's"
                + " own recording knows and no status could have told us")
        .as("a-silent-parent-is-also-a-200");

    // --- what the buffer has, which is everything. The local store never depended on any of it.
    NetworkCapture.actor(StoryTarget.OPERATOR);
    StoryTarget.operator()
        .queryParam("repositoryId", REPOSITORY)
        .queryParam("workspaceId", WORKSPACE)
        .get(StoryTarget.trace(REFUSED_TRACE))
        .then()
        .statusCode(200)
        .body("trace.spans", hasSize(1))
        .body("trace.spans[0].spanId", equalTo(REFUSED_SPAN))
        .body("trace.logs", hasSize(1))
        .body("trace.logs[0].body", equalTo(SILENT_LOG));
    StoryTarget.operator()
        .queryParam("repositoryId", REPOSITORY)
        .queryParam("workspaceId", WORKSPACE)
        .queryParam("minSeverity", "WARN")
        .get(StoryTarget.LOGS)
        .then()
        .statusCode(200)
        .body("logs", hasSize(1))
        .body("logs[0].body", equalTo(SILENT_LOG))
        .body("logs[0].severityText", equalTo("WARN"));
    story
        .note(
            "everything the parent refused or ignored is here, readable: the record the supervisor"
                + " never took is the record an operator reads on this tier")
        .as("the-local-buffer-kept-everything");

    // --- and the count, which is the honest half. An absence is an assertion, never an edge.
    assertEquals(
        tracesForwarded + 2,
        StoryParent.forwardCount(StoryParent.TRACES),
        "two exports, two forwards — nothing retried the one the parent refused");
    assertEquals(
        logsForwarded + 1,
        StoryParent.forwardCount(StoryParent.LOGS),
        "one export, one forward — nothing retried the one the parent never answered");
    story
        .note(
            "three exports, three forwards, and no fourth: there is no queue, no retry and no"
                + " dead-letter. A copy the supervisor refused is GONE, and this service neither"
                + " holds it nor reports having dropped it — the price of never being in the"
                + " exporter's way")
        .as("nothing-was-retried");
  }

  @AfterAll
  static void theParentTierStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    // In: two trace exports (one arrow — same route, same status, and that IS the claim) and one
    // log export, plus the operator's two reads.
    in(StoryTarget.EXPORTER, StoryTarget.exported(StoryTarget.TRACE_INGEST, 200));
    in(StoryTarget.EXPORTER, StoryTarget.exported(StoryTarget.LOG_INGEST, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.trace(REFUSED_TRACE), 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.LOGS, 200));

    // Out: three forwards, three different fates, and the labels are what say so.
    out(StoryParent.posted(StoryParent.TRACES));
    out(StoryParent.posted(StoryParent.TRACES, StoryParent.REFUSED_STATUS));
    out(StoryParent.unanswered(StoryParent.LOGS));

    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 7);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        SLUG,
        List.of(StoryTarget.EXPORTER, StoryTarget.OPERATOR, StoryTarget.SERVICE));

    for (String step :
        List.of(
            "a-slow-parent-is-not-waited-for",
            "a-refusing-parent-is-still-a-200",
            "a-silent-parent-is-also-a-200",
            "the-local-buffer-kept-everything",
            "nothing-was-retried")) {
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
