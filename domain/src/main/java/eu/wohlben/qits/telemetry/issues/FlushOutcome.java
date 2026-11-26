package eu.wohlben.qits.telemetry.issues;

import java.time.Instant;
import java.util.List;

/**
 * What one write of a flush window changed, for whoever announces it (epic qits-2538's events,
 * qits-2684): the issues it inserted, and the DISMISSED issues it reopened. A count increase on an
 * OPEN issue is in neither list. Each fingerprint is in at most one of them.
 */
public record FlushOutcome(List<ErrorIssue> opened, List<Regression> regressed) {

  public FlushOutcome {
    opened = List.copyOf(opened);
    regressed = List.copyOf(regressed);
  }

  /**
   * A DISMISSED issue seen again. {@code issue} is the row after the write (OPEN, its regression
   * counted, its dismiss history kept); {@code occurredAt} and {@code serviceVersion} are the
   * regressing occurrence's — the earliest in the window.
   */
  public record Regression(ErrorIssue issue, Instant occurredAt, String serviceVersion) {}
}
