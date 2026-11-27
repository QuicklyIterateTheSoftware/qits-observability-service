package eu.wohlben.qits.telemetry.sourcemap;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One decoded source map (revision 3), held as compactly as a lookup allows (epic qits-2689,
 * qits-2766).
 *
 * <p><b>What is kept.</b> {@code sources} (resolved against {@code sourceRoot}), {@code names}, and
 * the {@code mappings} decoded into one {@code int[]} per generated line, five ints per segment:
 * generated column, source index, original line, original column, name index ({@code -1} when the
 * segment names none, and the source fields {@code -1} on a one-field segment). {@code
 * sourcesContent} — most of a map's bytes — is skipped while streaming and never held: the ingest
 * reads positions, not sources.
 *
 * <p><b>What is refused</b>, as {@link Unreadable}: anything that is not a JSON object with {@code
 * version: 3} and a {@code mappings} string, an index map ({@code sections}), and a {@code
 * mappings} value whose VLQ does not decode. The caller falls back to today's fingerprint.
 *
 * <p>Plain Java, no static state: native-image safe, and {@code SourceMapTest} news it up.
 */
public final class SourceMap {

  private static final int FIELDS = 5;

  private final List<String> sources;
  private final List<String> names;
  private final int[][] lines;
  private final long estimatedBytes;

  private SourceMap(List<String> sources, List<String> names, int[][] lines) {
    this.sources = List.copyOf(sources);
    this.names = List.copyOf(names);
    this.lines = lines;
    long bytes = 64L + 16L * lines.length;
    for (int[] line : lines) {
      bytes += 16L + 4L * line.length;
    }
    for (String s : this.sources) {
      bytes += 40L + 2L * s.length();
    }
    for (String s : this.names) {
      bytes += 40L + 2L * s.length();
    }
    this.estimatedBytes = bytes;
  }

  /** A map that could not be read; the message says why. */
  public static final class Unreadable extends Exception {
    public Unreadable(String message) {
      super(message, null, false, false);
    }
  }

  /** One original position: the source, 0-based line and column, and the name there or null. */
  public record Position(String source, int line, int column, String name) {}

