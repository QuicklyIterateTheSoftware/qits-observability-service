package eu.wohlben.qits.telemetry.api;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import eu.wohlben.qits.archrules.DeskAdminParityRules;
import java.util.Set;

/**
 * Parity between {@code qits:admin-agent} and {@code qits:desk-admin} over this module's doors
 * (epic qits-1621, the task "qits-observability-service: admit qits:desk-admin wherever
 * qits:admin-agent is"): {@link WorkspaceTelemetryController} and {@link TelemetryStreamSocket}.
 * The dossier's inventory records no refusal for this service — the telemetry query surface and
 * the live stream are both read-only, and neither writes a git ref or touches the desk's own
 * credentials — so the refusal set is empty: every route admitting {@code qits:admin-agent} here
 * must also admit {@code qits:desk-admin}.
 */
@AnalyzeClasses(
    packages = "eu.wohlben.qits.telemetry.api",
    importOptions = ImportOption.DoNotIncludeTests.class)
class DeskAdminParityTest {

  private static final Set<String> REFUSED = Set.of();

  @ArchTest
  static final ArchRule PARITY = DeskAdminParityRules.everyAdminAgentRouteAdmitsDeskAdmin(REFUSED);

  @ArchTest
  static final ArchRule REFUSALS = DeskAdminParityRules.refusedRoutesExistAndRefuse(REFUSED);
}
