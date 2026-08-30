package eu.wohlben.qits.telemetry.stories.support;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;

import eu.wohlben.qits.userflows.Labels;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

/**
 * The one launched qits-observability, addressed the way each of its surfaces is addressed — and
 * named the way every diagram in this catalogue names it.
 *
 * <h2>One process, one port, three surfaces</h2>
 *
 * <p>Everything is under {@code /observability}, because {@code quarkus.rest.path} puts it there and
 * the edge routes the prefix here verbatim:
 *
 * <ul>
 *   <li>{@link #INGEST} — the OTLP/HTTP receiver. {@code @PermitAll}, protobuf only, and its callers
 *       are exporter SDKs that carry no identity and could not be given one.
 *   <li>{@link #QUERY} — the operator's read surface. {@code @RolesAllowed("qits:admin")}, resolved
 *       from two headers the edge stamps and strips.
 *   <li>{@link #READY} — Quarkus' own non-application root, {@code /observability/q}.
 * </ul>
 *
 * <p>The SPA Quinoa would serve at the host root is not a surface any story here drives: every story
 * runs against a build with {@code -Dquarkus.quinoa=false}, so there is no bundle to serve.
 *
 * <h2>The shipped tap's default skip is right here, and it was checked</h2>
 *
 * <p>{@link eu.wohlben.qits.userflows.NetworkTaps#restAssured(String)} skips any path carrying a
 * {@code /q/} <b>segment</b> rather than a leading one. This service's probes live at {@code
 * /observability/q/health/ready} — nested under {@code /observability}, which is exactly the case
 * the segment rule exists for. No route of this service can contain a {@code /q/} segment either:
 * the ingest paths are fixed, the query paths are fixed, and the only free segment anywhere is a
 * trace id, which is hex. So no story class overrides the predicate.
 *
 * <h2>Two headers are the whole of identity, and there is no credential in this catalogue</h2>
 *
 * <p>Authentication terminates at the edge; this service resolves a principal from {@code
 * X-Qits-User} / {@code X-Qits-Roles} and authenticates nothing. There is no bearer, no client
 * secret and no token to mint, so no story here has anything to assert unleaked — the two headers
 * are not secrets, they are the trusted namespace the edge strips from every inbound request.
 *
 * <p>What the wire says about who is asking is therefore <b>two headers</b>, which is why every
 * story names its actor before it calls: on the wire an operator, an ungranted reader and an
 * exporter differ by those headers alone, and a tap cannot read a narrative role.
 *
 * <h2>Labels: what survives and what is rewritten</h2>
 *
 * <p>{@link Labels} rewrites a whole path segment it can tell was generated. In this catalogue that
 * is exactly one thing and it matters: a <b>trace id</b> is 32 lowercase hex characters and a whole
 * segment, so {@code …/traces/0af765…} labels as {@code …/traces/{digest}} and a story's {@code
 * networkHash} does not move with the fixture. Everything else in a path here is authored — {@code
 * otel}, {@code v1}, {@code traces}, {@code slow-spans} — and survives verbatim, which is right:
 * those are the URLs an exporter is pointed at and a screen calls.
 *
 * <p><b>A query string never reaches an incoming label.</b> The shipped tap labels {@code METHOD
 * <scrubbed path> -> <status>} and drops the query entirely, and that is load-bearing here: which
 * bucket a read addressed travels as {@code ?source=} or as {@code ?repositoryId=&workspaceId=}, so
 * a full bucket and an empty one draw the <i>same</i> arrow. That is the claim rather than a loss —
 * the route and the status really are the same, and only what came back differs. Where the query
 * would carry information the diagram needs, it is on the <b>outgoing</b> side, and {@link
 * StoryParent} keeps it there.
 */
public final class StoryTarget {

  /** How every diagram in this catalogue names the service under test, on both sides of an edge. */
  public static final String SERVICE = "qits-observability";

  // --- the four initiators ------------------------------------------------------------------
  // Named rather than derived, because the wire cannot tell them apart: an exporter carries no
  // headers, and the three readers differ only in the two the edge stamps.

  /** An OTel SDK inside a workspace container or a platform service. Carries no identity, ever. */
  public static final String EXPORTER = "a workspace exporter";

