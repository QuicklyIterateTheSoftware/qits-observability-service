package eu.wohlben.qits.telemetry.issues;

import java.time.Instant;

/** One hour of an issue's occurrences: {@code hour} is truncated to the hour, UTC. */
public record ErrorIssueHour(Instant hour, long count) {}
