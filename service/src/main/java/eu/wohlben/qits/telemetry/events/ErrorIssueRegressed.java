package eu.wohlben.qits.telemetry.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>A dismissed error issue seen again</b> (epic qits-2538, qits-2684): a flush turned a DISMISSED
 * row back to OPEN. Published once per fingerprint, by {@code control/ErrorIssueAnnouncer}, after
 * the flush transaction that reopened the row committed. The wire name is the simple class name.
 *
 * <p>{@code occurredAt} and {@code serviceVersion} are the regressing occurrence's — the earliest
 * in the window. {@code dismissedAt} is when the issue had been dismissed, {@code regressions} the
 * count including this one and {@code occurrences} the total after the write. {@code message} is
 * the normalised title (at most 200 characters); {@code traceId} is null when the occurrence had
 * none. No component may be called {@code name} or {@code signature}: those are {@link
 * QitsEvent}'s.
 */
public record ErrorIssueRegressed(
    UUID eventId,
    Instant occurredAt,
    String fingerprint,
    String serviceName,
    String serviceVersion,
    String exceptionType,
    String location,
    String message,
    String traceId,
    Instant dismissedAt,
    int regressions,
    long occurrences)
    implements QitsEvent {

  public ErrorIssueRegressed {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }
}