  /** A person the edge named and granted {@code qits:admin}. */
  public static final String OPERATOR = "a platform operator";

  /** A caller the edge never named — no {@code X-Qits-User} at all. */
  public static final String UNNAMED_READER = "an unnamed reader";

  /** A caller the edge DID name, carrying roles that do not include {@code qits:admin}. */
  public static final String UNGRANTED_READER = "a reader without the grant";

  // --- the wire paths, spelled in full ----------------------------------------------------------

  /** {@code quarkus.rest.path} plus the gateway prefix — part of the path this process serves. */
  public static final String BASE = "/observability";

  /** The OTLP receiver's root. SDKs are pointed at {@code …/api/otel} and append {@code /v1/…}. */
  public static final String INGEST = BASE + "/api/otel/v1";

  public static final String TRACE_INGEST = INGEST + "/traces";

  public static final String LOG_INGEST = INGEST + "/logs";

  public static final String METRIC_INGEST = INGEST + "/metrics";

  /** The operator's read surface. */
  public static final String QUERY = BASE + "/api/telemetry";

  public static final String STORE = QUERY + "/store";

  public static final String SOURCES = QUERY + "/sources";

  public static final String ERRORS = QUERY + "/errors";

  public static final String TRACES = QUERY + "/traces";

  public static final String LOGS = QUERY + "/logs";

  public static final String SLOW_SPANS = QUERY + "/slow-spans";

  public static final String METRICS = QUERY + "/metrics";

  /** The readiness endpoint, which the tap skips — see the class javadoc. */
  public static final String READY = BASE + "/q/health/ready";

  /** One trace's page. {@code traceId} is the identity of the thing fetched, so it is a segment. */
  public static String trace(String traceId) {
    return TRACES + "/" + traceId;
  }

  // --- what a caller carries --------------------------------------------------------------------

  /** The one wire format this receiver speaks. OTLP/JSON and gRPC are deliberately not implemented. */
  public static final String PROTOBUF = "application/x-protobuf";

  /** qits-auth-core's defaults, and the edge's reserved namespace. */
  public static final String USER_HEADER = "X-Qits-User";

  public static final String ROLES_HEADER = "X-Qits-Roles";

  public static final String ADMIN_ROLE = "qits:admin";

  private StoryTarget() {}

  /**
   * An OTLP post shaped like a real exporter's: the content type <b>bare</b>, with no charset
   * parameter.
   *
   * <p>RestAssured appends one by default, and {@code OtelForwarder} relays the header it received —
   * so without this the tee assertion would be checking RestAssured's spelling rather than the
   * service's relay. {@code OtelTeeTest} disables the same default for the same reason.
   */
  public static RequestSpecification otlp() {
    return given()
        .config(
            RestAssured.config()
                .encoderConfig(
                    encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false)))
        .contentType(PROTOBUF);
  }

  /** A read as the edge presents one: a name and the roles asserted from the same session. */
  public static RequestSpecification operator() {
    return given().header(USER_HEADER, "ops").header(ROLES_HEADER, ADMIN_ROLE);
  }

  /**
   * A read by somebody the edge named and did not grant. The identity is fine; the grant is not, and
   * the two must not collapse into one answer.
   */
  public static RequestSpecification ungranted() {
    return given().header(USER_HEADER, "mallory").header(ROLES_HEADER, "qits:reader");
  }

  // --- what an assertion has to spell ------------------------------------------------------------

  /**
   * The label the shipped RestAssured tap gives an incoming request — {@code METHOD <scrubbed path>
   * -> <status>}, scrubbed through the very function the tap uses, so an assertion and an
   * observation can never disagree about what a generated segment became.
   */
  public static String served(String method, String path, int status) {
    return Labels.scrub(method + " " + path + " -> " + status);
  }

  /** {@code GET <path> -> <status>} — the shape of every edge a reader draws here. */
  public static String read(String path, int status) {
    return served("GET", path, status);
  }

  /** {@code POST <path> -> <status>} — the shape of every edge an exporter draws here. */
  public static String exported(String path, int status) {
    return served("POST", path, status);
  }
}
