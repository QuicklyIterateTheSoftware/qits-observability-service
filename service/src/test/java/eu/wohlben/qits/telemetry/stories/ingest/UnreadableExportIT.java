package eu.wohlben.qits.telemetry.stories.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.TelemetryBootstrapIT;
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
 * <b>A batch this receiver cannot read is refused here and relayed anyway — and that is the
 * design.</b>
 *
 * <p>{@code OtelReceiverResource} forwards <em>before</em> it parses. The ordering is one line of
 * code and it is a decision about what a middle tier is for: the tee's promise is "byte-verbatim",
 * so the parent receives what the exporter sent rather than what this process managed to decode.
 * The consequence is the one drawn here — a payload this buffer rejects still reaches the
 * supervisor, whose protobuf bindings, whose limits and whose judgement are its own.
 *
 * <p>It reads like a leak and it is the opposite of one. A receiver that adjudicated for its parent
 * would make every future decode difference — a newer OTLP field, a signal this version does not
 * know — into a silent hole in the tier above, and the hole would be invisible from both ends. What
 * this story pins is that the local refusal is <b>complete</b> (nothing is half-stored) and the
 * relay is <b>unconditional</b>, so the two tiers can disagree without either losing data.
 *
 * <p>Deliberately a small story with a big claim. It is also the cheap half of a pair: {@code
 * CanaryLogStreamTest} pins the same 400 against the JVM suite, where there is no parent to relay
 * to, and the byte ceiling's 413 (including the gzip bomb, which is refused at the same number the
 * HTTP layer would have given) is asserted there rather than here — a 64 MiB inflation is a
 * memory-shaped test, not a story about a network.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class UnreadableExportIT {

  static final String CATEGORY = "ingest";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "A batch this receiver cannot read is still relayed to the parent";

  static final String SLUG = Slugs.slug(STORY);

  /**
   * Not a protobuf message and unambiguously so: a valid {@code Export*ServiceRequest} starts with
   * field tag {@code 0x0a}. The same four bytes {@code CanaryLogStreamTest} refuses on the JVM.
   */
  private static final byte[] NOT_PROTOBUF = {(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x13};

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      An exporter posts bytes that are not an OTLP message at all — a truncated batch, a client
      speaking a format this build does not know, a proxy that mangled a body.

      This receiver refuses it, with a 400 that says why. The refusal is COMPLETE: the buffer holds
      exactly what it held before, byte for byte and source for source, because nothing is stored
      until the whole message has parsed. A half-decoded batch would be the worst possible outcome
      here, since the reader downstream has no way to know which half it is looking at.

      And the copy goes upstream regardless, because the tee runs before the decode. That is not an
      oversight: the promise is byte-verbatim relay, so the parent gets what the EXPORTER sent, not
      what this process could make of it. Which means the two tiers are free to disagree — a newer
      field, a signal this version does not know, a limit set differently — without either of them
      losing the batch. A receiver that adjudicated for its parent would turn every such difference
      into a silent hole in the tier above.

      The exporter, meanwhile, is told the truth about the tier it can see: 400, here, now. It is
      never told anything about the parent, and it never should be.
      """)
  @UserflowRunsAfter(TelemetryBootstrapIT.class)
  void anUnreadableBatchIsRefusedHereAndPassedOnAnyway(Interactions story) {
    long tracesForwarded = StoryParent.forwardCount(StoryParent.TRACES);

    NetworkCapture.actor(StoryTarget.OPERATOR);
    long sourcesBefore =
        number(
            StoryTarget.operator().get(StoryTarget.STORE).then().statusCode(200).extract()
                .path("sourceCount"));
    long bytesBefore =
        number(
            StoryTarget.operator().get(StoryTarget.STORE).then().statusCode(200).extract()
                .path("totalBytes"));

    NetworkCapture.actor(StoryTarget.EXPORTER);
    String refusal =
        StoryTarget.otlp()
            .body(NOT_PROTOBUF)
            .when()
            .post(StoryTarget.TRACE_INGEST)
            .then()
            .statusCode(400)
            .extract()
            .asString();
    assertTrue(
        refusal.contains("Malformed OTLP protobuf payload"),
        "a refused batch must say what was wrong with it: " + refusal);
    story
        .note(
            "an exporter posts bytes that are not an OTLP message, and the receiver refuses them"
                + " with a 400 that says why")
        .as("an-unreadable-batch-is-refused");

    // Awaited before the story ends, like every other forward here — and this one is the claim.
    assertEquals(
        200,
        StoryParent.awaitForward(StoryParent.TRACES, tracesForwarded).status(),
        "the tee runs before the decode, so the parent receives what the exporter sent");
    story
        .note(
            "the parent got it anyway: the tee runs BEFORE the decode, so the supervisor receives"
                + " what the exporter sent rather than what this process could make of it — and the"
                + " two tiers stay free to disagree without either losing the batch")
        .as("the-copy-went-up-regardless");

    NetworkCapture.actor(StoryTarget.OPERATOR);
    long sourcesAfter =
        number(
            StoryTarget.operator().get(StoryTarget.STORE).then().statusCode(200).extract()
                .path("sourceCount"));
    long bytesAfter =
        number(
            StoryTarget.operator().get(StoryTarget.STORE).then().statusCode(200).extract()
                .path("totalBytes"));
    assertEquals(sourcesBefore, sourcesAfter, "a refused batch must not create a bucket");
    assertEquals(bytesBefore, bytesAfter, "a refused batch must not leave a byte behind");
    story
        .note(
            "and the buffer holds exactly what it held: no new source, not one byte more. The"
                + " refusal is complete, because a half-decoded batch is the one answer a reader"
                + " downstream could not interpret")
        .as("the-buffer-is-untouched");
  }

  private static long number(Object jsonNumber) {
    return ((Number) jsonNumber).longValue();
  }

  @AfterAll
  static void theUnreadableExportStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.EXPORTER,
        StoryTarget.SERVICE,
        StoryTarget.exported(StoryTarget.TRACE_INGEST, 400));
    // The four /store reads are ONE arrow: same route, same status, and the whole point is that
    // what came back was identical.
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.OPERATOR,
        StoryTarget.SERVICE,
        StoryTarget.read(StoryTarget.STORE, 200));
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.HTTP,
        StoryTarget.SERVICE,
        StoryParent.SERVICE_NAME,
        StoryParent.posted(StoryParent.TRACES));

    // THREE. One refusal, one look at the buffer, one copy sent on — and nothing else. A retry of
    // the rejected batch, or a forward of the other two signals, would be a fourth.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 3);

    for (String step :
        List.of(
            "an-unreadable-batch-is-refused",
            "the-copy-went-up-regardless",
            "the-buffer-is-untouched")) {
      ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, step);
    }
  }
}
