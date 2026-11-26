package eu.wohlben.qits.telemetry.issues.entity;

import eu.wohlben.qits.telemetry.issues.ErrorIssue;
import eu.wohlben.qits.telemetry.issues.ErrorIssueState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * A row of {@code error_issue} (V1). The store converts it to and from {@link ErrorIssue}, which is
 * what every rule and every reader sees; nothing outside {@code persistence} touches this class.
 * Served by {@code listErrorIssues}, {@code getErrorIssue} and {@code dismissErrorIssue}.
 */
@Entity
@Table(name = "error_issue")
public class ErrorIssueEntity {

  @Id
  @Column(name = "fingerprint", columnDefinition = "char(32)", length = 32)
  public String fingerprint;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  public ErrorIssueState state;

  @Column(name = "service_name", nullable = false, columnDefinition = "text")
  public String serviceName;

  @Column(name = "exception_type", columnDefinition = "text")
  public String exceptionType;

  @Column(name = "location", columnDefinition = "text")
  public String location;

  @Column(name = "message", nullable = false, columnDefinition = "text")
  public String message;

  @Column(name = "sample_message", columnDefinition = "text")
  public String sampleMessage;

  @Column(name = "sample_stacktrace", columnDefinition = "text")
  public String sampleStacktrace;

  @Column(name = "sample_trace_id", length = 32)
  public String sampleTraceId;

  @Column(name = "first_seen", nullable = false)
  public Instant firstSeen;

  @Column(name = "last_seen", nullable = false)
  public Instant lastSeen;

  @Column(name = "first_version", columnDefinition = "text")
  public String firstVersion;

  @Column(name = "last_version", columnDefinition = "text")
  public String lastVersion;

  @Column(name = "occurrences", nullable = false)
  public long occurrences;

  @Column(name = "regressions", nullable = false)
  public int regressions;

  @Column(name = "regressed_at")
  public Instant regressedAt;

  @Column(name = "dismissed_at")
  public Instant dismissedAt;

  @Column(name = "dismissed_by", columnDefinition = "text")
  public String dismissedBy;

  @Version
  @Column(name = "row_version", nullable = false)
  public long rowVersion;

  /** The row as the domain reads it. */
  public ErrorIssue toIssue() {
    return new ErrorIssue(
        fingerprint,
        state,
        serviceName,
        exceptionType,
        location,
        message,
        sampleMessage,
        sampleStacktrace,
        sampleTraceId,
        firstSeen,
        lastSeen,
        firstVersion,
        lastVersion,
        occurrences,
        regressions,
        regressedAt,
        dismissedAt,
        dismissedBy);
  }

  /** Every column but the id and the version, from the issue. */
  public void assign(ErrorIssue issue) {
    fingerprint = issue.fingerprint();
    state = issue.state();
    serviceName = issue.serviceName();
    exceptionType = issue.exceptionType();
    location = issue.location();
    message = issue.message();
    sampleMessage = issue.sampleMessage();
    sampleStacktrace = issue.sampleStacktrace();
    sampleTraceId = issue.sampleTraceId();
    firstSeen = issue.firstSeen();
    lastSeen = issue.lastSeen();
    firstVersion = issue.firstVersion();
    lastVersion = issue.lastVersion();
    occurrences = issue.occurrences();
    regressions = issue.regressions();
    regressedAt = issue.regressedAt();
    dismissedAt = issue.dismissedAt();
    dismissedBy = issue.dismissedBy();
  }
}
