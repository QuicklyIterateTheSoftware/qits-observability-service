package eu.wohlben.qits.telemetry.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>A new error issue</b> (epic qits-2538, qits-2684): a fingerprint this service had never stored
 * was inserted by a flush. Published once per fingerprint, by {@code control/ErrorIssueAnnouncer},
 * after the flush transaction that inserted the row committed. The wire name is the simple class
 * name.
 *
 * <p>{@code occurredAt} is the issue's {@code first_seen} and {@code serviceVersion} that
 * occurrence's version. {@code message} is the normalised title (at most 200 characters), so the
 * event never carries a stack trace; {@code traceId} is null when the occurrence had none. No
 * component may be called {@code name} or {@code signature}: those are {@link QitsEvent}'s.
 */
public record ErrorIssueOpened(
    UUID eventId,
    Instant occurredAt,
    String fingerprint,
    String serviceName,
    String serviceVersion,
    String exceptionType,
    String location,
    String message,
    String traceId)
    implements QitsEvent {

  public ErrorIssueOpened {
    if (eventId == null) {
      eventId = UUID.randomUUID();
    }
  }
}
