package eu.wohlben.qits.telemetry.issues;

import java.time.Instant;
import java.util.Collections;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What one flush window saw of one fingerprint: the count, the per-hour counts, the time span, the
 * versions at either end of it and the newest samples. The aggregator in {@code service} builds it
 * from ERROR logs; {@link ErrorIssues} folds it into an issue.
 *
 * <p>{@code firstVersion} is the version of the earliest occurrence in the window and {@code
 * lastVersion} that of the newest. {@code sampleStacktrace} and {@code sampleTraceId} are the
 * newest occurrence's that had one, null when none did. {@code hourly} keys are hour starts.
 */
public record ErrorIssueDelta(
    String fingerprint,
    String serviceName,
    String exceptionType,
    String location,
    String message,
    String sampleMessage,
    String sampleStacktrace,
    String sampleTraceId,
    Instant firstSeen,
    Instant lastSeen,
    String firstVersion,
    String lastVersion,
    long occurrences,
    SortedMap<Instant, Long> hourly) {

  public ErrorIssueDelta {
    hourly = Collections.unmodifiableSortedMap(new TreeMap<>(hourly));
  }
}
