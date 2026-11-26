package eu.wohlben.qits.telemetry.issues;

import java.util.List;

/** A bounded list of issues: {@code total} counts every match, {@code truncated} says some are cut. */
public record ErrorIssuePage(List<ErrorIssue> issues, long total, boolean truncated) {

  public ErrorIssuePage {
    issues = List.copyOf(issues);
  }
}
