package eu.wohlben.qits.telemetry.control;

import eu.wohlben.qits.telemetry.issues.ErrorIssueDelta;
import eu.wohlben.qits.telemetry.issues.FlushOutcome;
import eu.wohlben.qits.telemetry.issues.persistence.ErrorIssueStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * One flush window, written in one transaction: the store folds it into the issue rows, then
 * {@link ErrorIssueAnnouncer} announces what that opened and reopened — inside the same
 * transaction, which publishes the events only once it commits, so a rollback drops the issue rows
 * and their events together.
 */
@ApplicationScoped
public class ErrorIssueFlushTransaction implements ErrorIssueSink {

  @Inject ErrorIssueStore store;
  @Inject ErrorIssueAnnouncer announcer;
  @Inject TelemetryClock clock;

  private volatile Boolean active;

  @Override
  public boolean active() {
    Boolean known = active;
    if (known == null) {
      known = store.active();
      active = known;
    }
    return known;
  }

  @Override
  @Transactional
  public void write(List<ErrorIssueDelta> window) {
    FlushOutcome outcome = store.apply(window, clock.now());
    announcer.announce(outcome);
  }
}
