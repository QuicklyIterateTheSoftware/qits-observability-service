package eu.wohlben.qits.telemetry.api;

import eu.wohlben.qits.telemetry.control.ErrorFingerprinter;
import eu.wohlben.qits.telemetry.control.TelemetryClock;
import eu.wohlben.qits.telemetry.dto.ErrorIssueDetailDto;
import eu.wohlben.qits.telemetry.dto.ErrorIssueDto;
import eu.wohlben.qits.telemetry.error.BadRequestException;
import eu.wohlben.qits.telemetry.error.NotFoundException;
import eu.wohlben.qits.telemetry.error.ServiceUnavailableException;
import eu.wohlben.qits.telemetry.issues.ErrorIssue;
import eu.wohlben.qits.telemetry.issues.ErrorIssuePage;
import eu.wohlben.qits.telemetry.issues.ErrorIssueState;
import eu.wohlben.qits.telemetry.issues.ErrorIssues;
import eu.wohlben.qits.telemetry.issues.persistence.ErrorIssueStore;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The stored error issues (epic qits-2538): list them, read one with its hourly counts, dismiss
 * one. Under {@code /observability/api/error-issues}.
 *
 * <p>Reads admit every role the telemetry reads do. <b>Dismissing is a person's</b> (owner
 * ruling): {@code qits:admin}, {@code qits:admin-agent} and {@code qits:desk-admin}, and a
 * commissioned agent ({@code qits:agent}) is answered 403.
 *
 * <p>A process with no error-issue database ({@code %dev}) answers every door 503.
 */
@Path("/error-issues")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin", "qits:agent"})
public class ErrorIssueController {

  static final int DEFAULT_LIMIT = 200;
  static final int MAX_LIMIT = 1000;
  static final int DEFAULT_HOURS = 168;
  static final int MAX_HOURS = 720;

  @Inject ErrorIssueStore store;
  @Inject TelemetryClock clock;
  @Inject SecurityIdentity identity;

  public static record ListErrorIssuesRequest() {
    @Schema(name = "ListErrorIssuesResponse")
    public record Response(List<ErrorIssueDto> issues, long total, boolean truncated) {}
  }

  /**
   * Issues, newest {@code lastSeen} first. {@code state} is {@code OPEN} (the default), {@code
   * DISMISSED} or {@code ALL}; {@code service} keeps one {@code serviceName} exactly; {@code limit}
   * is 1–1000 (default 200), a 400 outside that.
   */
  @GET
  @Operation(operationId = "listErrorIssues")
  public ListErrorIssuesRequest.Response list(
      @QueryParam("state") @DefaultValue("OPEN") String state,
      @QueryParam("service") String service,
      @QueryParam("limit") @DefaultValue("" + DEFAULT_LIMIT) int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
    }
    ErrorIssueState wanted = state(state);
    requireStore();
    ErrorIssuePage page =
        store.list(wanted, service == null || service.isEmpty() ? null : service, limit);
    return new ListErrorIssuesRequest.Response(
        page.issues().stream().map(ErrorIssueDto::of).toList(), page.total(), page.truncated());
  }

  /**
   * One issue, with its stack trace and the hours of the last {@code hours} hours (1–720, default
   * 168; the current hour counts as the last) that had occurrences, oldest first.
   */
  @GET
  @Path("/{fingerprint}")
  @Operation(operationId = "getErrorIssue")
  public ErrorIssueDetailDto get(
      @PathParam("fingerprint") String fingerprint,
      @QueryParam("hours") @DefaultValue("" + DEFAULT_HOURS) int hours) {
    checkFingerprint(fingerprint);
    if (hours < 1 || hours > MAX_HOURS) {
      throw new BadRequestException("hours must be between 1 and " + MAX_HOURS);
    }
    requireStore();
    ErrorIssue issue = store.find(fingerprint).orElseThrow(() -> unknown(fingerprint));
    Instant since = ErrorIssues.hourOf(clock.now()).minus(Duration.ofHours(hours - 1L));
    return ErrorIssueDetailDto.of(issue, store.hours(fingerprint, since));
  }

  /**
   * Dismisses the issue: a person's act. Idempotent — dismissing a DISMISSED issue is a 200 that
   * changes nothing. The issue reopens by itself when its fingerprint occurs again.
   */
  @POST
  @Path("/{fingerprint}/dismiss")
  @Operation(operationId = "dismissErrorIssue")
  @RolesAllowed({"qits:admin", "qits:admin-agent", "qits:desk-admin"})
  public ErrorIssueDto dismiss(@PathParam("fingerprint") String fingerprint) {
    checkFingerprint(fingerprint);
    requireStore();
    String by = identity.isAnonymous() ? null : identity.getPrincipal().getName();
    return ErrorIssueDto.of(
        store.dismiss(fingerprint, by, clock.now()).orElseThrow(() -> unknown(fingerprint)));
  }

  private void requireStore() {
    if (!store.active()) {
      throw new ServiceUnavailableException("this process has no error-issue store");
    }
  }

  private static ErrorIssueState state(String value) {
    String upper = value == null ? "OPEN" : value.toUpperCase(Locale.ROOT);
    return switch (upper) {
      case "OPEN" -> ErrorIssueState.OPEN;
      case "DISMISSED" -> ErrorIssueState.DISMISSED;
      case "ALL" -> null;
      default -> throw new BadRequestException("state must be OPEN, DISMISSED or ALL");
    };
  }

  private static void checkFingerprint(String fingerprint) {
    if (!ErrorFingerprinter.isFingerprint(fingerprint)) {
      throw new BadRequestException("an error issue id is 32 lower-case hex characters");
    }
  }

  private static NotFoundException unknown(String fingerprint) {
    return new NotFoundException("no error issue " + fingerprint);
  }
}
