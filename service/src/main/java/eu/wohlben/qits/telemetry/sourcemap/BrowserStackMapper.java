package eu.wohlben.qits.telemetry.sourcemap;

import eu.wohlben.qits.telemetry.control.TelemetryStore;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * Maps a browser ERROR log's minified stack through the release's published source maps, at ingest
 * and before the fingerprint stamp (epic qits-2689, qits-2766).
 *
 * <p><b>Which logs.</b> ERROR or worse, from a service named {@code <application>-browser} (what
 * {@code @qits/angular} calls its browser half), outside a workspace, with an {@code
 * exception.stacktrace} and a {@code service.version}. Its site is {@code
 * @sourcemaps/<application>}. Every other log passes through untouched, as the same instance.
 *
 * <p><b>What it does.</b> The top {@code frames} (10) frames are mapped, reading at most {@code
 * files} (3) distinct map files. A file is looked up at the log's {@code service.version} first;
 * when that version has no such file — a service-worker-cached bundle reporting the server's newer
 * version — the up to {@code olderVersions} (3) next older versions of the site are asked, which is
 * unambiguous because chunk names are content-hashed. A frame's function is the original name at
 * its <b>caller's</b> call site (the frame below it), which is what the minifier renamed; when the
 * caller is not mapped it is left out.
 *
 * <p><b>What it writes.</b> {@value #MAPPED_STACKTRACE}: the stack with every mapped frame replaced
 * by {@code    at <name> (<source>:<line>:<column>)} (1-based) and every other line as it came,
 * cut at {@value #MAX_MAPPED_STACKTRACE} characters. Nothing else changes in this task — the
 * fingerprint still reads the minified stack, so no issue moves.
 *
 * <p><b>What it never does</b>: wait longer than {@code budgetNanos} (5 s) for one batch, throw, or
 * log at ERROR — this service's own ERROR logs feed its own issues. Any failure leaves the log
 * exactly as it came; a surprise is a WARN at most once a minute.
 */
public final class BrowserStackMapper {

  /** The attribute holding the mapped stack trace. */
  public static final String MAPPED_STACKTRACE = "qits.error.mapped-stacktrace";

  /** The service-name suffix of a browser bundle's telemetry. */
  public static final String BROWSER_SUFFIX = "-browser";

  static final int MAX_MAPPED_STACKTRACE = 16384;

  private static final Pattern APPLICATION = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

  private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*://[^/]*/");

  private static final long WARN_EVERY_MILLIS = 60_000L;

  private static final Logger LOG = Logger.getLogger(BrowserStackMapper.class);

  private final SourceMapCache cache;
  private final int frames;
  private final int files;
  private final int olderVersions;
  private final long budgetNanos;
  private final LongSupplier nanos;
  private final LongSupplier millis;
  private final AtomicLong lastWarnMillis = new AtomicLong(Long.MIN_VALUE);

  public BrowserStackMapper(
      SourceMapCache cache,
      int frames,
      int files,
      int olderVersions,
      long budgetNanos,
      LongSupplier nanos,
      LongSupplier millis) {
    this.cache = cache;
    this.frames = frames;
    this.files = files;
    this.olderVersions = olderVersions;
    this.budgetNanos = budgetNanos;
    this.nanos = nanos;
    this.millis = millis;
  }

  /** The batch with every eligible log carrying {@value #MAPPED_STACKTRACE}; never throws. */
  public List<StoredLog> map(List<StoredLog> logs) {
    long deadline = nanos.getAsLong() + budgetNanos;
    List<StoredLog> out = null;
    for (int i = 0; i < logs.size(); i++) {
      StoredLog log = logs.get(i);
      StoredLog mapped = log;
      try {
        mapped = mapOne(log, deadline);
      } catch (RuntimeException e) {
        warn("mapping a browser stack failed, the log is kept as it came: " + e);
      }
      if (mapped != log) {
        if (out == null) {
          out = new ArrayList<>(logs);
        }
        out.set(i, mapped);
      }
    }
    return out == null ? logs : out;
  }

  /** The site a log's maps are published under, or empty when the log is not a browser error. */
  static Optional<String> site(StoredLog log) {
    if (!log.isError() || log.serviceName() == null) {
      return Optional.empty();
    }
    if (log.resourceAttributes() != null
        && log.resourceAttributes().containsKey(TelemetryStore.WORKSPACE_ATTRIBUTE)) {
      return Optional.empty();
    }
    String service = log.serviceName();
    if (!service.endsWith(BROWSER_SUFFIX)) {
      return Optional.empty();
    }
    String application = service.substring(0, service.length() - BROWSER_SUFFIX.length());
    return APPLICATION.matcher(application).matches()
        ? Optional.of("@sourcemaps/" + application)
        : Optional.empty();
  }

  private StoredLog mapOne(StoredLog log, long deadline) {
    Optional<String> site = site(log);
    if (site.isEmpty() || log.attributes() == null || log.resourceAttributes() == null) {
      return log;
    }
    String stack = log.attributes().get("exception.stacktrace");
    String version = log.resourceAttributes().get("service.version");
    if (stack == null
        || stack.isBlank()
        || version == null
        || !DocsSourceMapSource.VERSION.matcher(version).matches()) {
      return log;
    }
    List<BrowserStack.Line> lines = BrowserStack.parse(stack);
    // The top frames, and the maps they need: at most `files` distinct ones, in stack order.
    List<Integer> top = new ArrayList<>();
    Map<String, Optional<SourceMap>> maps = new LinkedHashMap<>();
    for (int i = 0; i < lines.size() && top.size() < frames; i++) {
      BrowserStack.Line line = lines.get(i);
      if (!line.isFrame()) {
        continue;
      }
      top.add(i);
      if (!maps.containsKey(line.file()) && maps.size() < files) {
        maps.put(line.file(), Optional.empty());
      }
    }
    if (top.isEmpty()) {
      return log;
    }
    for (String file : new ArrayList<>(maps.keySet())) {
      maps.put(file, resolve(site.get(), version, file, deadline));
    }
    // Each top frame's original position, where its file's map is held.
    Map<Integer, SourceMap.Position> positions = new HashMap<>();
    for (int i : top) {
      BrowserStack.Line line = lines.get(i);
      Optional<SourceMap> map = maps.getOrDefault(line.file(), Optional.empty());
      if (map.isPresent()) {
        SourceMap.Position at = map.get().lookup(line.line() - 1, line.column() - 1);
        if (at != null) {
          positions.put(i, at);
        }
      }
    }
    if (positions.isEmpty()) {
      return log;
    }
    StringBuilder mapped = new StringBuilder(stack.length());
    for (int k = 0; k < lines.size(); k++) {
      if (k > 0) {
        mapped.append('\n');
      }
      SourceMap.Position at = positions.get(k);
      if (at == null) {
        mapped.append(lines.get(k).text());
        continue;
      }
      String name = callerName(top, positions, k);
      mapped.append("    at ");
      if (name != null) {
        mapped.append(name).append(" (");
      }
      mapped
          .append(normaliseSource(at.source()))
          .append(':')
          .append(at.line() + 1)
          .append(':')
          .append(at.column() + 1);
      if (name != null) {
        mapped.append(')');
      }
    }
    String text =
        mapped.length() > MAX_MAPPED_STACKTRACE
            ? mapped.substring(0, MAX_MAPPED_STACKTRACE)
            : mapped.toString();
    Map<String, String> attributes = new LinkedHashMap<>(log.attributes());
    attributes.put(MAPPED_STACKTRACE, text);
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

  /** The name at the next top frame's call site: what the minifier renamed this frame's function. */
  private static String callerName(
      List<Integer> top, Map<Integer, SourceMap.Position> positions, int index) {
    int at = top.indexOf(index);
    if (at < 0 || at + 1 >= top.size()) {
      return null;
    }
    SourceMap.Position caller = positions.get(top.get(at + 1));
    return caller == null || caller.name() == null || caller.name().isBlank()
        ? null
        : caller.name();
  }

  /** The file's map at the version, else at one of the next older versions of the site. */
  private Optional<SourceMap> resolve(String site, String version, String file, long deadline) {
    Optional<SourceMap> map = cache.map(new SourceMapCache.Key(site, version, file), deadline);
    if (map.isPresent() || olderVersions <= 0 || deadline - nanos.getAsLong() <= 0) {
      return map;
    }
    Optional<List<String>> published = cache.versions(site, deadline);
    if (published.isEmpty()) {
      return Optional.empty();
    }
    List<String> list = published.get();
    int at = list.indexOf(version);
    if (at < 0) {
      return Optional.empty();
    }
    for (int i = at + 1; i < list.size() && i <= at + olderVersions; i++) {
      if (deadline - nanos.getAsLong() <= 0) {
        return Optional.empty();
      }
      map = cache.map(new SourceMapCache.Key(site, list.get(i), file), deadline);
      if (map.isPresent()) {
        return map;
      }
    }
    return Optional.empty();
  }

  /**
   * A map's source as a repository-relative path: a {@code webpack://…/} or other scheme prefix
   * dropped, then every leading {@code ./}, {@code ../} and {@code /}.
   */
  static String normaliseSource(String source) {
    String s = SCHEME.matcher(source).replaceFirst("");
    while (true) {
      if (s.startsWith("./")) {
        s = s.substring(2);
      } else if (s.startsWith("../")) {
        s = s.substring(3);
      } else if (s.startsWith("/")) {
        s = s.substring(1);
      } else {
        return s;
      }
    }
  }

  private void warn(String message) {
    long now = millis.getAsLong();
    long last = lastWarnMillis.get();
    if (last != Long.MIN_VALUE && now - last < WARN_EVERY_MILLIS) {
      return;
    }
    if (lastWarnMillis.compareAndSet(last, now)) {
      LOG.warn(message);
    }
  }
}
