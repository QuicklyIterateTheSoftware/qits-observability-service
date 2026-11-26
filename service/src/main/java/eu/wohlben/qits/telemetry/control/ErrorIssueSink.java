package eu.wohlben.qits.telemetry.control;

import eu.wohlben.qits.telemetry.issues.ErrorIssueDelta;
import java.util.List;

/**
 * Where {@link ErrorIssueAggregator} writes a flush window. The bean is {@link
 * ErrorIssueFlushTransaction}; {@code ErrorIssueAggregatorTest} passes an in-memory one, so the
 * aggregator is tested as plain Java.
 */
public interface ErrorIssueSink {

  /** Whether there is anywhere to write: false when this process has no error-issue database. */
  boolean active();

  /** Writes one window, every fingerprint in it, as one unit: all of it or, by throwing, none. */
  void write(List<ErrorIssueDelta> window);
}
