package eu.wohlben.qits.telemetry.api;

import eu.wohlben.qits.telemetry.dto.ErrorIssueDetailDto;
import eu.wohlben.qits.telemetry.dto.ErrorIssueDto;
import eu.wohlben.qits.telemetry.dto.ErrorIssueHourDto;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Native-image reflection registration for every type Jackson writes on {@link
 * ErrorIssueController}'s doors — the counterpart of {@code TelemetryStreamFrame}'s registration
 * for the live stream. On a JVM everything binds regardless; the failure this guards is a 500 in
 * the native binary behind a green suite.
 *
 * <p><b>A new served type joins this list in the commit that adds it</b>: {@code
 * ErrorIssueWireReflectionTest} walks the controller's answers and fails naming any it misses.
 */
@RegisterForReflection(
    targets = {
      ErrorIssueController.ListErrorIssuesRequest.Response.class,
      ErrorIssueDto.class,
      ErrorIssueDetailDto.class,
      ErrorIssueHourDto.class
    })
public final class ErrorIssueWireReflection {

  private ErrorIssueWireReflection() {}
}
