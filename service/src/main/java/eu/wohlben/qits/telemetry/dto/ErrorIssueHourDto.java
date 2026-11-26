package eu.wohlben.qits.telemetry.dto;

import eu.wohlben.qits.telemetry.issues.ErrorIssueHour;
import java.time.Instant;

/** One hour of an issue's occurrences: {@code hour} is the hour's start, UTC. */
public record ErrorIssueHourDto(Instant hour, long count) {

  public static ErrorIssueHourDto of(ErrorIssueHour hour) {
    return new ErrorIssueHourDto(hour.hour(), hour.count());
  }
}
