package eu.wohlben.qits.telemetry.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.dto.StoredSpan;
import eu.wohlben.qits.telemetry.dto.TelemetryTraceSummaryDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit test of the trace-summary fold's route derivation — no Quarkus needed. Spans are
 * hand-built because the decoding is not what is under test here; {@code routeOf} reads the
 * attribute map the decoder fills.
 */
class TelemetryQueryServiceTracesTest {

  private static final String REPO = "repo-routes";
  private static final String WORKSPACE = "wt-routes";
  private static final String KEY = TelemetryStore.key(REPO, WORKSPACE);

  private TelemetryStore store;
  private TelemetryQueryService service;

  @BeforeEach
  void setUp() {
    store = new TelemetryStore();
    service = new TelemetryQueryService();
    service.store = store;
  }

  private static StoredSpan span(
      String traceId,
      String spanId,
      String parentSpanId,
      String name,
      long startNanos,
      Map<String, String> attributes) {
    return new StoredSpan(
        traceId,
        spanId,
        parentSpanId,
        "svc",
        "scope",
        name,
        "SERVER",
        startNanos,
        startNanos + 250_000_000L,
        "UNSET",
        "",
        attributes,
        List.of(),
        Map.of(
            "service.name",
            "svc",
            "qits.repository.id",
            REPO,
            "qits.workspace.id",
            WORKSPACE),
        System.currentTimeMillis());
  }

  private TelemetryTraceSummaryDto summaryOf(String traceId) {
    return service
        .tracesIn(KEY, null, 0, null, TelemetryQueryService.SpanSort.RECENT, 100)
        .items()
        .stream()
        .filter(row -> row.traceId().equals(traceId))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void theTemplatedRouteWinsOverTheConcretePath() {
    store.addSpans(
        List.of(
            span(
                "trace-templated",
                "s1",
                "",
                "GET /users/{id}",
                1_000_000_000L,
                Map.of("http.route", "/users/{id}", "url.path", "/users/42"))));

    assertEquals("/users/{id}", summaryOf("trace-templated").rootRoute());
  }

  @Test
  void theConcretePathStandsInWhenNoTemplatedRouteExists() {
    store.addSpans(
        List.of(
            span(
                "trace-path",
                "s1",
                "",
                "GET /users/42",
                1_000_000_000L,
                Map.of("url.path", "/users/42"))));

    assertEquals("/users/42", summaryOf("trace-path").rootRoute());
  }

  @Test
  void aRootWithNeitherAttributeAnswersNullRatherThanAGuess() {
    store.addSpans(
        List.of(span("trace-bare", "s1", "", "consume orders", 1_000_000_000L, Map.of())));

    assertNull(summaryOf("trace-bare").rootRoute());
  }

  @Test
  void aBlankRouteReadsAsAbsent() {
    store.addSpans(
        List.of(
            span(
                "trace-blank",
                "s1",
                "",
                "GET /users/42",
                1_000_000_000L,
                Map.of("http.route", "  ", "url.path", "/users/42"))));

    assertEquals("/users/42", summaryOf("trace-blank").rootRoute());
  }

  /**
   * With no buffered root the earliest span stands in, and the route follows the same stand-in the
   * name already came from — the group a trace lands in always matches what its row says.
   */
  @Test
  void aRootlessTraceTakesItsRouteFromTheStandInSpan() {
    store.addSpans(
        List.of(
            span(
                "trace-rootless",
                "s2",
                "s-evicted",
                "later child",
                3_000_000_000L,
                Map.of("http.route", "/late/{id}")),
            span(
                "trace-rootless",
                "s1",
                "s-evicted",
                "earliest child",
                1_000_000_000L,
                Map.of("http.route", "/early/{id}"))));

    TelemetryTraceSummaryDto summary = summaryOf("trace-rootless");
    assertTrue(summary.rootMissing());
    assertEquals("earliest child", summary.rootName());
    assertEquals("/early/{id}", summary.rootRoute());
  }
}
