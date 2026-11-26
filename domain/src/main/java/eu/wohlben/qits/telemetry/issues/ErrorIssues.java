package eu.wohlben.qits.telemetry.issues;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The rules an issue changes by, as plain functions (epic qits-2538, dossier page "Error issue
 * store and API"). The store applies them to the rows it locks; a test applies them to records.
 */
public final class ErrorIssues {

  private ErrorIssues() {}

  /** The hour an instant falls in, UTC: what {@code error_issue_hour.hour} holds. */
  public static Instant hourOf(Instant at) {
    return at.truncatedTo(ChronoUnit.HOURS);
  }

  /** A fingerprint seen for the first time: an OPEN issue holding exactly the window's counts. */
  public static ErrorIssue open(ErrorIssueDelta delta) {
    return new ErrorIssue(
        delta.fingerprint(),
        ErrorIssueState.OPEN,
        delta.serviceName(),
        delta.exceptionType(),
        delta.location(),
        delta.message(),
        delta.sampleMessage(),
        delta.sampleStacktrace(),
        delta.sampleTraceId(),
        delta.firstSeen(),
        delta.lastSeen(),
        delta.firstVersion(),
        delta.lastVersion(),
        delta.occurrences(),
        0,
        null,
        null,
        null);
  }

  /** {@link #merge}'s answer: the issue after the window, and whether that reopened it. */
  public record Merged(ErrorIssue issue, boolean reopened) {}

  /**
   * An existing issue after one more window. {@code occurrences} adds up, {@code lastSeen} is the
   * later of the two and {@code firstSeen} the earlier. When the window's newest occurrence is the
   * issue's newest, it brings {@code lastVersion} and the samples; a stack trace or trace id the
   * window lacks keeps the issue's. The title, the service, the type and the location are the
   * issue's from its first window and never move.
   *
   * <p><b>A DISMISSED issue becomes OPEN</b>, with {@code regressions + 1} and {@code regressedAt =
   * now}; {@code dismissedAt} and {@code dismissedBy} stay, as its history.
   */
  public static Merged merge(ErrorIssue issue, ErrorIssueDelta delta, Instant now) {
    boolean newest = !delta.lastSeen().isBefore(issue.lastSeen());
    boolean earliest = delta.firstSeen().isBefore(issue.firstSeen());
    boolean reopened = issue.state() == ErrorIssueState.DISMISSED;
    return new Merged(
        new ErrorIssue(
            issue.fingerprint(),
            ErrorIssueState.OPEN,
            issue.serviceName(),
            issue.exceptionType(),
            issue.location(),
            issue.message(),
            newest ? delta.sampleMessage() : issue.sampleMessage(),
            newest && delta.sampleStacktrace() != null
                ? delta.sampleStacktrace()
                : issue.sampleStacktrace(),
            newest && delta.sampleTraceId() != null ? delta.sampleTraceId() : issue.sampleTraceId(),
            earliest ? delta.firstSeen() : issue.firstSeen(),
            newest ? delta.lastSeen() : issue.lastSeen(),
            earliest || issue.firstVersion() == null ? delta.firstVersion() : issue.firstVersion(),
            newest ? delta.lastVersion() : issue.lastVersion(),
            issue.occurrences() + delta.occurrences(),
            reopened ? issue.regressions() + 1 : issue.regressions(),
            reopened ? now : issue.regressedAt(),
            issue.dismissedAt(),
            issue.dismissedBy()),
        reopened);
  }

  /**
   * The issue dismissed by {@code by} at {@code now}. Idempotent: a DISMISSED issue is returned as
   * it is, its first dismissal kept.
   */
  public static ErrorIssue dismiss(ErrorIssue issue, String by, Instant now) {
    if (issue.state() == ErrorIssueState.DISMISSED) {
      return issue;
    }
    return new ErrorIssue(
        issue.fingerprint(),
        ErrorIssueState.DISMISSED,
        issue.serviceName(),
        issue.exceptionType(),
        issue.location(),
        issue.message(),
        issue.sampleMessage(),
        issue.sampleStacktrace(),
        issue.sampleTraceId(),
        issue.firstSeen(),
        issue.lastSeen(),
        issue.firstVersion(),
        issue.lastVersion(),
        issue.occurrences(),
        issue.regressions(),
        issue.regressedAt(),
        now,
        by);
  }
}
