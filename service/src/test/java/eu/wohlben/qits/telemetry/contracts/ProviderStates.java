package eu.wohlben.qits.telemetry.contracts;

import static eu.wohlben.qits.telemetry.TelemetryFixtures.attribute;
import static eu.wohlben.qits.telemetry.TelemetryFixtures.spanBuilder;
import static eu.wohlben.qits.telemetry.TelemetryFixtures.traceRequest;

import com.google.protobuf.ByteString;
import eu.wohlben.qits.telemetry.control.TelemetryDecoder;
import eu.wohlben.qits.telemetry.control.TelemetryStore;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * <b>qits-observability's provider states</b> (epic qits-546, ticket qits-1149): each fills the
 * in-memory buffer for one consumer situation and hands back its parameters.
 *
 * <p>Two callers. {@link GoldenMasterRecordingTest} runs a state before it records each operation
 * that names it, and {@link ConsumerPactVerificationTest}'s {@code @State} methods delegate here.
 *
 * <p><b>A state empties the buffer first</b>, so it assumes nothing about what ran before it.
 *
 * <p><b>Every time is fixed.</b> Record times and the ingest stamp are on {@link #T0}, so an answer
 * is the same on every run. The one live value is the buffer's {@code startedAt}, which the
 * recording freezes. A consumer that filters by a window relative to the wall clock ({@code
 * sinceMinutes}) finds nothing in these states; it needs a state of its own.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String TELEMETRY_FROM_ONE_SERVICE = "telemetry from one platform service";
  public static final String AN_EMPTY_TELEMETRY_BUFFER = "an empty telemetry buffer";

  /** 2026-01-01T00:00:00Z: the time every record in a state is dated from. */
  static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  static final String SERVICE = "qits-example";
  static final String SOURCE = TelemetryStore.SERVICE_KEY_PREFIX + SERVICE;

  /** The trace that failed: a server span in ERROR with an exception event, and a slow child. */
  static final String FAILED_TRACE_ID = "6a1f0c2b9d8e47f3a5b4c3d2e1f00a01";

  static final String FAILED_ROOT_SPAN_ID = "7b2e1d3c4f5a6b01";
  static final String SLOW_CHILD_SPAN_ID = "7b2e1d3c4f5a6b02";

  /** A second trace that went well, so the trace list has more than one row. */
  static final String OK_TRACE_ID = "6a1f0c2b9d8e47f3a5b4c3d2e1f00a02";

  static final String OK_SPAN_ID = "7b2e1d3c4f5a6b03";

  /** What a state hands back: its parameters, keys sorted. */
  public record Setup(Map<String, String> params) {}

  @Inject TelemetryStore store;
  @Inject TelemetryDecoder decoder;

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  public ProviderStates() {
    states.put(TELEMETRY_FROM_ONE_SERVICE, this::telemetryFromOneService);
    states.put(AN_EMPTY_TELEMETRY_BUFFER, this::anEmptyTelemetryBuffer);
  }

  /** Every state name this provider answers for. */
  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  /** Runs the named state; an unknown name is a programming error, not an empty state. */
  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  /** {@link #setUp} for a pact {@code @State} method, which returns only the params. */
  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /** Nothing buffered. */
  private Setup anEmptyTelemetryBuffer() {
    store.clear();
    return new Setup(Map.of());
  }

  /**
   * One platform service, {@value #SERVICE}, exported what a deployed Quarkus service exports: its
   * resource names the service, the release and the environment, and carries no qits.* pair, so the
   * telemetry lands in the service bucket {@value #SOURCE}.
   *
   * <ul>
   *   <li>Trace {@value #FAILED_TRACE_ID}: a server span {@code GET /invoices} in ERROR with an
   *       {@code exception} event (750 ms), and inside it a 600 ms {@code SELECT invoices} span —
   *       a slow span at the default 500 ms threshold.
   *   <li>Trace {@value #OK_TRACE_ID}: a 5 ms {@code GET /health} that went well.
   *   <li>Two logs in the failed trace: an INFO and an ERROR carrying the exception attributes.
   *   <li>Two metric series: the gauge {@code jvm.memory.used} and the counter {@code
   *       http.server.requests}.
   * </ul>
   */
  private Setup telemetryFromOneService() {
    store.clear();
    long received = T0.toEpochMilli() + 2_000L;
    long t0 = T0.toEpochMilli() * 1_000_000L;
    Resource resource =
        Resource.newBuilder()
            .addAttributes(attribute("service.name", SERVICE))
            .addAttributes(attribute("service.version", "2026.1231.120000"))
            .addAttributes(attribute("deployment.environment.name", "dev"))
            .build();

    Span root =
        spanBuilder(FAILED_TRACE_ID, FAILED_ROOT_SPAN_ID, "GET /invoices", t0, t0 + 750_000_000L)
            .addAttributes(attribute("http.route", "/invoices"))
            .setStatus(
                Status.newBuilder()
                    .setCode(Status.StatusCode.STATUS_CODE_ERROR)
                    .setMessage("invoice store unreachable"))
            .addEvents(
                Span.Event.newBuilder()
                    .setName("exception")
                    .setTimeUnixNano(t0 + 700_000_000L)
                    .addAttributes(attribute("exception.type", "java.lang.IllegalStateException"))
                    .addAttributes(attribute("exception.message", "invoice store unreachable"))
                    .addAttributes(
                        attribute(
                            "exception.stacktrace",
                            "java.lang.IllegalStateException: invoice store unreachable\n"
                                + "\tat eu.example.InvoiceResource.list(InvoiceResource.java:42)\n")))
            .build();
    Span slowChild =
        spanBuilder(
                FAILED_TRACE_ID,
                SLOW_CHILD_SPAN_ID,
                "SELECT invoices",
                t0 + 50_000_000L,
                t0 + 650_000_000L)
            .setParentSpanId(ByteString.copyFrom(HexFormat.of().parseHex(FAILED_ROOT_SPAN_ID)))
            .setKind(Span.SpanKind.SPAN_KIND_CLIENT)
            .build();
    Span ok =
        spanBuilder(OK_TRACE_ID, OK_SPAN_ID, "GET /health", t0 + 1_000_000_000L, t0 + 1_005_000_000L)
            .addAttributes(attribute("http.route", "/health"))
            .build();
    store.addSpans(decoder.decodeSpans(traceRequest(resource, root, slowChild, ok), received));

    LogRecord info =
        log(t0 + 10_000_000L, SeverityNumber.SEVERITY_NUMBER_INFO, "INFO", "listing invoices")
            .build();
    LogRecord error =
        log(
                t0 + 700_000_000L,
                SeverityNumber.SEVERITY_NUMBER_ERROR,
                "ERROR",
                "invoice store unreachable")
            .addAttributes(attribute("exception.type", "java.lang.IllegalStateException"))
            .addAttributes(attribute("exception.message", "invoice store unreachable"))
            .build();
    store.addLogs(
        decoder.decodeLogs(
            ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(
                    ResourceLogs.newBuilder()
                        .setResource(resource)
                        .addScopeLogs(
                            ScopeLogs.newBuilder()
                                .setScope(
                                    InstrumentationScope.newBuilder()
                                        .setName("io.quarkus.opentelemetry"))
                                .addLogRecords(info)
                                .addLogRecords(error)))
                .build(),
            received));

    Metric gauge =
        Metric.newBuilder()
            .setName("jvm.memory.used")
            .setUnit("By")
            .setGauge(
                Gauge.newBuilder()
                    .addDataPoints(
                        NumberDataPoint.newBuilder()
                            .setTimeUnixNano(t0 + 1_000_000_000L)
                            .setAsDouble(150_000_000d)
                            .addAttributes(attribute("pool", "heap"))))
            .build();
    Metric counter =
        Metric.newBuilder()
            .setName("http.server.requests")
            .setSum(
                Sum.newBuilder()
                    .setIsMonotonic(true)
                    .addDataPoints(
                        NumberDataPoint.newBuilder()
                            .setTimeUnixNano(t0 + 1_000_000_000L)
                            .setAsInt(3)))
            .build();
    store.addMetrics(
        decoder.decodeMetrics(
            ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(
                    ResourceMetrics.newBuilder()
                        .setResource(resource)
                        .addScopeMetrics(
                            ScopeMetrics.newBuilder().addMetrics(gauge).addMetrics(counter)))
                .build(),
            received));

    Map<String, String> params = new TreeMap<>();
    params.put("source", SOURCE);
    params.put("service", SERVICE);
    params.put("traceId", FAILED_TRACE_ID);
    return new Setup(Collections.unmodifiableMap(params));
  }

  private static LogRecord.Builder log(
      long epochNanos, SeverityNumber severity, String severityText, String body) {
    return LogRecord.newBuilder()
        .setTimeUnixNano(epochNanos)
        .setObservedTimeUnixNano(epochNanos)
        .setSeverityNumber(severity)
        .setSeverityText(severityText)
        .setBody(AnyValue.newBuilder().setStringValue(body))
        .setTraceId(ByteString.copyFrom(HexFormat.of().parseHex(FAILED_TRACE_ID)))
        .setSpanId(ByteString.copyFrom(HexFormat.of().parseHex(FAILED_ROOT_SPAN_ID)));
  }
}
