package eu.wohlben.qits.telemetry.control;

import eu.wohlben.qits.telemetry.issues.persistence.ErrorIssueStore;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The hourly purge of {@code error_issue_hour}: rows older than {@value #RETENTION_KEY} (default
 * {@code P30D}) go. The issue rows are never purged, and neither are their totals and first/last
 * seen (owner ruling 2026-11-22: issues never expire).
 */
@ApplicationScoped
public class ErrorIssuePurge {

  static final String RETENTION_KEY = "qits.observability.error-issues.hour-retention";

  private static final Logger LOG = Logger.getLogger(ErrorIssuePurge.class);

  @Inject ErrorIssueStore store;
  @Inject TelemetryClock clock;

  @ConfigProperty(name = RETENTION_KEY, defaultValue = "P30D")
  Duration retention;

  @Scheduled(
      identity = "error-issue-hour-purge",
      every = "${qits.observability.error-issues.purge-interval:1h}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void scheduled() {
    try {
      purge();
    } catch (RuntimeException e) {
      // WARN, never ERROR: this service's own ERROR logs feed its own issues.
      LOG.warnf("error-issue hour purge failed: %s", e.toString());
    }
  }

  /** Purges now and answers how many hour rows went; 0 when there is no error-issue database. */
  public int purge() {
    if (!store.active()) {
      return 0;
    }
    int purged = store.purgeHoursBefore(clock.now().minus(retention));
    LOG.debugf("purged %d error-issue hour rows older than %s", purged, retention);
    return purged;
  }
}
