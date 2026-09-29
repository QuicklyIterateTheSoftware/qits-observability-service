package eu.wohlben.qits.telemetry.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.telemetry.dto.MetricPoint;
import eu.wohlben.qits.telemetry.dto.SpanEvent;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.dto.StoredSpan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The matcher, field by op, against records built by hand. Plain JUnit: {@link TelemetryFilter}
 * needs no Quarkus. The table it implements is in README "The live stream".
 */
class TelemetryFilterTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
  private static final String SPAN_ID = "00f067aa0ba902b7";

  private static final Map<String, String> RESOURCE =
      Map.of(
          "service.name", "qits-ci",
          "service.version", "2026.912.101500",
          "deployment.environment.name", "dev");

  /** An ERROR log inside a trace, with dotted attribute keys. */
  private static final StoredLog LOG =
      new StoredLog(
          1L,
          17,
          "ERROR",
          "Connection refused by qits-githost",
          TRACE,
          SPAN_ID,
          "qits-ci",
          Map.of("exception.type", "java.net.ConnectException", "retry.count", "42", "cached", "true"),
          RESOURCE,
          1000L);

  /** An ERROR span with an exception event. */
  private static final StoredSpan SPAN =
      new StoredSpan(
          TRACE,
          SPAN_ID,
          "",
          "qits-ci",
          "scope",
          "GET /ci/api/runs",
          "SERVER",
          1L,
          2L,
          "ERROR",
          "boom",
          Map.of("http.route", "/ci/api/runs"),
          List.of(new SpanEvent("exception", 1L, Map.of("exception.type", "java.lang.IllegalStateException"))),
          RESOURCE,
          1000L);

  private static final MetricPoint METRIC =
      new MetricPoint(
          "jvm.memory.used",
          "",
          "By",
          "GAUGE",
          12.5,
          1L,
          Map.of("pool", "heap"),
          "qits-ci",
          RESOURCE,
          1000L);

  /** A log with no trace, no severity, no service and no body. */
  private static final StoredLog BARE_LOG =
      new StoredLog(1L, 0, "", "", "", "", "", Map.of(), Map.of(), 1000L);

  private static final StoredSpan QUIET_SPAN =
      new StoredSpan(
          TRACE, SPAN_ID, "", "qits-ci", "scope", "GET /fine", "SERVER", 1L, 2L, "UNSET", "",
          Map.of(), List.of(), RESOURCE, 1000L);

  // --- helpers --------------------------------------------------------------------------------

  private static TelemetryFilter parse(String frame) {
    try {
      return TelemetryFilter.parse(JSON.readTree(frame));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** One group holding the given conditions. */
  private static TelemetryFilter group(String... conditions) {
    return parse("{\"subscribe\":[{\"conditions\":[" + String.join(",", conditions) + "]}]}");
  }

  private static String cond(String field, String op, String valueJson) {
    return "{\"field\":\"" + field + "\",\"op\":\"" + op + "\",\"value\":" + valueJson + "}";
  }

  private static String keyed(String field, String key, String op, String valueJson) {
    return "{\"field\":\""
        + field
        + "\",\"key\":\""
        + key
        + "\",\"op\":\""
        + op
        + "\",\"value\":"
        + valueJson
        + "}";
  }

  private static String str(String value) {
    return "\"" + value + "\"";
  }

  private static String reason(String frame) {
    return assertThrows(IllegalArgumentException.class, () -> parse(frame)).getMessage();
  }

  // --- kind -----------------------------------------------------------------------------------

  @Test
  void kindExactNamesOneKind() {
    TelemetryFilter logs = group(cond("kind", "exact", str("log")));
    assertTrue(logs.matches(LOG));
    assertFalse(logs.matches(SPAN));
    assertFalse(logs.matches(METRIC));
    assertTrue(group(cond("kind", "exact", str("span"))).matches(SPAN));
    assertTrue(group(cond("kind", "exact", str("metric"))).matches(METRIC));
  }

  @Test
  void kindTakesEveryOp() {
    assertTrue(group(cond("kind", "prefix", str("sp"))).matches(SPAN));
    assertFalse(group(cond("kind", "prefix", str("sp"))).matches(LOG));
    assertTrue(group(cond("kind", "contains", str("ETR"))).matches(METRIC));
    assertTrue(group(cond("kind", "exists", "true")).matches(LOG));
    assertFalse(group(cond("kind", "exists", "false")).matches(SPAN));
  }

  // --- service --------------------------------------------------------------------------------

  @Test
  void serviceIsTheResourceServiceNameOnEveryKind() {
    for (String op : new String[] {"exact", "prefix", "contains"}) {
      String value = op.equals("exact") ? "qits-ci" : op.equals("prefix") ? "qits-" : "CI";
      TelemetryFilter filter = group(cond("service", op, str(value)));
      assertTrue(filter.matches(LOG), op);
      assertTrue(filter.matches(SPAN), op);
      assertTrue(filter.matches(METRIC), op);
    }
    assertFalse(group(cond("service", "exact", str("qits-cd"))).matches(LOG));
    assertFalse(group(cond("service", "prefix", str("ci"))).matches(LOG));
  }

  @Test
  void anEmptyServiceNameIsAbsent() {
    assertTrue(group(cond("service", "exists", "false")).matches(BARE_LOG));
    assertFalse(group(cond("service", "exists", "true")).matches(BARE_LOG));
    assertFalse(group(cond("service", "exact", str(""))).matches(BARE_LOG));
    assertFalse(group(cond("service", "prefix", str(""))).matches(BARE_LOG));
    assertTrue(group(cond("service", "exists", "true")).matches(LOG));
  }

  // --- traceId / spanId -----------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({"traceId," + TRACE + ",4bf92f", "spanId," + SPAN_ID + ",00f067"})
  void idsMatchLogsAndSpans(String field, String id, String start) {
    for (var match :
        List.of(
            group(cond(field, "exact", str(id))),
            group(cond(field, "prefix", str(start))),
            group(cond(field, "contains", str(start.toUpperCase()))),
            group(cond(field, "exists", "true")))) {
      assertTrue(match.matches(LOG));
      assertTrue(match.matches(SPAN));
      assertFalse(match.matches(METRIC), "a metric has no " + field);
    }
    assertFalse(group(cond(field, "exact", str("ffff"))).matches(LOG));
    assertTrue(group(cond(field, "exists", "false")).matches(BARE_LOG), "an empty id is absent");
    assertTrue(group(cond(field, "exists", "false")).matches(METRIC));
  }

  // --- severity -------------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
    "TRACE,true", "DEBUG,true", "INFO,true", "WARN,true", "ERROR,true", "FATAL,false",
    "warn,true", "WARNING,true", "error,true"
  })
  void severityMinTakesTheBandNames(String name, boolean matchesAnError) {
    assertEquals(matchesAnError, group(cond("severity", "min", str(name))).matches(LOG));
  }

  @ParameterizedTest
  @CsvSource({"\"1\",true", "\"17\",true", "\"18\",false", "17,true", "18,false", "24,false"})
  void severityMinTakesNumbersAsStringOrNumber(String valueJson, boolean matches) {
    assertEquals(matches, group(cond("severity", "min", valueJson)).matches(LOG));
  }

  @Test
  void severityNamesMapToTheQueryApiFloors() {
    StoredLog warn2 =
        new StoredLog(1L, 14, "WARN2", "b", "", "", "s", Map.of(), Map.of(), 1L);
    StoredLog info4 =
        new StoredLog(1L, 12, "INFO4", "b", "", "", "s", Map.of(), Map.of(), 1L);
    TelemetryFilter warn = group(cond("severity", "min", str("WARN")));
    assertTrue(warn.matches(warn2));
    assertFalse(warn.matches(info4));
    assertTrue(group(cond("severity", "min", str("INFO"))).matches(info4));
  }

  @Test
  void aLogWithNoSeverityFailsEveryFloor() {
    assertFalse(group(cond("severity", "min", str("TRACE"))).matches(BARE_LOG));
    assertFalse(group(cond("severity", "min", "1")).matches(BARE_LOG));
    assertTrue(group(cond("severity", "exists", "false")).matches(BARE_LOG));
  }

  @Test
  void severityComparesAsTheNumberString() {
    assertTrue(group(cond("severity", "exact", str("17"))).matches(LOG));
    assertTrue(group(cond("severity", "exact", "17")).matches(LOG));
    assertFalse(group(cond("severity", "exact", str("ERROR"))).matches(LOG));
    assertTrue(group(cond("severity", "prefix", str("1"))).matches(LOG));
    assertTrue(group(cond("severity", "contains", str("7"))).matches(LOG));
    assertTrue(group(cond("severity", "exists", "true")).matches(LOG));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"LOUD\"", "\"0\"", "\"25\"", "25", "17.5", "true", "null", "{}"})
  void aSeverityFloorOutsideTheScaleIsRefused(String valueJson) {
    assertTrue(reason("{\"subscribe\":[{\"conditions\":[" + cond("severity", "min", valueJson) + "]}]}")
        .contains("'min' needs TRACE, DEBUG, INFO, WARN, ERROR, FATAL or a number 1-24"));
  }

  @Test
  void minAppliesToSeverityOnly() {
    assertTrue(
        reason("{\"subscribe\":[{\"conditions\":[" + cond("body", "min", str("ERROR")) + "]}]}")
            .contains("'min' applies to severity only"));
  }

  // --- body -----------------------------------------------------------------------------------

  @Test
  void bodyIsTheLogMessage() {
    assertTrue(group(cond("body", "exact", str("Connection refused by qits-githost"))).matches(LOG));
    assertTrue(group(cond("body", "prefix", str("Connection"))).matches(LOG));
    assertFalse(group(cond("body", "prefix", str("connection"))).matches(LOG), "prefix is exact case");
    assertTrue(group(cond("body", "contains", str("connection REFUSED"))).matches(LOG));
    assertTrue(group(cond("body", "exists", "true")).matches(LOG));
    assertTrue(group(cond("body", "exists", "false")).matches(BARE_LOG));
  }

  // --- name / status / event ------------------------------------------------------------------

  @Test
  void nameIsTheSpanOrMetricName() {
    assertTrue(group(cond("name", "exact", str("GET /ci/api/runs"))).matches(SPAN));
    assertTrue(group(cond("name", "prefix", str("jvm."))).matches(METRIC));
    assertTrue(group(cond("name", "contains", str("MEMORY"))).matches(METRIC));
    assertTrue(group(cond("name", "exists", "true")).matches(SPAN));
    assertFalse(group(cond("name", "exists", "true")).matches(LOG), "a log has no name");
  }

  @Test
  void statusIsTheSpanStatus() {
    assertTrue(group(cond("status", "exact", str("ERROR"))).matches(SPAN));
    assertFalse(group(cond("status", "exact", str("ERROR"))).matches(QUIET_SPAN));
    assertTrue(group(cond("status", "exact", str("UNSET"))).matches(QUIET_SPAN));
    assertTrue(group(cond("status", "contains", str("err"))).matches(SPAN));
    assertTrue(group(cond("status", "prefix", str("ER"))).matches(SPAN));
    assertFalse(group(cond("status", "exists", "true")).matches(LOG));
  }

  @Test
  void eventIsAnyOfTheSpansEventNames() {
    assertTrue(group(cond("event", "exact", str("exception"))).matches(SPAN));
    assertTrue(group(cond("event", "prefix", str("exc"))).matches(SPAN));
    assertTrue(group(cond("event", "contains", str("CEPT"))).matches(SPAN));
    assertTrue(group(cond("event", "exists", "true")).matches(SPAN));
    assertFalse(group(cond("event", "exists", "true")).matches(QUIET_SPAN), "no events is absent");
    assertTrue(group(cond("event", "exists", "false")).matches(QUIET_SPAN));
    assertFalse(group(cond("event", "exact", str("exception"))).matches(LOG));
  }

  // --- attribute / resource -------------------------------------------------------------------

  @Test
  void attributeTakesADottedKey() {
    String key = "exception.type";
    assertTrue(group(keyed("attribute", key, "exact", str("java.net.ConnectException"))).matches(LOG));
    assertTrue(group(keyed("attribute", key, "prefix", str("java.net."))).matches(LOG));
    assertTrue(group(keyed("attribute", key, "contains", str("connectexception"))).matches(LOG));
    assertTrue(group(keyed("attribute", key, "exists", "true")).matches(LOG));
    assertTrue(group(keyed("attribute", "http.route", "exact", str("/ci/api/runs"))).matches(SPAN));
    assertTrue(group(keyed("attribute", "pool", "exact", str("heap"))).matches(METRIC));
  }

  @Test
  void aMissingAttributeKeyIsAbsent() {
    assertFalse(group(keyed("attribute", "nope.nope", "exact", str(""))).matches(LOG));
    assertFalse(group(keyed("attribute", "nope.nope", "exists", "true")).matches(LOG));
    assertTrue(group(keyed("attribute", "nope.nope", "exists", "false")).matches(LOG));
    // A span's exception attributes live on its event, not on the span.
    assertFalse(group(keyed("attribute", "exception.type", "exists", "true")).matches(SPAN));
  }

  @Test
  void attributeValuesCompareAsStrings() {
    assertTrue(group(keyed("attribute", "retry.count", "exact", "42")).matches(LOG));
    assertTrue(group(keyed("attribute", "retry.count", "exact", str("42"))).matches(LOG));
    assertTrue(group(keyed("attribute", "cached", "exact", "true")).matches(LOG));
  }

  @Test
  void resourceReadsTheResourceAttributesOnEveryKind() {
    TelemetryFilter version = group(keyed("resource", "service.version", "prefix", str("2026.912.")));
    assertTrue(version.matches(LOG));
    assertTrue(version.matches(SPAN));
    assertTrue(version.matches(METRIC));
    assertTrue(group(keyed("resource", "deployment.environment.name", "exact", str("dev"))).matches(SPAN));
    assertTrue(group(keyed("resource", "deployment.environment.name", "contains", str("DE"))).matches(METRIC));
    assertTrue(group(keyed("resource", "qits.workspace.id", "exists", "false")).matches(LOG));
    assertFalse(group(keyed("resource", "service.version", "exists", "true")).matches(BARE_LOG));
  }

  @Test
  void attributeAndResourceNeedAKey() {
    assertTrue(
        reason("{\"subscribe\":[{\"conditions\":[" + cond("attribute", "exists", "true") + "]}]}")
            .contains("field 'attribute' needs a 'key'"));
    assertTrue(
        reason("{\"subscribe\":[{\"conditions\":[" + keyed("resource", "", "exists", "true") + "]}]}")
            .contains("field 'resource' needs a 'key'"));
  }

  // --- a field a kind does not have -----------------------------------------------------------

  @Test
  void aFieldTheKindDoesNotHaveFailsEveryOpExceptExistsFalse() {
    record Missing(String field, Object record) {}
    List<Missing> missing =
        List.of(
            new Missing("name", LOG),
            new Missing("status", LOG),
            new Missing("event", LOG),
            new Missing("body", SPAN),
            new Missing("severity", SPAN),
            new Missing("traceId", METRIC),
            new Missing("spanId", METRIC),
            new Missing("severity", METRIC),
            new Missing("body", METRIC),
            new Missing("status", METRIC),
            new Missing("event", METRIC));
    for (Missing m : missing) {
      String label = m.field() + " on " + m.record().getClass().getSimpleName();
      assertFalse(matches(group(cond(m.field(), "exact", str(""))), m.record()), label);
      assertFalse(matches(group(cond(m.field(), "exact", str("x"))), m.record()), label);
      assertFalse(matches(group(cond(m.field(), "prefix", str(""))), m.record()), label);
      assertFalse(matches(group(cond(m.field(), "contains", str(""))), m.record()), label);
      assertFalse(matches(group(cond(m.field(), "exists", "true")), m.record()), label);
      assertTrue(matches(group(cond(m.field(), "exists", "false")), m.record()), label);
      if (m.field().equals("severity")) {
        assertFalse(matches(group(cond("severity", "min", "1")), m.record()), label);
      }
    }
  }

  private static boolean matches(TelemetryFilter filter, Object record) {
    return switch (record) {
      case StoredLog log -> filter.matches(log);
      case StoredSpan span -> filter.matches(span);
      case MetricPoint point -> filter.matches(point);
      default -> throw new IllegalArgumentException(record.toString());
    };
  }

  // --- groups ---------------------------------------------------------------------------------

  @Test
  void conditionsInAGroupAreAnded() {
    TelemetryFilter errorLogs =
        group(cond("kind", "exact", str("log")), cond("severity", "min", str("ERROR")));
    assertTrue(errorLogs.matches(LOG));
    assertFalse(errorLogs.matches(SPAN));
    assertFalse(
        group(cond("kind", "exact", str("log")), cond("service", "exact", str("qits-cd")))
            .matches(LOG));
  }

  @Test
  void groupsAreOred() {
    TelemetryFilter either =
        parse(
            "{\"subscribe\":["
                + "{\"conditions\":["
                + cond("kind", "exact", str("metric"))
                + "]},"
                + "{\"conditions\":["
                + cond("status", "exact", str("ERROR"))
                + "]}]}");
    assertTrue(either.matches(METRIC));
    assertTrue(either.matches(SPAN));
    assertFalse(either.matches(QUIET_SPAN));
    assertFalse(either.matches(LOG));
  }

  @Test
  void anEmptyGroupMatchesEverything() {
    TelemetryFilter everything = parse("{\"subscribe\":[{\"conditions\":[]}]}");
    assertFalse(everything.isEmpty());
    assertTrue(everything.matches(LOG));
    assertTrue(everything.matches(BARE_LOG));
    assertTrue(everything.matches(SPAN));
    assertTrue(everything.matches(METRIC));
  }

  @Test
  void anEmptySubscribeMatchesNothing() {
    TelemetryFilter nothing = parse("{\"subscribe\":[]}");
    assertSame(TelemetryFilter.NOTHING, nothing);
    assertTrue(nothing.isEmpty());
    assertFalse(nothing.matches(LOG));
    assertFalse(nothing.matches(SPAN));
    assertFalse(nothing.matches(METRIC));
  }

  // --- malformed frames -----------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '`',
      value = {
        "[]|a frame must be a JSON object",
        "\"subscribe\"|a frame must be a JSON object",
        "{}|'subscribe' must be an array of groups",
        "{\"subscribe\":\"x\"}|'subscribe' must be an array of groups",
        "{\"subscribe\":{}}|'subscribe' must be an array of groups",
        "{\"subscribe\":[1]}|group 0 must be an object",
        "{\"subscribe\":[{}]}|group 0: 'conditions' must be an array",
        "{\"subscribe\":[{\"conditions\":[]},{\"conditions\":{}}]}|group 1: 'conditions' must be an array",
        "{\"subscribe\":[{\"conditions\":[\"kind=log\"]}]}|group 0, condition 0 must be an object",
        "{\"subscribe\":[{\"conditions\":[{\"field\":\"level\",\"op\":\"min\",\"value\":\"ERROR\"}]}]}|unknown field 'level'",
        "{\"subscribe\":[{\"conditions\":[{\"op\":\"exact\",\"value\":\"x\"}]}]}|unknown field (missing)",
        "{\"subscribe\":[{\"conditions\":[{\"field\":\"kind\",\"op\":\"equals\",\"value\":\"log\"}]}]}|unknown op 'equals'",
        "{\"subscribe\":[{\"conditions\":[{\"field\":\"kind\",\"op\":\"exists\",\"value\":\"true\"}]}]}|'exists' needs a boolean value",
        "{\"subscribe\":[{\"conditions\":[{\"field\":\"kind\",\"op\":\"exists\"}]}]}|'exists' needs a boolean value",
        "{\"subscribe\":[{\"conditions\":[{\"field\":\"kind\",\"op\":\"exact\"}]}]}|'exact' needs a string value",
        "{\"subscribe\":[{\"conditions\":[{\"field\":\"kind\",\"op\":\"prefix\",\"value\":null}]}]}|'prefix' needs a string value",
        "{\"subscribe\":[{\"conditions\":[{\"field\":\"kind\",\"op\":\"contains\",\"value\":[\"x\"]}]}]}|'contains' needs a string value"
      })
  void aMalformedFrameIsRefusedWithAReason(String frame, String reason) {
    String got = reason(frame);
    assertTrue(got.contains(reason), "expected '" + reason + "' in: " + got);
  }
}
