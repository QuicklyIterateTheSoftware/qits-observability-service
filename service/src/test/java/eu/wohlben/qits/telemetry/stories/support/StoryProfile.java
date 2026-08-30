package eu.wohlben.qits.telemetry.stories.support;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * <b>One launched qits-observability for the whole story catalogue</b>, and every seam a story
 * moves, declared once.
 *
 * <p>A {@code @TestProfile} is what failsafe launches a process for, so two profiles would be two
 * receivers — two boots, two in-memory buffers, and a diagram whose traffic landed in whichever
 * process happened to be running. Every story class therefore names this one, {@code
 * TelemetryBootstrapIT} included: it is a story class like the others and it owns the boot.
 *
 * <h2>There is strikingly little here, and that is this service's shape</h2>
 *
 * <p>qits-observability owns no tables. {@code .config/qits/deployments.yml} declares no {@code
 * resources:} at all, so there are no generic triples to supply, no datasource, no Flyway and no
 * data directory to redirect into {@code target/}. The whole of a qits-observability deployment is
 * <b>a process and two addresses</b>, and one of those two is the seam below.
 *
 * <p>It also has <b>no authentication of its own</b>. The edge authenticates and stamps {@code
 * X-Qits-User} / {@code X-Qits-Roles}; this process reads them. So there is no idp to stand up, no
 * tenant, no JWKS and no bearer anywhere in this catalogue — which is also why no story here asserts
 * a secret unleaked: there is none to leak.
 *
 * <h2>Both keys are RUNTIME keys</h2>
 *
 * <p>A packaged process takes its configuration as {@code -D} arguments on an artifact that was
 * already built, so a build-time key here would be silently ignored and the stories would prove
 * something other than what they say. Everything that makes this service what it is — {@code
 * quarkus.rest.path}, the MCP root path, the Quinoa ignore list, the 64M body limit, the four OTel
 * logging keys, and the store's caps — is left exactly as it ships.
 *
 * <ul>
 *   <li><b>{@code otel.exporter.otlp.endpoint}</b> — {@link StoryParent}, a real listener on
 *       loopback speaking the OTLP/HTTP collector surface. The key is deliberately the env-var-shaped
 *       spelling {@code OtelForwarder} reads and a supervising qits injects as {@code
 *       OTEL_EXPORTER_OTLP_ENDPOINT}, <b>not</b> a {@code quarkus.*} one: a rename on either side
 *       then fails here rather than in production. The parent starts here, before the application,
 *       and parks its port in a system property; that is also how a story method reaches the very
 *       server the launched process posts to.
 *   <li><b>{@code quarkus.otel.sdk.disabled}</b> — dark outside a deployment, like {@code %dev} and
 *       {@code %test}. See the gap below.
 * </ul>
 *
 * <h2>One thing is OFF, and for this service it is worth being precise about</h2>
 *
 * <p><b>This service's own OTLP exporter.</b> qits-observability is the platform's telemetry plane
 * and it is also a producer: the shipped configuration points its own SDK at {@code
 * http://qits-observability:8080/observability/api/otel} — <i>itself</i> — so that the one service
 * whose latency and errors would otherwise be invisible reports them too. The SDK is disabled here,
 * and the honest reasons are three:
 *
 * <ul>
 *   <li>The shipped endpoint names a host that resolves on {@code qits-net} and nowhere else, so a
 *       launched artifact would spend the run retrying an export into the void.
 *   <li>Pointing it at the <i>launched process itself</i> is out of reach, not merely undesirable: a
 *       {@code @QuarkusIntegrationTest} gets an ephemeral port, and a {@code @TestProfile}'s
 *       overrides are computed before the process exists. There is no address to hand it.
 *   <li>An exporter flushes on a schedule of its own, on its own thread, so its batches would draw
 *       arrows into whichever story happened to be open — a {@code networkHash} that never settles.
 * </ul>
 *
 * <p><b>So no story here covers this service's self-export</b>, and no story claims its absence
 * either: an {@code assertNoEdgesTo} over an exporter this profile switched off would be a claim
 * about the profile rather than about the service. The gap is stated in AGENTS.md rather than papered
 * over. What disabling it <i>buys</i> the catalogue is the other half of the same fact: the only
 * thing in the launched process that can post to the parent is the tee, which is what makes {@link
 * StoryParent}'s structural {@code from} true.
 */
public class StoryProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    // The parent starts HERE, before the application, and parks its port in a system property: a
    // test profile is instantiated in more than one classloader, and the property table is the one
    // thing every copy (and a story method's own reads) shares.
    return Map.of(
        "otel.exporter.otlp.endpoint", StoryParent.ensureStarted(),
        "quarkus.otel.sdk.disabled", "true");
  }
}
