package eu.wohlben.qits.telemetry.dto;

import eu.wohlben.qits.telemetry.issues.ErrorIssue;
import java.time.Instant;

/**
 * One error issue on the wire ({@code listErrorIssues}, {@code dismissErrorIssue}). {@code state} is
 * {@code OPEN} or {@code DISMISSED}; {@code exceptionType} and {@code location} are null when the
 * occurrences carried none; {@code message} is the normalised title; the instants are ISO-8601. A
 * reopened issue keeps {@code dismissedAt} / {@code dismissedBy} as its history.
 */
public record ErrorIssueDto(
    String fingerprint,
    String state,
    String serviceName,
    String exceptionType,
    String location,
    String message,
    String sampleMessage,
    String sampleTraceId,
    Instant firstSeen,
    Instant lastSeen,
    String firstVersion,
    String lastVersion,
    long occurrences,
    int regressions,
    Instant regressedAt,
    Instant dismissedAt,
    String dismissedBy) {

  public static ErrorIssueDto of(ErrorIssue issue) {
    return new ErrorIssueDto(
        issue.fingerprint(),
        issue.state().name(),
        issue.serviceName(),
        issue.exceptionType(),
        issue.location(),
        issue.message(),
        issue.sampleMessage(),
        issue.sampleTraceId(),
        issue.firstSeen(),
        issue.lastSeen(),
        issue.firstVersion(),
        issue.lastVersion(),
        issue.occurrences(),
        issue.regressions(),
        issue.regressedAt(),
        issue.dismissedAt(),
        issue.dismissedBy());
  }
}
