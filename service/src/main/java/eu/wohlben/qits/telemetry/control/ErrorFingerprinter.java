package eu.wohlben.qits.telemetry.control;

import eu.wohlben.qits.telemetry.dto.StoredLog;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Decides which ERROR logs feed an error issue and gives each one its fingerprint (epic qits-2538,
 * dossier page "Fingerprint and ingest").
 *
 * <p>A log feeds an issue when it is an ERROR or worse ({@link StoredLog#isError()}), names a
 * {@code serviceName}, and carries no {@code qits.workspace.id} resource attribute — workspace
 * telemetry is out of scope. Only logs: error spans do not feed issues.
 *
 * <p>The fingerprint is the lower-case hex of the first 16 bytes of {@code SHA-256(serviceName +
 * "\n" + type + "\n" + location + "\n" + message)}, where {@code type} is {@code exception.type},
 * {@code location} is the class and method of the first in-app stack frame (or the best fallback a
 * producer sent), and {@code message} is the normalised body, used only when type or location is
 * empty. The line number and the service version are deliberately not inputs: both move between
 * releases, and a regression in a later version belongs to the same issue.
 *
 * <p>Plain Java: the logic needs no CDI, so {@code ErrorFingerprinterTest} news it up. The bean
 * reads {@value #IN_APP_PREFIXES_KEY} (comma-separated, default {@value #DEFAULT_IN_APP_PREFIXES}).
 */
@Singleton
public class ErrorFingerprinter {

  /** The attribute the receiver stamps on every log that feeds an issue. */
  public static final String FINGERPRINT_ATTRIBUTE = "qits.error.fingerprint";

  /** The config key naming the class prefixes whose frames count as in-app. */
  public static final String IN_APP_PREFIXES_KEY =
      "qits.observability.error-issues.in-app-prefixes";

  public static final String DEFAULT_IN_APP_PREFIXES = "eu.wohlben.qits.";

  /** What a fingerprint looks like on the wire: 32 lower-case hex characters. */
  public static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{32}$");

  static final int MAX_MESSAGE_LENGTH = 200;

  private static final String WORKSPACE_ATTRIBUTE = TelemetryStore.WORKSPACE_ATTRIBUTE;

  private static final Pattern FRAME =
      Pattern.compile("^\\s*at ([\\w$.]+)\\.([\\w$<>]+)\\(", Pattern.MULTILINE);

  private static final Pattern LAMBDA = Pattern.compile("^lambda\\$(.+)\\$\\d+$");
  private static final Pattern GENERATED_SUFFIX = Pattern.compile("\\$\\$.*$");
  private static final Pattern PROXY_SUFFIX = Pattern.compile("(_Subclass|_ClientProxy)+$");
  private static final Pattern HASH_SUFFIX = Pattern.compile("_[A-Za-z0-9_-]{20,}.*$");
  private static final Pattern ANONYMOUS_SUFFIX = Pattern.compile("(\\$\\d+)+$");

  private static final Pattern UUID =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  private static final Pattern HEX_RUN = Pattern.compile("[0-9a-fA-F]{8,}");
  private static final Pattern QUOTED = Pattern.compile("'[^']*'|\"[^\"]*\"");
  private static final Pattern DIGITS = Pattern.compile("\\d+");
  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private final List<String> inAppPrefixes;

  /** The bean: the prefixes come from {@value #IN_APP_PREFIXES_KEY}. */
  @Inject
  public ErrorFingerprinter(
      @ConfigProperty(name = IN_APP_PREFIXES_KEY, defaultValue = DEFAULT_IN_APP_PREFIXES)
          List<String> inAppPrefixes) {
    this.inAppPrefixes =
        inAppPrefixes.stream().map(String::trim).filter(p -> !p.isEmpty()).toList();
  }

  /** The default prefixes, for plain-Java callers. */
  public ErrorFingerprinter() {
    this(List.of(DEFAULT_IN_APP_PREFIXES));
  }

  /** The parts that went into a fingerprint, which the issue aggregator keeps beside it. */
  public record ErrorFingerprint(
      String fingerprint, String type, String location, String message) {}

  /** Whether this log feeds an error issue: ERROR or worse, a named service, no workspace. */
  public boolean feeds(StoredLog log) {
    return log.isError()
        && log.serviceName() != null
        && !log.serviceName().isBlank()
        && (log.resourceAttributes() == null
            || !log.resourceAttributes().containsKey(WORKSPACE_ATTRIBUTE));
  }

  /** The log's fingerprint and its parts. Computed for any log; {@link #feeds} decides use. */
  public ErrorFingerprint fingerprint(StoredLog log) {
    Map<String, String> attributes = log.attributes() == null ? Map.of() : log.attributes();
    String type = orEmpty(attributes.get("exception.type"));
    String location = location(attributes);
    String message = type.isEmpty() || location.isEmpty() ? normaliseMessage(log.body()) : "";
    String fingerprint =
        hash(orEmpty(log.serviceName()) + "\n" + type + "\n" + location + "\n" + message);
    return new ErrorFingerprint(fingerprint, type, location, message);
  }

  /**
   * The batch as the store should hold it: every log that {@link #feeds} an issue is replaced by a
   * copy carrying {@value #FINGERPRINT_ATTRIBUTE}; every other log is returned as it came.
   */
  public List<StoredLog> stamp(List<StoredLog> logs) {
    List<StoredLog> stamped = new ArrayList<>(logs.size());
    for (StoredLog log : logs) {
      stamped.add(feeds(log) ? stamped(log, fingerprint(log).fingerprint()) : log);
    }
    return stamped;
  }

  /** Whether a value is shaped like a fingerprint ({@code ^[0-9a-f]{32}$}). */
  public static boolean isFingerprint(String value) {
    return value != null && FINGERPRINT.matcher(value).matches();
  }

  private static StoredLog stamped(StoredLog log, String fingerprint) {
    Map<String, String> attributes = new LinkedHashMap<>();
    if (log.attributes() != null) {
      attributes.putAll(log.attributes());
    }
    attributes.put(FINGERPRINT_ATTRIBUTE, fingerprint);
    return new StoredLog(
        log.epochNanos(),
        log.severityNumber(),
        log.severityText(),
        log.body(),
        log.traceId(),
        log.spanId(),
        log.serviceName(),
        Collections.unmodifiableMap(attributes),
        log.resourceAttributes(),
        log.receivedAtMillis());
  }

  /** The first in-app frame, else the first frame, else what the logging bridge named. */
  String location(Map<String, String> attributes) {
    String stack = attributes.get("exception.stacktrace");
    if (stack != null && !stack.isEmpty()) {
      String firstFrame = null;
      Matcher frame = FRAME.matcher(stack);
      while (frame.find()) {
        String className = frame.group(1);
        String normalised = normaliseFrame(className, frame.group(2));
        if (isInApp(className)) {
          return normalised;
        }
        if (firstFrame == null) {
          firstFrame = normalised;
        }
      }
      if (firstFrame != null) {
        return firstFrame;
      }
    }
    String function = attributes.get("code.function.name");
    if (function != null && !function.isBlank() && !"null.null".equals(function)) {
      return function;
    }
    String namespace = attributes.get("code.namespace");
    if (namespace != null && !namespace.isBlank()) {
      return namespace;
    }
    return orEmpty(attributes.get("bridge.name"));
  }

  private boolean isInApp(String className) {
    for (String prefix : inAppPrefixes) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code <class>.<method>} with the generated parts dropped: a lambda names its enclosing method,
   * an anonymous class its outer class, and CDI subclasses, client proxies and hash-suffixed
   * synthetic classes the class they stand in for.
   */
  static String normaliseFrame(String className, String method) {
    Matcher lambda = LAMBDA.matcher(method);
    String m = lambda.matches() ? lambda.group(1) : method;
    int dot = className.lastIndexOf('.');
    String pkg = dot < 0 ? "" : className.substring(0, dot + 1);
    String simple = dot < 0 ? className : className.substring(dot + 1);
    simple = GENERATED_SUFFIX.matcher(simple).replaceFirst("");
    simple = PROXY_SUFFIX.matcher(simple).replaceFirst("");
    simple = HASH_SUFFIX.matcher(simple).replaceFirst("");
    simple = ANONYMOUS_SUFFIX.matcher(simple).replaceFirst("");
    return pkg + simple + "." + m;
  }

  /**
   * The body with its variable parts replaced, in this order: UUIDs, runs of eight or more hex
   * digits, quoted strings, remaining digit runs; then whitespace collapsed, trimmed and cut at
   * {@value #MAX_MESSAGE_LENGTH} characters.
   */
  static String normaliseMessage(String body) {
    if (body == null) {
      return "";
    }
    String text = UUID.matcher(body).replaceAll("<uuid>");
    text = HEX_RUN.matcher(text).replaceAll("<hex>");
    text = QUOTED.matcher(text).replaceAll("<s>");
    text = DIGITS.matcher(text).replaceAll("<n>");
    text = WHITESPACE.matcher(text).replaceAll(" ").trim();
    return text.length() > MAX_MESSAGE_LENGTH ? text.substring(0, MAX_MESSAGE_LENGTH) : text;
  }

  private static String hash(String input) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String orEmpty(String value) {
    return value == null ? "" : value;
  }
}
