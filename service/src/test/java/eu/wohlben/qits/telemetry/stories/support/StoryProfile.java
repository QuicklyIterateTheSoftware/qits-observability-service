package eu.wohlben.qits.telemetry.stories.support;

import eu.wohlben.qits.telemetry.security.BearerTokens;
import eu.wohlben.qits.telemetry.testdb.EmbeddedPg;
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
 * <p>qits-observability's one table family is its error issues (epic qits-2538): {@code
 * .config/qits/deployments.yml} declares {@code postgresql:db}, so a deployment injects the
 * platform's generic triple {@code QITS_RESOURCE_DB_URL} / {@code _USERNAME} / {@code _PASSWORD},
 * and this profile fills it exactly that way, with a database of the catalogue's own on the suite's
 * one embedded postgres ({@code testdb/EmbeddedPg}: a child process of this JVM, no docker). The
 * event bus's outbox (qits-2684, {@code postgresql:eventstream:…}) is filled the same way, from a
 * second database, with the bus dark. There is no data directory to redirect into {@code target/}.
 * The whole of a qits-observability deployment is <b>a process, its databases and two
 * addresses</b>, and one of those addresses is the seam below.
 *
 * <p>Identity is <b>a bearer token</b>, checked by the shipped OIDC tenant (epic qits-543: the
 * forwarded {@code X-Qits-*} headers authenticate nothing). There is still no idp to stand up: the
 * profile hands the tenant the suite's local verification key and the issuer the idp would publish,
 * exactly as {@code security/BearerAuthProfile} does, so a token is checked without a key fetch and
 * no story draws an arrow to an idp. The key pair exists for this suite only and protects nothing.
 *
 * <h2>Every key is a RUNTIME key</h2>
 *
 * <p>A packaged process takes its configuration as {@code -D} arguments on an artifact that was
 * already built, so a build-time key here would be silently ignored and the stories would prove
 * something other than what they say. Everything that makes this service what it is — {@code
 * quarkus.rest.path}, the Quinoa ignore list, the 64M body limit, the four OTel
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
 * http://localhost:8080/observability/api/otel} — <i>itself</i> — so that the one service
 * whose latency and errors would otherwise be invisible reports them too. The SDK is disabled here,
 * and the honest reasons are three:
 *
 * <ul>
 *   <li>The shipped endpoint is port 8080 on localhost, which a launched artifact on an ephemeral
 *       port does not listen on, so it would spend the run retrying an export into the void.
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

  /** Where the url is parked for whichever copy of this class is asked second. */
  private static final String URL_PROPERTY = "qits.test.userflows-it.db-url";

  /** The catalogue's own database on the one embedded postgres. */
  private static final String DATABASE = "observability_userflows_it";

  /** Where the outbox's url is parked, for the same reason. */
  private static final String EVENTSTREAM_URL_PROPERTY = "qits.test.userflows-it.eventstream-url";

  /** The catalogue's outbox database (qits-2684), beside its own. */
  private static final String EVENTSTREAM_DATABASE = "observability_userflows_it_eventstream";

  @Override
  public Map<String, String> getConfigOverrides() {
    // The parent starts HERE, before the application, and parks its port in a system property: a
    // test profile is instantiated in more than one classloader, and the property table is the one
    // thing every copy (and a story method's own reads) shares.
    return Map.ofEntries(
        // The platform's generic resource contract, exactly as a deployment fills it.
        Map.entry("QITS_RESOURCE_DB_URL", databaseUrl()),
        Map.entry("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER),
        Map.entry("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD),
        // The event bus's outbox (qits-2684), the second resource a deployment fills. The bus is
        // dark: no story draws an arrow to a qits-events nothing here stands up.
        Map.entry("QITS_RESOURCE_EVENTSTREAM_URL", eventstreamUrl()),
        Map.entry("QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER),
        Map.entry("QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD),
        Map.entry("qits.eventstream.enabled", "false"),
        Map.entry("otel.exporter.otlp.endpoint", StoryParent.ensureStarted()),
        Map.entry("quarkus.otel.sdk.disabled", "true"),
        // The bearer check, without an idp: the local key replaces discovery and the key fetch.
        Map.entry("quarkus.oidc.auth-server-url", ""),
        Map.entry("quarkus.oidc.public-key", BearerTokens.verificationKey()),
        Map.entry("quarkus.oidc.token.issuer", BearerTokens.ISSUER));
  }

  private static synchronized String eventstreamUrl() {
    String recorded = System.getProperty(EVENTSTREAM_URL_PROPERTY);
    if (recorded != null) {
      return recorded;
    }
    String url = EmbeddedPg.url(EVENTSTREAM_DATABASE);
    System.setProperty(EVENTSTREAM_URL_PROPERTY, url);
    return url;
  }

  private static synchronized String databaseUrl() {
    String recorded = System.getProperty(URL_PROPERTY);
    if (recorded != null) {
      return recorded;
    }
    // localhost resolves for the launched process too — it is a child of this JVM on this host.
    String url = EmbeddedPg.url(DATABASE);
    System.setProperty(URL_PROPERTY, url);
    return url;
  }
}
