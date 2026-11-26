package eu.wohlben.qits.telemetry.control;

import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import eu.wohlben.qits.telemetry.events.ErrorIssueOpened;
import eu.wohlben.qits.telemetry.events.ErrorIssueRegressed;
import eu.wohlben.qits.telemetry.issues.ErrorIssue;
import eu.wohlben.qits.telemetry.issues.FlushOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Announces what a flush changed on qits-events (epic qits-2538, qits-2684): one {@link
 * ErrorIssueOpened} per issue the flush inserted ({@link FlushOutcome#opened()}), one {@link
 * ErrorIssueRegressed} per DISMISSED issue it reopened ({@link FlushOutcome#regressed()}), and
 * nothing for a count increase on an OPEN issue. A fingerprint is in at most one of the two lists,
 * so a flush publishes at most one event per fingerprint.
 *
 * <p><b>Called inside the flush transaction, published when it commits.</b> {@link
 * ErrorIssueFlushTransaction} calls {@link #announce} right after the store wrote the window. The
 * events are built there and handed to the transaction as a synchronization that publishes them
 * only on {@code STATUS_COMMITTED}: a rollback drops the issue rows and the events together.
 * Publishing directly at the call would not — {@link QitsEventBus#publish} attempts the PUT inline
 * and writes an undelivered event to its outbox in a transaction of its own ({@code
 * requiringNew}), so an event published mid-transaction outlives a rollback of the rows it
 * announces. After the commit, the bus owns the event the way it owns every other: delivered
 * inline, or an outbox row the sweeper retries.
 *
 * <p><b>Never fails the flush, and never logs at ERROR.</b> The bus does not throw; a failure past
 * it is logged at DEBUG here, because this service's own ERROR logs feed its own issues.
 */
@ApplicationScoped
public class ErrorIssueAnnouncer {

  private static final Logger LOG = Logger.getLogger(ErrorIssueAnnouncer.class);

  @Inject QitsEventBus bus;

  @Inject TransactionSynchronizationRegistry transactions;

  /** Called once per written window, inside its transaction; publishes once that commits. */
  public void announce(FlushOutcome outcome) {
    List<QitsEvent> events = events(outcome);
    if (events.isEmpty()) {
      return;
    }
    transactions.registerInterposedSynchronization(
        new Synchronization() {
          @Override
          public void beforeCompletion() {}

          @Override
          public void afterCompletion(int status) {
            if (status == Status.STATUS_COMMITTED) {
              events.forEach(ErrorIssueAnnouncer.this::publishQuietly);
            }
          }
        });
  }

  /** The events one flush outcome announces: the opened issues first, then the regressions. */
  static List<QitsEvent> events(FlushOutcome outcome) {
    List<QitsEvent> events = new ArrayList<>();
    for (ErrorIssue issue : outcome.opened()) {
      events.add(
          new ErrorIssueOpened(
              null,
              issue.firstSeen(),
              issue.fingerprint(),
              issue.serviceName(),
              issue.firstVersion(),
              issue.exceptionType(),
              issue.location(),
              issue.message(),
              issue.sampleTraceId()));
    }
    for (FlushOutcome.Regression regression : outcome.regressed()) {
      ErrorIssue issue = regression.issue();
      events.add(
          new ErrorIssueRegressed(
              null,
              regression.occurredAt(),
              issue.fingerprint(),
              issue.serviceName(),
              regression.serviceVersion(),
              issue.exceptionType(),
              issue.location(),
              issue.message(),
              issue.sampleTraceId(),
              issue.dismissedAt(),
              issue.regressions(),
              issue.occurrences()));
    }
    return events;
  }

  private void publishQuietly(QitsEvent event) {
    try {
      bus.publish(event);
    } catch (RuntimeException e) {
      LOG.debugf(e, "%s %s was not published", event.signature(), event.eventId());
    }
  }
}
