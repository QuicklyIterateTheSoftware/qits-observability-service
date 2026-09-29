package eu.wohlben.qits.telemetry.control;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.telemetry.dto.MetricPoint;
import eu.wohlben.qits.telemetry.dto.SpanEvent;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.dto.StoredSpan;
import eu.wohlben.qits.telemetry.error.DomainException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What one live-stream connection asked for: the groups of its last {@code {"subscribe": [...]}}
 * frame. The wire protocol is README "The live stream", shared with the {@code qits observe}
 * command.
 *
 * <p>A record matches when <b>any</b> group matches (OR), and a group matches when <b>all</b> its
 * conditions hold (AND). A group with no conditions matches everything. No groups at all matches
 * nothing: that is {@link #NOTHING}, the state of a connection that has not subscribed yet.
 *
 * <p>A field a record kind does not have, or has empty, is <i>absent</i>. An absent field fails every
 * op except {@code exists: false}. That is the qits-ci {@code when:} rule.
 *
 * <p>Plain Java and Jackson's tree model only, so {@code TelemetryFilterTest} runs without Quarkus.
 * Immutable: a new subscribe frame makes a new filter.
 */
public final class TelemetryFilter {

  /** The {@code kind} values on the wire, in the frame envelope and in a {@code kind} condition. */
  public static final String LOG = "log";

  public static final String SPAN = "span";
  public static final String METRIC = "metric";

  /** No groups: matches no record. */
  public static final TelemetryFilter NOTHING = new TelemetryFilter(List.of());

  enum Field {
    KIND("kind"),
    SERVICE("service"),
    TRACE_ID("traceId"),
    SPAN_ID("spanId"),
    SEVERITY("severity"),
    BODY("body"),
    NAME("name"),
    STATUS("status"),
    EVENT("event"),
    ATTRIBUTE("attribute"),
    RESOURCE("resource");

    final String wire;

    Field(String wire) {
      this.wire = wire;
    }

    boolean keyed() {
      return this == ATTRIBUTE || this == RESOURCE;
    }

    static Field of(String wire) {
      for (Field field : values()) {
        if (field.wire.equals(wire)) {
          return field;
        }
      }
      return null;
    }
  }

  enum Op {
    EXACT("exact"),
    PREFIX("prefix"),
    EXISTS("exists"),
    MIN("min"),
    CONTAINS("contains");

    final String wire;

    Op(String wire) {
      this.wire = wire;
    }

    static Op of(String wire) {
      for (Op op : values()) {
        if (op.wire.equals(wire)) {
          return op;
        }
      }
      return null;
    }
  }

  /**
   * One parsed condition. {@code text} is the compare value for exact/prefix/contains (lowercased
   * for contains), {@code exists} the flag for exists, {@code floor} the severity number for min.
   */
  record Condition(Field field, String key, Op op, String text, boolean exists, int floor) {}

  record Group(List<Condition> conditions) {}

  /** Reads one field off one record: its values, or an empty list when the field is absent. */
  @FunctionalInterface
  private interface Values {
    List<String> of(Field field, String key);
  }

  private final List<Group> groups;

  private TelemetryFilter(List<Group> groups) {
    this.groups = List.copyOf(groups);
  }

  /** True for a filter that matches no record. */
  public boolean isEmpty() {
    return groups.isEmpty();
  }

  public boolean matches(StoredLog log) {
    return !groups.isEmpty() && matches((field, key) -> valuesOf(log, field, key));
  }

  public boolean matches(StoredSpan span) {
    return !groups.isEmpty() && matches((field, key) -> valuesOf(span, field, key));
  }

  public boolean matches(MetricPoint point) {
    return !groups.isEmpty() && matches((field, key) -> valuesOf(point, field, key));
  }

  private boolean matches(Values record) {
    for (Group group : groups) {
      if (allHold(group, record)) {
        return true;
      }
    }
    return false;
  }

  private static boolean allHold(Group group, Values record) {
    for (Condition condition : group.conditions()) {
      if (!holds(condition, record.of(condition.field(), condition.key()))) {
        return false;
      }
    }
    return true;
  }

  private static boolean holds(Condition condition, List<String> values) {
    if (condition.op() == Op.EXISTS) {
      return values.isEmpty() != condition.exists();
    }
    for (String value : values) {
      boolean hit =
          switch (condition.op()) {
            case EXACT -> value.equals(condition.text());
            case PREFIX -> value.startsWith(condition.text());
            case CONTAINS -> value.toLowerCase(Locale.ROOT).contains(condition.text());
            case MIN -> Integer.parseInt(value) >= condition.floor();
            case EXISTS -> throw new IllegalStateException("handled above");
          };
      if (hit) {
        return true;
      }
    }
    return false;
  }

  // --- what each record kind has --------------------------------------------------------------

  private static List<String> valuesOf(StoredLog log, Field field, String key) {
    return switch (field) {
      case KIND -> List.of(LOG);
      case SERVICE -> present(log.serviceName());
      case TRACE_ID -> present(log.traceId());
      case SPAN_ID -> present(log.spanId());
      case SEVERITY ->
          log.severityNumber() > 0
              ? List.of(Integer.toString(log.severityNumber()))
              : List.of();
      case BODY -> present(log.body());
      case ATTRIBUTE -> present(get(log.attributes(), key));
      case RESOURCE -> present(get(log.resourceAttributes(), key));
      case NAME, STATUS, EVENT -> List.of();
    };
  }

  private static List<String> valuesOf(StoredSpan span, Field field, String key) {
    return switch (field) {
      case KIND -> List.of(SPAN);
      case SERVICE -> present(span.serviceName());
      case TRACE_ID -> present(span.traceId());
      case SPAN_ID -> present(span.spanId());
      case NAME -> present(span.name());
      case STATUS -> present(span.status());
      case EVENT -> eventNames(span.events());
      case ATTRIBUTE -> present(get(span.attributes(), key));
      case RESOURCE -> present(get(span.resourceAttributes(), key));
      case SEVERITY, BODY -> List.of();
    };
  }

  private static List<String> valuesOf(MetricPoint point, Field field, String key) {
    return switch (field) {
      case KIND -> List.of(METRIC);
      case SERVICE -> present(point.serviceName());
      case NAME -> present(point.name());
      case ATTRIBUTE -> present(get(point.attributes(), key));
      case RESOURCE -> present(get(point.resourceAttributes(), key));
      case TRACE_ID, SPAN_ID, SEVERITY, BODY, STATUS, EVENT -> List.of();
    };
  }

  private static List<String> eventNames(List<SpanEvent> events) {
    if (events == null || events.isEmpty()) {
      return List.of();
    }
    List<String> names = new ArrayList<>(events.size());
    for (SpanEvent event : events) {
      if (event.name() != null && !event.name().isEmpty()) {
        names.add(event.name());
      }
    }
    return names;
  }

  private static String get(Map<String, String> attributes, String key) {
    return attributes == null ? null : attributes.get(key);
  }

  private static List<String> present(String value) {
    return value == null || value.isEmpty() ? List.of() : List.of(value);
  }

  // --- the subscribe frame --------------------------------------------------------------------

  /**
   * Parses a whole {@code {"subscribe": [...]}} frame.
   *
   * @throws IllegalArgumentException with the reason the client gets back as {@code {"error": …}}
   */
  public static TelemetryFilter parse(JsonNode frame) {
    if (frame == null || !frame.isObject()) {
      throw bad("a frame must be a JSON object: {\"subscribe\": [...]}");
    }
    JsonNode subscribe = frame.get("subscribe");
    if (subscribe == null || !subscribe.isArray()) {
      throw bad("'subscribe' must be an array of groups");
    }
    List<Group> groups = new ArrayList<>();
    int g = 0;
    for (JsonNode group : subscribe) {
      String where = "group " + g++;
      if (!group.isObject()) {
        throw bad(where + " must be an object: {\"conditions\": [...]}");
      }
      JsonNode conditions = group.get("conditions");
      if (conditions == null || !conditions.isArray()) {
        throw bad(where + ": 'conditions' must be an array");
      }
      List<Condition> parsed = new ArrayList<>();
      int c = 0;
      for (JsonNode condition : conditions) {
        parsed.add(condition(condition, where + ", condition " + c++));
      }
      groups.add(new Group(List.copyOf(parsed)));
    }
    return groups.isEmpty() ? NOTHING : new TelemetryFilter(groups);
  }

  private static Condition condition(JsonNode node, String where) {
    if (!node.isObject()) {
      throw bad(where + " must be an object: {\"field\", \"op\", \"value\"}");
    }
    String fieldName = text(node.get("field"));
    Field field = Field.of(fieldName);
    if (field == null) {
      throw bad(
          where
              + ": unknown field "
              + quoted(fieldName)
              + " (kind, service, traceId, spanId, severity, body, name, status, event, attribute,"
              + " resource)");
    }
    String opName = text(node.get("op"));
    Op op = Op.of(opName);
    if (op == null) {
      throw bad(where + ": unknown op " + quoted(opName) + " (exact, prefix, exists, min, contains)");
    }
    String key = null;
    if (field.keyed()) {
      key = text(node.get("key"));
      if (key == null || key.isEmpty()) {
        throw bad(where + ": field '" + field.wire + "' needs a 'key'");
      }
    }
    JsonNode value = node.get("value");
    return switch (op) {
      case EXISTS -> {
        if (value == null || !value.isBoolean()) {
          throw bad(where + ": 'exists' needs a boolean value");
        }
        yield new Condition(field, key, op, null, value.booleanValue(), 0);
      }
      case MIN -> {
        if (field != Field.SEVERITY) {
          throw bad(where + ": 'min' applies to severity only");
        }
        yield new Condition(field, key, op, null, false, severityFloor(value, where));
      }
      case EXACT, PREFIX, CONTAINS -> {
        // Values compare as strings: a JSON number or boolean is taken as its text.
        if (value == null || !value.isValueNode() || value.isNull()) {
          throw bad(where + ": '" + op.wire + "' needs a string value");
        }
        String text = value.asText();
        yield new Condition(
            field, key, op, op == Op.CONTAINS ? text.toLowerCase(Locale.ROOT) : text, false, 0);
      }
    };
  }

  /** The same mapping as the query API's {@code minSeverity}: a band name or a number 1-24. */
  private static int severityFloor(JsonNode value, String where) {
    String reason = where + ": 'min' needs TRACE, DEBUG, INFO, WARN, ERROR, FATAL or a number 1-24";
    if (value == null || !(value.isTextual() || value.isIntegralNumber())) {
      throw bad(reason);
    }
    Integer floor;
    try {
      floor = TelemetryQueryService.severityFloor(value.asText());
    } catch (DomainException notASeverity) {
      throw bad(reason);
    }
    if (floor == null) {
      throw bad(reason);
    }
    return floor;
  }

  private static String text(JsonNode node) {
    return node != null && node.isTextual() ? node.asText() : null;
  }

  private static String quoted(String value) {
    return value == null ? "(missing)" : "'" + value + "'";
  }

  private static IllegalArgumentException bad(String reason) {
    return new IllegalArgumentException(reason);
  }
}
