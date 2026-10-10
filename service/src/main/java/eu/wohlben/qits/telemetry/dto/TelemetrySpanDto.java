package eu.wohlben.qits.telemetry.dto;

import java.util.List;
import java.util.Map;

/**
 * A span as returned by the telemetry query surface (the REST routes).
 *
 * <p>{@code resourceAttributes} carries the emitting resource whole, for the reason and with the
 * caveats written on {@link TelemetryLogDto}: without it a slow endpoint and a slow release read the
 * same.
 */
public record TelemetrySpanDto(
    String traceId,
    String spanId,
    String parentSpanId,
    String serviceName,
    String scopeName,
    String name,
    String kind,
    long startEpochNanos,
    long durationMs,
    String status,
    String statusMessage,
    Map<String, String> attributes,
    List<SpanEvent> events,
    Map<String, String> resourceAttributes) {

  public static TelemetrySpanDto of(StoredSpan span) {
    return new TelemetrySpanDto(
        span.traceId(),
        span.spanId(),
        span.parentSpanId(),
        span.serviceName(),
        span.scopeName(),
        span.name(),
        span.kind(),
        span.startEpochNanos(),
        span.durationMs(),
        span.status(),
        span.statusMessage(),
        span.attributes(),
        span.events(),
        span.resourceAttributes());
  }
}