  /** Parses a map from a stream the caller has already bounded in size. */
  public static SourceMap parse(InputStream in) throws Unreadable {
    String mappings = null;
    String sourceRoot = "";
    List<String> sources = new ArrayList<>();
    List<String> names = new ArrayList<>();
    int version = -1;
    // A factory per parse, never a static one: a map is parsed once per (site, version, file), and a
    // static factory would carry Jackson's buffer-recycler state into the native image heap.
    try (JsonParser p = new JsonFactory().createParser(in)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        throw new Unreadable("not a JSON object");
      }
      while (p.nextToken() == JsonToken.FIELD_NAME) {
        String field = p.currentName();
        JsonToken value = p.nextToken();
        switch (field) {
          case "version" -> version = value == JsonToken.VALUE_NUMBER_INT ? p.getIntValue() : -1;
          case "mappings" -> mappings = value == JsonToken.VALUE_STRING ? p.getText() : null;
          case "sourceRoot" -> sourceRoot = value == JsonToken.VALUE_STRING ? p.getText() : "";
          case "sources" -> readStrings(p, value, sources);
          case "names" -> readStrings(p, value, names);
          case "sections" -> throw new Unreadable("an index map (sections) is not supported");
          default -> p.skipChildren();
        }
      }
    } catch (IOException | RuntimeException e) {
      throw new Unreadable("not a readable source map: " + e.getMessage());
    }
    if (version != 3) {
      throw new Unreadable("not a version 3 source map");
    }
    if (mappings == null) {
      throw new Unreadable("no mappings");
    }
    if (!sourceRoot.isEmpty()) {
      String root = sourceRoot.endsWith("/") ? sourceRoot : sourceRoot + "/";
      sources.replaceAll(s -> s == null ? null : root + s);
    }
    return new SourceMap(sources, names, decode(mappings, sources.size(), names.size()));
  }

  private static void readStrings(JsonParser p, JsonToken start, List<String> into)
      throws IOException, Unreadable {
    if (start != JsonToken.START_ARRAY) {
      throw new Unreadable("expected an array");
    }
    JsonToken t;
    while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
      if (t == JsonToken.VALUE_STRING) {
        into.add(p.getText());
      } else if (t == JsonToken.VALUE_NULL) {
        into.add(null);
      } else {
        throw new Unreadable("expected strings");
      }
    }
  }

  /** The VLQ {@code mappings} grammar: {@code ;} ends a line, {@code ,} a segment. */
  static int[][] decode(String mappings, int sourceCount, int nameCount) throws Unreadable {
    List<int[]> lines = new ArrayList<>();
    int[] line = new int[FIELDS * 8];
    int used = 0;
    int source = 0;
    int originalLine = 0;
    int originalColumn = 0;
    int name = 0;
    int[] segment = new int[FIELDS];
    int i = 0;
    int n = mappings.length();
    while (i <= n) {
      if (i == n || mappings.charAt(i) == ';') {
        lines.add(Arrays.copyOf(line, used));
        used = 0;
        i++;
        if (i > n) {
          break;
        }
        continue;
      }
      if (mappings.charAt(i) == ',') {
        i++;
        continue;
      }
      // One segment: 1, 4 or 5 VLQ fields.
      int fields = 0;
      int generatedColumn = used == 0 ? 0 : line[used - FIELDS];
      while (i < n && mappings.charAt(i) != ',' && mappings.charAt(i) != ';') {
        if (fields == FIELDS) {
          throw new Unreadable("a segment with more than five fields");
        }
        int value = 0;
        int shift = 0;
        boolean more;
        do {
          if (i >= n) {
            throw new Unreadable("a truncated VLQ");
          }
          int digit = base64(mappings.charAt(i++));
          if (digit < 0 || shift > 30) {
            throw new Unreadable("an invalid VLQ");
          }
          more = (digit & 32) != 0;
          value |= (digit & 31) << shift;
          shift += 5;
        } while (more);
        segment[fields++] = (value & 1) == 1 ? -(value >>> 1) : value >>> 1;
      }
      if (fields != 1 && fields != 4 && fields != 5) {
        throw new Unreadable("a segment with " + fields + " fields");
      }
      generatedColumn += segment[0];
      int s = -1;
      int ol = -1;
      int oc = -1;
      int nm = -1;
      if (fields >= 4) {
        source += segment[1];
        originalLine += segment[2];
        originalColumn += segment[3];
        if (source < 0 || source >= sourceCount || originalLine < 0 || originalColumn < 0) {
          throw new Unreadable("a segment outside the map's sources");
        }
        s = source;
        ol = originalLine;
        oc = originalColumn;
      }
      if (fields == 5) {
        name += segment[4];
        if (name < 0 || name >= nameCount) {
          throw new Unreadable("a segment outside the map's names");
        }
        nm = name;
      }
      if (used + FIELDS > line.length) {
        line = Arrays.copyOf(line, line.length * 2);
      }
      line[used++] = generatedColumn;
      line[used++] = s;
      line[used++] = ol;
      line[used++] = oc;
      line[used++] = nm;
    }
    return lines.toArray(new int[0][]);
  }

  private static int base64(char c) {
    if (c >= 'A' && c <= 'Z') {
      return c - 'A';
    }
    if (c >= 'a' && c <= 'z') {
      return c - 'a' + 26;
    }
    if (c >= '0' && c <= '9') {
      return c - '0' + 52;
    }
    if (c == '+') {
      return 62;
    }
    if (c == '/') {
      return 63;
    }
    return -1;
  }

  /**
   * The original position of a generated one (both 0-based): the last segment of that line at or
   * before the column, or null when the line has none or that segment maps to no source.
   */
  public Position lookup(int line, int column) {
    if (line < 0 || line >= lines.length || column < 0) {
      return null;
    }
    int[] segments = lines[line];
    int lo = 0;
    int hi = segments.length / FIELDS - 1;
    int found = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (segments[mid * FIELDS] <= column) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (found < 0) {
      return null;
    }
    int at = found * FIELDS;
    int source = segments[at + 1];
    if (source < 0 || sources.get(source) == null) {
      return null;
    }
    int name = segments[at + 4];
    return new Position(
        sources.get(source), segments[at + 2], segments[at + 3], name < 0 ? null : names.get(name));
  }

  /** What this map costs the cache, roughly: the arrays and strings it holds. */
  public long estimatedBytes() {
    return estimatedBytes;
  }
}
