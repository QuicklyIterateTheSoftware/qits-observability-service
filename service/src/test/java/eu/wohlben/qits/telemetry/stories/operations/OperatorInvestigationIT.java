package eu.wohlben.qits.telemetry.stories.operations;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
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
 * <b>One investigation, from "something is wrong" to a stack trace and the release it came from.</b>
 *
 * <p>Every other story here is about one mechanism. This one is about the surface as a
 * <em>sequence</em>: an operator who knows only that a platform service is unhappy, and the seven
 * reads that take them from there to the line of code. It is the reason the buffer holds three
 * signals rather than one — a span says what failed, a log says what it said while failing, and a
 * metric says whether it is failing for everybody.
 *
 * <h2>The bucket is not a workspace, and that is the point</h2>
 *
 * <p>Every other story in this catalogue exports with the {@code qits.repository.id} / {@code
 * qits.workspace.id} pair, so its bucket is a workspace. A <b>platform service</b> stamps neither:
 * it exports {@code service.name} and the three identity attributes cd injects, and it lands in the
 * {@code service.name} tier — which is where the overwhelming majority of telemetry on this platform
 * actually lives, and which <b>no repository/workspace pair can spell</b>.
 *
 * <p>So this story reaches its bucket the only way a caller may: {@code /sources} hands out an
 * opaque {@code key} and every read hands it back as {@code ?source=}. Nothing here constructs one —
 * the key is read out of the listing at runtime and threaded through, which is exactly the contract
 * ("no caller constructs a key, so the tiers can change without a wire change") and is unprovable by
 * a test that spells the key it expects.
 *
 * <h2>Why the canary batch and not a fixture of its own</h2>
 *
 * <p>{@link TelemetryFixtures#canaryLogsRequest} is the producer's-eye view: one batch shaped like a
 * platform Quarkus service's OTel logging bridge, carrying {@code service.version}, {@code
 * deployment.environment.name} and {@code service.instance.id} — the three attributes cd stamps into
 * every container it deploys, and the ones that separate "this service is failing" from "this
 * release is failing". {@code CanaryLogStreamTest} answers the same questions against the JVM suite;
 * what this story adds is the packaged process, the operator's headers, and the order.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class OperatorInvestigationIT {

  static final String CATEGORY = "operations";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY =
      "An operator follows one failing release from the source list to the stack trace";

  static final String SLUG = Slugs.slug(STORY);

  private static final String INFO_BODY = "canary handled GET /canary";

  private static final String ERROR_BODY = "canary failed to reach the widget service";

  /** Below the {@code slow-spans} default of 500ms: a canary is fast, and 42ms is still the slowest. */
  private static final int SLOW_THRESHOLD_MS = 10;

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A platform service — one of qits' own, deployed by cd with its version, environment and
      instance stamped into every export — starts failing. An operator has a name and nothing else.

      They open the SOURCE LIST first, because it is the only place a bucket key exists. This
      service's telemetry is filed under its `service.name`, not under any workspace, and no
      repository/workspace pair can address it: the listing hands out an opaque key and every read
      below hands the same key back. Nothing in this story constructs one.

      From there it is a sequence, and each read answers a question the previous one raised. WHICH
      REQUEST? — the trace list, one row per buffered trace. WHAT HAPPENED IN IT? — the trace page,
      the span and, beside it, the records the service logged while serving it. That correlation is
      by first-class ids rather than by a convention parsed out of formatted text, and it is most of
      why OTLP was chosen over log scraping.

      WHAT ACTUALLY BROKE? — the errors feed, which carries the exception's type, message and whole
      stack trace as structured attributes. WHICH RELEASE? — the resource identity riding on every
      record: a version, an environment and an instance id, so "the service is broken" can become
      "this release is broken", which is a different conversation and a different fix.

      Then the two lenses that say whether it is one request or all of them: the log tail with a
      severity FLOOR, applied by the endpoint rather than by the screen — because this endpoint
      truncates, and filtering a page already cut to 200 would show "the errors among the last 200
      records" while reading as "the last 200 errors" — and the metrics, latest point per series,
      unbounded because the store keeps one point per series and caps the series count.

      Seven reads, one bucket, and one arrow per route on the diagram. What each of them asked for
      travelled as a query, which no incoming label carries — so the picture is the SHAPE of an
      investigation, and the notes are its content.
      """)
  @UserflowRunsAfter(TelemetryBootstrapIT.class)
  void anOperatorReadsFromTheSourceListDownToTheStackTrace(Interactions story) {
    long tracesForwarded = StoryParent.forwardCount(StoryParent.TRACES);
    long logsForwarded = StoryParent.forwardCount(StoryParent.LOGS);
    long metricsForwarded = StoryParent.forwardCount(StoryParent.METRICS);

    // --- the producer. Three signals, one resource, no qits.* pair: this is what a platform
    // service exports, and it is why the service.name tier exists at all.
    NetworkCapture.actor(StoryTarget.EXPORTER);
    StoryTarget.otlp()
        .body(TelemetryFixtures.canaryTraceRequest().toByteArray())
        .when()
        .post(StoryTarget.TRACE_INGEST)
        .then()
        .statusCode(200);
    StoryTarget.otlp()
        .body(TelemetryFixtures.canaryLogsRequest(INFO_BODY, ERROR_BODY).toByteArray())
        .when()
        .post(StoryTarget.LOG_INGEST)
        .then()
        .statusCode(200);
    StoryTarget.otlp()
        .body(
            TelemetryFixtures.metricsRequest(TelemetryFixtures.CANARY_SERVICE, null, null, 512d, 7L)
                .toByteArray())
        .when()
        .post(StoryTarget.METRIC_INGEST)
        .then()
        .statusCode(200);
    story
        .note(
            "a platform service exports all three signals for one request: the span it served, the"
                + " records it logged inside it, and the counters it reports on a timer")
        .as("a-platform-service-reports");

    // Awaited here, before any read and before the story ends: three exports, three forwards, and
    // the framework drains the parent's recording at story end.
    assertEquals(200, StoryParent.awaitForward(StoryParent.TRACES, tracesForwarded).status());
    assertEquals(200, StoryParent.awaitForward(StoryParent.LOGS, logsForwarded).status());
    assertEquals(200, StoryParent.awaitForward(StoryParent.METRICS, metricsForwarded).status());
    story
        .note("and all three go on to the parent qits, each on its own signal route")
        .as("all-three-signals-are-teed");

    // --- (1) which bucket? The key is READ, never built.
    NetworkCapture.actor(StoryTarget.OPERATOR);
    String source =
        StoryTarget.operator()
            .get(StoryTarget.SOURCES)
            .then()
            .statusCode(200)
            .body(
                "sources.find { it.label == '"
                    + TelemetryFixtures.CANARY_SERVICE
                    + "' }.kind",
                equalTo("SERVICE"))
            // A service-tier bucket has no workspace lens at all, which is the fact that forces the
            // key: these two are null and no pair could have addressed it.
            .body(
                "sources.find { it.label == '"
                    + TelemetryFixtures.CANARY_SERVICE
                    + "' }.repositoryId",
                nullValue())
            .body(
                "sources.find { it.label == '" + TelemetryFixtures.CANARY_SERVICE + "' }.spans",
                equalTo(1))
            .body(
                "sources.find { it.label == '" + TelemetryFixtures.CANARY_SERVICE + "' }.logs",
                equalTo(2))
            .body(
                "sources.find { it.label == '"
                    + TelemetryFixtures.CANARY_SERVICE
                    + "' }.metricSeries",
                equalTo(2))
            .extract()
            .path("sources.find { it.label == '" + TelemetryFixtures.CANARY_SERVICE + "' }.key");
    story
        .note(
            "the source listing is where an investigation starts, because it is the only place a"
                + " bucket key exists: this service's telemetry is filed under its service.name and"
                + " has no workspace lens at all — repositoryId and workspaceId are null, and no"
                + " pair could have addressed it")
        .as("the-key-comes-from-the-listing");

    // --- (2) which request?
    StoryTarget.operator()
        .queryParam("source", source)
        .get(StoryTarget.TRACES)
        .then()
        .statusCode(200)
        .body("traces", hasSize(1))
        .body("traces[0].traceId", equalTo(TelemetryFixtures.CANARY_TRACE_ID))
        .body("traces[0].rootName", equalTo("GET /canary"))
        .body("traces[0].rootService", equalTo(TelemetryFixtures.CANARY_SERVICE))
        .body("traces[0].spanCount", equalTo(1))
        .body("truncated", equalTo(false));
    story
        .note(
            "the trace list, addressed with the key the listing handed out — one row per buffered"
                + " trace, so the page below is reachable without knowing an id in advance")
        .as("the-trace-list-names-the-request");

    // --- (3) what happened inside it? The correlation OTLP was chosen for.
    StoryTarget.operator()
        .queryParam("source", source)
        .get(StoryTarget.trace(TelemetryFixtures.CANARY_TRACE_ID))
        .then()
        .statusCode(200)
        .body("trace.spans", hasSize(1))
        .body("trace.spans[0].spanId", equalTo(TelemetryFixtures.CANARY_SPAN_ID))
        .body("trace.spans[0].name", equalTo("GET /canary"))
        .body("trace.logs", hasSize(2))
        .body("trace.logs[0].body", equalTo(INFO_BODY))
        .body("trace.logs[1].body", equalTo(ERROR_BODY))
        .body("trace.logs[1].severityText", equalTo("ERROR"));
    story
        .note(
            "the trace page holds the span and, beside it, both records the service logged while"
                + " serving it — correlated by first-class ids rather than by a convention parsed"
                + " out of formatted text, which is most of why OTLP beats scraping a log tail")
        .as("the-trace-page-correlates-both-signals");

    // --- (4) what broke, structurally?
    StoryTarget.operator()
        .queryParam("source", source)
        .get(StoryTarget.ERRORS)
        .then()
        .statusCode(200)
        .body("groups", hasSize(1))
        .body("groups[0].traceId", equalTo(TelemetryFixtures.CANARY_TRACE_ID))
        .body("groups[0].serviceName", equalTo(TelemetryFixtures.CANARY_SERVICE))
        // The INFO record is not in it: a feed that grouped by trace without filtering severity
        // would carry every record the failing request also logged.
        .body("groups[0].errorLogs", hasSize(1))
        .body("groups[0].errorLogs[0].body", equalTo(ERROR_BODY))
        .body(
            "groups[0].errorLogs[0].attributes.'exception.type'",
            equalTo(TelemetryFixtures.CANARY_EXCEPTION_TYPE))
        .body(
            "groups[0].errorLogs[0].attributes.'exception.stacktrace'",
            containsString("CanaryResource.callWidgets(CanaryResource.java:42)"))
        // --- (5) which release? The three attributes cd stamps, riding on the record itself.
        .body(
            "groups[0].errorLogs[0].resourceAttributes.'service.version'",
            equalTo(TelemetryFixtures.CANARY_VERSION))
        .body(
            "groups[0].errorLogs[0].resourceAttributes.'deployment.environment.name'",
            equalTo(TelemetryFixtures.CANARY_ENVIRONMENT))
        .body(
            "groups[0].errorLogs[0].resourceAttributes.'service.instance.id'",
            equalTo(TelemetryFixtures.CANARY_INSTANCE_ID));
    story
        .note(
            "the errors feed carries the exception whole — type, message and the real stack trace,"
                + " as structured attributes rather than as a formatted string — and the INFO record"
                + " is not in it")
        .as("the-stack-trace-arrives-whole");
    story
        .note(
            "and the record names the RELEASE that emitted it: version, environment and instance,"
                + " the three attributes cd stamps into every container. \"The service is broken\""
                + " and \"this release is broken\" are different conversations and different fixes")
        .as("the-release-is-nameable");

    // --- (6) is it one request or all of them? The severity floor is the endpoint's, not the
    // screen's, because this endpoint truncates.
    StoryTarget.operator()
        .queryParam("source", source)
        .queryParam("minSeverity", "ERROR")
        .get(StoryTarget.LOGS)
        .then()
        .statusCode(200)
        .body("logs", hasSize(1))
        .body("logs[0].body", equalTo(ERROR_BODY))
        .body("logs[0].severityNumber", equalTo(17))
        .body("total", equalTo(1));
    story
        .note(
            "the log tail with a severity FLOOR — warnings and worse, applied by the endpoint"
                + " rather than by the screen, because this endpoint truncates and filtering a page"
                + " already cut to 200 would read as \"the last 200 errors\" while showing \"the"
                + " errors among the last 200 records\"")
        .as("the-floor-is-the-endpoints");

    StoryTarget.operator()
        .queryParam("source", source)
        .queryParam("thresholdMs", SLOW_THRESHOLD_MS)
        .get(StoryTarget.SLOW_SPANS)
        .then()
        .statusCode(200)
        .body("spans", hasSize(1))
        .body("spans[0].spanId", equalTo(TelemetryFixtures.CANARY_SPAN_ID))
        .body("spans[0].durationMs", equalTo(42));
    story
        .note(
            "the slow-span lens over the same bucket, asked at 10ms because a canary is fast — the"
                + " default is 500ms, which is a question about a service under load rather than"
                + " about this one")
        .as("the-slow-lens-is-the-same-bucket");

    // --- (7) and the third signal, which is the only one that can say "for everybody".
    StoryTarget.operator()
        .queryParam("source", source)
        .get(StoryTarget.METRICS)
        .then()
        .statusCode(200)
        .body("metrics", hasSize(2))
        .body("metrics.name", hasItem("http.server.requests"))
        .body("metrics.name", hasItem("jvm.memory.used"))
        .body("metrics.find { it.name == 'jvm.memory.used' }.unit", equalTo("By"));
    story
        .note(
            "and the metrics: the latest point of every series, with no limit — the store keeps one"
                + " point per series and caps the series count, so the answer is bounded by"
                + " construction and there is no history to page through")
        .as("the-metrics-are-latest-point-only");
  }

  @AfterAll
  static void theInvestigationStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);

    in(StoryTarget.EXPORTER, StoryTarget.exported(StoryTarget.TRACE_INGEST, 200));
    in(StoryTarget.EXPORTER, StoryTarget.exported(StoryTarget.LOG_INGEST, 200));
    in(StoryTarget.EXPORTER, StoryTarget.exported(StoryTarget.METRIC_INGEST, 200));

    // The seven reads of one investigation. Which bucket each addressed travelled as ?source=, and
    // no incoming label carries a query — so this is the shape of an investigation rather than its
    // content, and the content is in the notes.
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.SOURCES, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.TRACES, 200));
    in(
        StoryTarget.OPERATOR,
        StoryTarget.read(StoryTarget.trace(TelemetryFixtures.CANARY_TRACE_ID), 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.ERRORS, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.LOGS, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.SLOW_SPANS, 200));
    in(StoryTarget.OPERATOR, StoryTarget.read(StoryTarget.METRICS, 200));

    out(StoryParent.posted(StoryParent.TRACES));
    out(StoryParent.posted(StoryParent.LOGS));
    out(StoryParent.posted(StoryParent.METRICS));

    // THIRTEEN: three exports, three copies on, seven reads. A read that had fanned out to the
    // parent to answer a question would be a fourteenth, and it is the one thing this surface must
    // never do — see the reading story, which makes that claim on its own.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 13);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG,
        SLUG,
        List.of(StoryTarget.EXPORTER, StoryTarget.OPERATOR, StoryTarget.SERVICE));

    for (String step :
        List.of(
            "a-platform-service-reports",
            "all-three-signals-are-teed",
            "the-key-comes-from-the-listing",
            "the-trace-list-names-the-request",
            "the-trace-page-correlates-both-signals",
            "the-stack-trace-arrives-whole",
            "the-release-is-nameable",
            "the-floor-is-the-endpoints",
            "the-slow-lens-is-the-same-bucket",
            "the-metrics-are-latest-point-only")) {
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
