package eu.wohlben.qits.telemetry.issues;

import java.time.Instant;

/**
 * One error issue as the domain reads and merges it: every column of {@code error_issue} but the
 * optimistic {@code row_version}. Framework-free, so the merge rules in {@link ErrorIssues} are
 * plain functions a test calls without a database.
 *
 * <p>{@code fingerprint} is the issue's id. {@code exceptionType} and {@code location} are null when
 * the occurrences carried none. {@code message} is the normalised body, the issue's title. The
 * samples are the newest occurrence's; {@code firstSeen} / {@code lastSeen} are record times.
 */
public record ErrorIssue(
    String fingerprint,
    ErrorIssueState state,
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
    int regressions,
    Instant regressedAt,
    Instant dismissedAt,
    String dismissedBy) {}
