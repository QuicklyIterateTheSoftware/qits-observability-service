package eu.wohlben.qits.telemetry.dto;

import eu.wohlben.qits.telemetry.issues.ErrorIssue;
import eu.wohlben.qits.telemetry.issues.ErrorIssueHour;
import java.time.Instant;
import java.util.List;

/**
 * One error issue in full ({@code getErrorIssue}): every {@link ErrorIssueDto} field, plus the
 * newest stack trace and {@code hourly} — the hours of the asked-for window that had occurrences,
 * oldest first. Tickets made from the issue are not here: they live in qits-projects.
 */
public record ErrorIssueDetailDto(
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
    String dismissedBy,
    String sampleStacktrace,
    List<ErrorIssueHourDto> hourly) {

  public static ErrorIssueDetailDto of(ErrorIssue issue, List<ErrorIssueHour> hours) {
    return new ErrorIssueDetailDto(
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
        issue.dismissedBy(),
        issue.sampleStacktrace(),
        hours.stream().map(ErrorIssueHourDto::of).toList());
  }
}
