package eu.wohlben.qits.telemetry.sourcemap;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A browser {@code Error.stack}, read line by line into frames (epic qits-2689, qits-2766).
 *
 * <p>Two grammars, the two the estate's browsers write:
 *
 * <ul>
 *   <li><b>V8</b> (Chrome, Edge): {@code    at fn (https://host/chunk-X.js:1:2345)}, {@code    at
 *       https://host/chunk-X.js:1:2345}, with {@code async} and {@code new} prefixes;
 *   <li><b>SpiderMonkey and JavaScriptCore</b> (Firefox, Safari): {@code fn@https://host/chunk-X.js:1:2345}
 *       and {@code @https://…}.
 * </ul>
 *
 * <p>Only an {@code http(s)} URL is a frame, and of it only the <b>basename</b> is ever used — it must
 * match {@link #FILE} — so a log can never steer which URL the map fetch dials: the site, version
 * and file are joined onto a fixed base. Every other line (the message line, {@code <anonymous>},
 * an extension's frame) is kept verbatim and mapped to nothing.
 *
 * <p>Lines and columns are 1-based on the wire in both grammars and are kept that way here; {@link
 * BrowserStackMapper} makes them 0-based for the lookup.
 */
public final class BrowserStack {

  /** What a frame's file may be: a bundle chunk's basename, content-hashed by the build. */
  public static final Pattern FILE = Pattern.compile("^[A-Za-z0-9._-]+\\.js$");

  private static final Pattern V8 =
      Pattern.compile(
          "^\\s*at (?:(?<fn>[^()]*?) \\()?(?<url>https?://[^\\s()]+?):(?<line>\\d+):(?<col>\\d+)\\)?\\s*$");

  private static final Pattern GECKO =
      Pattern.compile(
          "^\\s*(?<fn>[^@\\s]*)@(?<url>https?://[^\\s]+?):(?<line>\\d+):(?<col>\\d+)\\s*$");

  private static final Pattern PREFIX = Pattern.compile("^(?:async |new )+");

  private BrowserStack() {}

  /**
   * One line of the stack: a frame when {@code file} is non-null (its basename, 1-based line and
   * column, and the minified function name or {@code ""}), else a line kept as it came.
   */
  public record Line(String text, String file, int line, int column, String function) {
    public boolean isFrame() {
      return file != null;
    }
  }

  /** The stack, line by line; never null. */
  public static List<Line> parse(String stack) {
    List<Line> out = new ArrayList<>();
    if (stack == null || stack.isEmpty()) {
      return out;
    }
    for (String text : stack.split("\\r?\\n", -1)) {
      out.add(line(text));
    }
    return out;
  }

  static Line line(String text) {
    Matcher m = V8.matcher(text);
    if (!m.matches()) {
      m = GECKO.matcher(text);
      if (!m.matches()) {
        return new Line(text, null, 0, 0, "");
      }
    }
    String file = basename(m.group("url"));
    if (file == null) {
      return new Line(text, null, 0, 0, "");
    }
    int line;
    int column;
    try {
      line = Integer.parseInt(m.group("line"));
      column = Integer.parseInt(m.group("col"));
    } catch (NumberFormatException e) {
      return new Line(text, null, 0, 0, "");
    }
    String fn = m.group("fn") == null ? "" : PREFIX.matcher(m.group("fn").trim()).replaceFirst("");
    return new Line(text, file, line, column, fn);
  }

  /** The last path segment of an http(s) URL, query and fragment dropped, when it is a bundle file. */
  static String basename(String url) {
    String path = url;
    int cut = path.indexOf('?');
    if (cut >= 0) {
      path = path.substring(0, cut);
    }
    cut = path.indexOf('#');
    if (cut >= 0) {
      path = path.substring(0, cut);
    }
    int slash = path.lastIndexOf('/');
    String name = slash < 0 ? path : path.substring(slash + 1);
    return FILE.matcher(name).matches() ? name : null;
  }
}
