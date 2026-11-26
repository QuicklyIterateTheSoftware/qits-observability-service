package eu.wohlben.qits.telemetry.control;

import eu.wohlben.qits.telemetry.issues.FlushOutcome;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Announces what a flush changed: the issues it opened and the dismissed issues it reopened.
 *
 * <p><b>The seam for qits-2684</b> (dossier page "Events on qits-events"). {@link
 * ErrorIssueFlushTransaction} calls {@link #announce} inside the flush transaction, right after the
 * store wrote the window, so an event published here through the eventstream outbox commits or
 * fails with the issue rows: {@code ErrorIssueOpened} per {@link FlushOutcome#opened()} entry,
 * {@code ErrorIssueRegressed} per {@link FlushOutcome#regressed()} entry, and nothing for a count
 * increase on an OPEN issue. Until then it announces nothing.
 */
@ApplicationScoped
public class ErrorIssueAnnouncer {

  /** Called once per written window, inside its transaction. Must not swallow a failure it means. */
  public void announce(FlushOutcome outcome) {
    // qits-2684 publishes ErrorIssueOpened / ErrorIssueRegressed here.
  }
}
