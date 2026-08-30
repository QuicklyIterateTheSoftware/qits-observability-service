package eu.wohlben.qits.telemetry.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One row of the trace list: enough to draw it and to decide whether to open it, and nothing more.
 * The spans themselves come from {@code GET /telemetry/traces/{traceId}}.
 *
 * <p>{@code rootName} is the name of the span with no parent. In a bounded buffer that span is
 * often gone — evicted, or never in this bucket at all — so when there is none the earliest
 * buffered span stands in and {@code rootMissing} is true. Say so on screen: a plausible-looking
 * wrong root is worse than an admitted gap.
 *
 * <p>{@code durationMs} spans the whole trace as buffered (earliest start to latest end), which is
 * not the root's own duration when the root is missing. {@code spanCount} likewise counts what
 * survived, not what was emitted.
 *
 * <p>{@code rootRoute} is the shown span's templated route ({@code http.route}), falling back to
 * its concrete path ({@code url.path}), and null when it carries neither — a non-HTTP root, or no
 * root at all. Like {@code rootName}, under {@code rootMissing} it describes the stand-in span, so
 * the group a trace lands in always matches what its row says.
 */
@Schema(name = "TelemetryTraceSummary", description = "One row of the trace list.")
public record TelemetryTraceSummaryDto(
    String traceId,
    String rootName,
    String rootService,
    String rootRoute,
    List<String> services,
    long startEpochNanos,
    long durationMs,
    int spanCount,
    int errorSpanCount,
    boolean hasException,
    boolean rootMissing) {}
