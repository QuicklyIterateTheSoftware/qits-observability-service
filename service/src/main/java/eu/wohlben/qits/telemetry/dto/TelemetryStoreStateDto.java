package eu.wohlben.qits.telemetry.dto;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The buffer's own state, as returned by {@code GET /telemetry/store} — the numbers a UI needs to
 * say what it is showing without overclaiming.
 *
 * <p>{@code startedAt} is when this buffer began holding what it holds. It is not a build date or a
 * deployment time: the store is in memory and a restart empties it, so this is the honest lower
 * bound on the age of anything in here.
 *
 * <p>{@code evictedSpans} is the load-bearing one. Zero means the answers below are everything that
 * arrived; non-zero means they are what survived, and a screen that does not say so is inviting the
 * wrong conclusion from a short list.
 *
 * <p>Read pressure per source: a source's own {@code bytes} (on the wire as {@code
 * TelemetrySourceDto.bytes}) against {@code maxBytesPerSource}. That budget is the tier that binds
 * for an edge-shaped source, and it is what decides what that source retains — its counts can sit
 * well under their caps while the byte budget is evicting.
 *
 * <p>{@code totalBytes} against {@code maxTotalBytes} is the backstop gauge: it bounds this
 * process's heap across every bucket, and it should not bind in normal operation. If it sits near
 * full, the answer is to lower the per-source budget, not to raise the ceiling — a binding global
 * tier means one source's retention is decided by what another source is holding.
 */
@Schema(name = "TelemetryStoreState", description = "The in-memory buffer's own state.")
public record TelemetryStoreStateDto(
    Instant startedAt,
    long totalBytes,
    long maxTotalBytes,
    long maxBytesPerSource,
    Caps caps,
    int sourceCount,
    long evictedSpans,
    long evictedLogs,
    long droppedMetricSeries) {

  /** The per-source count caps in force. */
  @Schema(name = "TelemetryStoreCaps")
  public record Caps(int spansPerSource, int logsPerSource, int metricSeriesPerSource) {}
}
