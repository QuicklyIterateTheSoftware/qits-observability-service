package eu.wohlben.qits.telemetry.dto;

import java.util.Map;

/**
 * A log record as returned by the telemetry query surface (the REST routes).
 *
 * <p>{@code attributes} are the record's own. {@code resourceAttributes} are the emitting
 * resource's, and they are passed through <em>whole</em> — not filtered to a known set. That is what
 * answers "which build wrote this line": every cd-deployed container stamps {@code service.version},
 * {@code deployment.environment.name} and {@code service.instance.id} into its resource, and a
 * reader holding only {@code serviceName} cannot tell one release from the next.
 *
 * <p>Whole has two consequences, both deliberate. An attribute the platform starts stamping tomorrow
 * reaches a screen with no change here. And the map repeats on every record of a page, because the
 * store holds it per record rather than per source — a few hundred bytes a row, which is why the
 * page limits stay where they are.
 */
public record TelemetryLogDto(
    long epochNanos,
    int severityNumber,
    String severityText,
    String body,
    String traceId,
    String spanId,
    String serviceName,
    Map<String, String> attributes,
    Map<String, String> resourceAttributes) {

  public static TelemetryLogDto of(StoredLog log) {
    return new TelemetryLogDto(
        log.epochNanos(),
        log.severityNumber(),
        log.severityText(),
        log.body(),
        log.traceId(),
        log.spanId(),
        log.serviceName(),
        log.attributes(),
        log.resourceAttributes());
  }
}
