package eu.wohlben.qits.telemetry.stories.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.userflows.Labels;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * <b>The parent qits-observability</b> — the one thing this service dials out to — as an in-JVM
 * stand-in, plus the <b>outgoing</b> tap that draws what the launched process posted it.
 *
 * <h2>Why this is a stand-in of one protocol rather than a generic mock</h2>
 *
 * <p>This catalogue replaced {@code qits-service-mock}'s generic {@code MockService} with this
 * class, and the reason is the same one qits-docs gives for its {@code StoryStore}: a canned-JSON
 * mock keyed on an exact {@code METHOD path} pair cannot express the stories that matter here.
 *
 * <ul>
 *   <li><b>It speaks OTLP.</b> A real parent answers {@code application/x-protobuf} carrying an
 *       empty {@code Export*ServiceResponse}, which is what this answers — built from the very proto
 *       bindings the receiver decodes with. A JSON body would make every ingest story prove a parent
 *       that does not exist.
 *   <li><b>A parent can be down, and that is a story.</b> Telemetry is best-effort upstream, so the
 *       interesting flows are the ones where the far side <i>fails</i>: refusing, silent, and slow.
 *       {@code MockService} can only be armed from the classloader that started it — a story method
 *       holds an attached handle, which refuses to stub — so the faults here are <b>file-armed</b>,
 *       exactly as the sibling catalogues arm theirs.
 *   <li><b>The headers are the assertion.</b> The tee's whole contract is "relayed as received", so
 *       {@code Content-Type} and {@code Content-Encoding} are what a story reads back. They are
 *       recorded per request rather than reachable only through a control round trip.
 * </ul>
 *
 * <p><b>One stated liberty.</b> Nothing here decodes the body it was posted. A recording carries
 * method, target, status and the two headers, never bytes — so "the parent got the same bytes" is
 * not a claim any story in this catalogue makes. {@code OtelTeeTest} pins byte-verbatim relay
 * against the suite's JVM with a real body-capturing server; what is proven <i>here</i> and nowhere
 * else is that the relay happens at all from the packaged process, and what it carries on its head.
 *
 * <h2>The recording, and who each edge is FROM</h2>
 *
 * <p>Every answered request is appended to a file as {@code METHOD\tTARGET\tSTATUS\tTYPE\tENCODING}
 * — <b>before</b> the response is written, so a line is on disk by the time its effect is
 * observable, and so a request that is deliberately never answered still leaves one. Tabs rather
 * than spaces, because a relayed {@code Content-Type} may carry a charset parameter and a space with
 * it.
 *
 * <p>{@code TARGET} carries the <b>query</b>, and today there never is one: {@link
 * eu.wohlben.qits.telemetry.api.OtelForwarder} builds {@code <endpoint>/v1/<signal>} and nothing
 * else, because an OTLP endpoint takes no parameters. It is kept anyway — a forwarder that grew one
 * would show it on the outgoing label, which is the only side a query can reach at all (the shipped
 * inbound tap drops it).
 *
 * <p><b>{@code from} is decided by the method and the path prefix, and that is a structural fact.</b>
 * A {@code POST} under {@code /v1/} is an OTLP export, and the only OTLP client in the launched
 * process is the tee — its own SDK is dark (see {@link StoryProfile}). Anything else recorded here
 * is drawn from {@link #UNEXPECTED_CLIENT} rather than quietly attributed to the tee, so a stray
 * caller appears on a diagram as a stray caller and every {@code assertEdgeCount} notices it.
 *
 * <p><b>There is no floor.</b> The recording is wiped when the stub starts and the stub starts inside
 * this run, so everything in it belongs to this run — which is what lets {@code TelemetryBootstrapIT}
 * claim that starting this receiver costs the parent nothing. A floor taken at the first {@code
 * @BeforeAll} would swallow exactly the boot traffic that story exists to say is absent.
 *
 * <h2>Stateless per request, with three file-armed exceptions</h2>
 *
 * <p>The stub holds no state a story sets in memory: the arming story and the running server are in
 * different classloaders, and no story-controlled value reaches these paths in a way an outage could
 * key on — "the parent is refusing tonight" is a property of the parent, not of the signal somebody
 * exported. Each fault is a file, written in a {@code try}/{@code finally} that always clears it,
 * wiped again when the stub starts, and read fresh on every request. <b>Prefixes must not overlap</b>
 * — a fault armed at {@code /v1/} would catch every signal — so a story arms one signal at a time and
 * sequences its arms.
 */
public final class StoryParent {

  /** How every diagram in this catalogue names the far side. */
  public static final String SERVICE_NAME = "parent-qits-observability";

  /**
   * The initiator of anything recorded here that is not an OTLP export. Nothing should ever draw
   * it; it exists so that if something does, the diagram says so instead of blaming the tee.
   */
  public static final String UNEXPECTED_CLIENT = "an unexpected client";

  /** What a refused route answers: the parent is up and cannot take the copy. */
  public static final int REFUSED_STATUS = 503;

  /** What an edge's label says where the parent accepted the connection and then said nothing. */
  public static final String NO_ANSWER = "no answer";

  /** The three signal routes an OTLP endpoint serves — the paths the tee appends to its endpoint. */
  public static final String TRACES = "/v1/traces";

  public static final String LOGS = "/v1/logs";

  public static final String METRICS = "/v1/metrics";

  /** The status parked in the recording for a request that got no answer at all. */
  private static final int HUNG_UP = 0;

  /** How long a story is willing to wait for a fire-and-forget forward to land. */
  private static final long AWAIT_MILLIS = 15_000;

  private static final String PORT_PROPERTY = "qits.test.story-parent.port";

  private static final String SOURCE_ID = "story-parent";

  private static final String ABSENT = "-";

  private static final Path ROOT = Path.of("target", "story-parent");

  /** The recording: one line per request the parent received, the shape an access log has. */
  private static final Path ACCESS_LOG = ROOT.resolve("access.log");

  /** Which path prefixes answer {@link #REFUSED_STATUS} right now. */
  private static final Path REFUSALS = ROOT.resolve("refusals");

  /** Which path prefixes are accepted and then dropped without a byte. */
  private static final Path HANGUPS = ROOT.resolve("hangups");

  /** Which path prefixes answer only after a delay, and how long a one. */
  private static final Path DELAYS = ROOT.resolve("delays");

  private static final Object LOCK = new Object();

  private static boolean registered;

  private static int harvested;

  private static final List<NetworkEdge> EDGES = new ArrayList<>();

  private StoryParent() {}

  /** One request the parent recorded: what it was asked, what it answered, what it was carrying. */
  public record Forward(String method, String target, int status, String type, String encoding) {

    /** Whether this forward was accepted and then dropped without an answer. */
    public boolean unanswered() {
      return status == HUNG_UP;
    }
  }

  /** One answer, assembled before anything is written so the recording carries the real status. */
  private record Answer(int status, String contentType, byte[] body) {}

  // --- the server -------------------------------------------------------------------------------

  /**
   * Start the stub once per JVM and park its port, wiping whatever an earlier run left behind.
   * Called from {@link StoryProfile}, which is the only place that knows the parent's address in
   * time to hand it to the launched artifact.
   */
  public static synchronized String ensureStarted() {
    String port = System.getProperty(PORT_PROPERTY);
    if (port != null) {
      return baseUrl(Integer.parseInt(port));
    }
    wipe();
    HttpServer server;
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("could not start the parent collector stub", e);
    }
    server.createContext("/", StoryParent::handle);
    // An executor, unlike the sibling catalogues' stubs, because one arm of the faults story makes
    // this server DELIBERATELY SLOW: com.sun's default runs every handler on the dispatcher thread,
    // so a two-second sleep there would also stop the server accepting anything else. Daemon
    // threads, so a pool left holding nothing never keeps the failsafe JVM alive.
    server.setExecutor(
        Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "story-parent");
              thread.setDaemon(true);
              return thread;
            }));
    server.start();
    System.setProperty(PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
    return baseUrl(server.getAddress().getPort());
  }

  /** The parent's base url — what {@code otel.exporter.otlp.endpoint} is pointed at. */
  public static String baseUrl() {
    String port = System.getProperty(PORT_PROPERTY);
    if (port == null) {
      throw new IllegalStateException("the parent collector stub was never started in this JVM");
    }
    return baseUrl(Integer.parseInt(port));
  }

  private static String baseUrl(int port) {
    return "http://localhost:" + port;
  }

  private static void handle(HttpExchange exchange) throws IOException {
    URI uri = exchange.getRequestURI();
    String path = uri.getPath();
    String query = uri.getRawQuery();
    String method = exchange.getRequestMethod();
    String target = query == null ? path : path + "?" + query;
    String type = header(exchange, "Content-Type");
    String encoding = header(exchange, "Content-Encoding");

    // Drained before anything else: a client that is still writing when the far side walks away
    // gets a reset rather than the clean silence the hang-up arm is about.
    exchange.getRequestBody().readAllBytes();

    long delay = armedDelay(path);
    if (delay > 0) {
      sleep(delay);
    }
    if (isArmed(HANGUPS, path)) {
      // Recorded first: the export DID reach the parent, and that is exactly what distinguishes
      // "could not be reached" from "answered something else".
      record(method, target, HUNG_UP, type, encoding);
      exchange.close();
      return;
    }
    Answer answer = isArmed(REFUSALS, path) ? refused() : route(method, path);

    record(method, target, answer.status(), type, encoding);
    if (answer.contentType() != null) {
      exchange.getResponseHeaders().set("Content-Type", answer.contentType());
    }
    if (answer.body().length == 0) {
      // com.sun reads a length of 0 as "unspecified, chunked"; -1 is how an empty body is spelled,
      // and an empty body is exactly what a successful OTLP export answers.
      exchange.sendResponseHeaders(answer.status(), -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(answer.status(), answer.body().length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(answer.body());
    }
  }

  /**
   * The OTLP/HTTP collector surface: three signal routes, each answering an empty {@code
   * Export*ServiceResponse} as protobuf — which is what a real qits-observability answers, built
   * from the same bindings it decodes with.
   */
  private static Answer route(String method, String path) {
    if (!"POST".equals(method)) {
      return notFound();
    }
    return switch (path) {
      case TRACES -> new Answer(
          200, StoryTarget.PROTOBUF, ExportTraceServiceResponse.getDefaultInstance().toByteArray());
      case LOGS -> new Answer(
          200, StoryTarget.PROTOBUF, ExportLogsServiceResponse.getDefaultInstance().toByteArray());
      case METRICS -> new Answer(
          200,
          StoryTarget.PROTOBUF,
          ExportMetricsServiceResponse.getDefaultInstance().toByteArray());
      default -> notFound();
    };
  }

  private static Answer notFound() {
    return new Answer(
        404,
        "application/json",
        "{\"message\":\"the parent collector serves /v1/<signal> only\"}"
            .getBytes(StandardCharsets.UTF_8));
  }

  private static Answer refused() {
    return new Answer(
        REFUSED_STATUS,
        "application/json",
        "{\"message\":\"the parent collector cannot take a copy right now\"}"
            .getBytes(StandardCharsets.UTF_8));
  }

  private static String header(HttpExchange exchange, String name) {
    String value = exchange.getRequestHeaders().getFirst(name);
    return value == null || value.isBlank() ? null : value;
  }

  // --- the three armed faults -------------------------------------------------------------------

  /**
   * Make every path starting with {@code prefix} answer {@link #REFUSED_STATUS} — the parent is up
   * and cannot take the copy — until {@link #answerNormally()} is called.
   *
   * <p><b>Always in a {@code try}/{@code finally}.</b> An outage that outlived its story would be a
   * broken parent in somebody else's diagram, and the two would look exactly alike.
   */
  public static void refuse(String prefix) {
    write(REFUSALS, prefix + "\n");
  }

  /**
   * Make every path starting with {@code prefix} be accepted and then dropped without a byte — the
   * arm the forwarder reaches through an {@code IOException} rather than through a status it read.
   * Same discipline as {@link #refuse}.
   */
  public static void hangUp(String prefix) {
    write(HANGUPS, prefix + "\n");
  }

  /**
   * Make every path starting with {@code prefix} answer only after {@code millis} — a parent that is
   * up, correct and slow, which is the arm that says whether ingest waits for it. Same discipline as
   * {@link #refuse}.
   */
  public static void answerSlowly(String prefix, long millis) {
    write(DELAYS, prefix + " " + millis + "\n");
  }

  /** Clear every armed fault. Idempotent, and safe to call when nothing was armed. */
  public static void answerNormally() {
    try {
      Files.deleteIfExists(REFUSALS);
      Files.deleteIfExists(HANGUPS);
      Files.deleteIfExists(DELAYS);
    } catch (IOException e) {
      throw new UncheckedIOException("could not clear the armed faults", e);
    }
  }

  private static boolean isArmed(Path file, String path) {
    for (String prefix : armedLines(file)) {
      if (path.startsWith(prefix.strip())) {
        return true;
      }
    }
    return false;
  }

  private static long armedDelay(String path) {
    for (String line : armedLines(DELAYS)) {
      String[] fields = line.strip().split(" ");
      if (fields.length == 2 && path.startsWith(fields[0])) {
        return Long.parseLong(fields[1]);
      }
    }
    return 0;
  }

  private static List<String> armedLines(Path file) {
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    String armed;
    try {
      armed = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    return List.of(armed.split("\n")).stream().filter(line -> !line.isBlank()).toList();
  }

  // --- what a story class calls -----------------------------------------------------------------

  /** Register the outgoing tap once per JVM. Called from every story class's {@code @BeforeAll}. */
  public static void install() {
    synchronized (LOCK) {
      if (registered) {
        return;
      }
      harvested = 0;
      NetworkCapture.source(SOURCE_ID, StoryParent::edges);
      registered = true;
    }
  }

  /**
   * Every export the parent has recorded at exactly {@code path} so far, oldest first — the
   * snapshot a story takes on entry so {@link #awaitForward} can wait for <b>one more</b> rather
   * than for an absolute count. That is what keeps the stories order-independent: none depends on
   * another having run, or not having run, first.
   *
   * <p>{@code POST} only, and that is not a detail. A counter that included every method would count
   * a probe or a stray {@code GET} as an export, and the whole point of a forward count is to say
   * how many copies left this process.
   */
  public static List<Forward> forwards(String path) {
    return recorded().stream()
        .filter(forward -> "POST".equals(forward.method()) && path.equals(bare(forward.target())))
        .toList();
  }

  /** How many times the parent has been posted {@code path} so far. */
  public static long forwardCount(String path) {
    return forwards(path).size();
  }

  /**
   * Wait until the parent has been posted {@code path} once more than {@code alreadySeen}, and
   * answer with that forward.
   *
   * <p>The forward is fire-and-forget on another thread, so the ingest response is back before the
   * upstream request is — polling is the shape of the contract, not flake tolerance. It is also what
   * puts the forward in the story's <b>diagram</b>: the framework drains this recording at story
   * end, and a request still in flight then would be attributed to a later story or to no story at
   * all. <b>Every story that exports must call this before it returns.</b>
   */
  public static Forward awaitForward(String path, long alreadySeen) {
    long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (true) {
      List<Forward> forwarded = forwards(path);
      if (forwarded.size() > alreadySeen) {
        return forwarded.get((int) alreadySeen);
      }
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("the parent collector was never posted " + path);
      }
      sleep(50);
    }
  }

  // --- the labels an assertion has to spell ------------------------------------------------------

  /** The label an answered export renders as, scrubbed exactly as the drain will scrub it. */
  public static String posted(String path, String status) {
    return Labels.scrub("POST " + path + " -> " + status);
  }

  /** {@code POST <path> -> <status>}. */
  public static String posted(String path, int status) {
    return posted(path, String.valueOf(status));
  }

  /** {@code POST <path> -> 200} — a copy the parent took. */
  public static String posted(String path) {
    return posted(path, 200);
  }

  /** {@code POST <path> -> no answer} — a copy the parent accepted and never answered. */
  public static String unanswered(String path) {
    return posted(path, NO_ANSWER);
  }

  // --- the source -------------------------------------------------------------------------------

  private static List<NetworkEdge> edges() {
    synchronized (LOCK) {
      harvest();
      return List.copyOf(EDGES);
    }
  }

  private static void harvest() {
    List<String> lines = recordedLines();
    if (harvested > lines.size()) {
      harvested = 0;
      lines = recordedLines();
    }
    for (String line : lines.subList(harvested, lines.size())) {
      edge(line).ifPresent(EDGES::add);
    }
    harvested = lines.size();
  }

  /**
   * One recorded line as an edge. {@code from} is decided structurally: a {@code POST} under {@code
   * /v1/} is an OTLP export and the only OTLP client in the launched process is the tee — see the
   * class javadoc.
   */
  private static Optional<NetworkEdge> edge(String line) {
    Forward forward = parse(line);
    if (forward == null) {
      return Optional.empty();
    }
    String from =
        "POST".equals(forward.method()) && forward.target().startsWith("/v1/")
            ? StoryTarget.SERVICE
            : UNEXPECTED_CLIENT;
    String status = forward.unanswered() ? NO_ANSWER : String.valueOf(forward.status());
    return Optional.of(
        NetworkEdge.http(
            from,
            SERVICE_NAME,
            Labels.scrub(forward.method() + " " + forward.target() + " -> " + status)));
  }

  private static List<Forward> recorded() {
    List<Forward> forwards = new ArrayList<>();
    for (String line : recordedLines()) {
      Forward forward = parse(line);
      if (forward != null) {
        forwards.add(forward);
      }
    }
    return forwards;
  }

  private static Forward parse(String line) {
    String[] fields = line.split("\t");
    if (fields.length != 5 || !fields[1].startsWith("/")) {
      return null;
    }
    return new Forward(
        fields[0],
        fields[1],
        Integer.parseInt(fields[2]),
        ABSENT.equals(fields[3]) ? null : fields[3],
        ABSENT.equals(fields[4]) ? null : fields[4]);
  }

  private static String bare(String target) {
    int query = target.indexOf('?');
    return query < 0 ? target : target.substring(0, query);
  }

  /**
   * The recording's complete lines. A missing file is an empty recording rather than a failure, and
   * an <b>unterminated tail is dropped</b>: the server appends while this reads, and half a line
   * would shape half an edge. The next harvest sees it whole.
   */
  private static List<String> recordedLines() {
    if (!Files.isRegularFile(ACCESS_LOG)) {
      return List.of();
    }
    String text;
    try {
      text = Files.readString(ACCESS_LOG, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      return List.of();
    }
    int lastComplete = text.lastIndexOf('\n');
    if (lastComplete < 0) {
      return List.of();
    }
    return List.of(text.substring(0, lastComplete).split("\n"));
  }

  private static synchronized void record(
      String method, String target, int status, String type, String encoding) {
    try {
      Files.createDirectories(ROOT);
      Files.writeString(
          ACCESS_LOG,
          String.join(
                  "\t",
                  method,
                  target,
                  String.valueOf(status),
                  type == null ? ABSENT : type,
                  encoding == null ? ABSENT : encoding)
              + "\n",
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException ignored) {
      // A recording that cannot be written costs the diagram an arrow; it must not cost the
      // launched process its answer, which is the thing under test.
    }
  }

  private static void write(Path file, String content) {
    try {
      Files.createDirectories(ROOT);
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + file, e);
    }
  }

  private static void wipe() {
    try {
      Files.deleteIfExists(ACCESS_LOG);
      Files.deleteIfExists(REFUSALS);
      Files.deleteIfExists(HANGUPS);
      Files.deleteIfExists(DELAYS);
    } catch (IOException e) {
      throw new UncheckedIOException("could not clear " + ROOT, e);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted waiting on the parent collector", e);
    }
  }
}
