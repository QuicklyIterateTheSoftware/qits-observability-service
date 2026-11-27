package eu.wohlben.qits.telemetry.sourcemap;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Builds small source maps for the tests: a VLQ encoder and the JSON around it. */
final class TestMaps {

  private static final String BASE64 =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

  private TestMaps() {}

  /** One segment, absolute values: generated column, source, line, column, name (-1 for none). */
  record Seg(int column, int source, int line, int originalColumn, int name) {}

  static String vlq(int value) {
    int v = value < 0 ? ((-value) << 1) | 1 : value << 1;
    StringBuilder out = new StringBuilder();
    do {
      int digit = v & 31;
      v >>>= 5;
      if (v > 0) {
        digit |= 32;
      }
      out.append(BASE64.charAt(digit));
    } while (v > 0);
    return out.toString();
  }

  /** The mappings string for generated lines of absolute segments. */
  static String mappings(List<List<Seg>> lines) {
    StringBuilder out = new StringBuilder();
    int source = 0;
    int line = 0;
    int column = 0;
    int name = 0;
    for (int l = 0; l < lines.size(); l++) {
      if (l > 0) {
        out.append(';');
      }
      int generated = 0;
      List<Seg> segs = lines.get(l);
      for (int i = 0; i < segs.size(); i++) {
        Seg s = segs.get(i);
        if (i > 0) {
          out.append(',');
        }
        out.append(vlq(s.column() - generated));
        generated = s.column();
        out.append(vlq(s.source() - source)).append(vlq(s.line() - line));
        out.append(vlq(s.originalColumn() - column));
        source = s.source();
        line = s.line();
        column = s.originalColumn();
        if (s.name() >= 0) {
          out.append(vlq(s.name() - name));
          name = s.name();
        }
      }
    }
    return out.toString();
  }

  static String json(List<String> sources, List<String> names, String mappings) {
    return "{\"version\":3,\"file\":\"main-ABC.js\",\"sources\":["
        + quoted(sources)
        + "],\"sourcesContent\":[\"a huge source the reader must skip\"],\"names\":["
        + quoted(names)
        + "],\"mappings\":\""
        + mappings
        + "\"}";
  }

  /**
   * The fixture: main-ABC.js line 1 has `save` at column 1 (src/app/x.page.ts 11:3), the call to
   * `save` at column 21 (src/app/x.page.ts 21:5, name "save"), and angular code at column 41.
   */
  static String fixture() {
    return json(
        List.of("webpack:///./src/app/x.page.ts", "node_modules/@angular/core/fesm2022/core.mjs"),
        List.of("save", "handle"),
        mappings(
            List.of(
                List.of(
                    new Seg(0, 0, 10, 2, -1),
                    new Seg(20, 0, 20, 4, 0),
                    new Seg(40, 1, 5, 0, 1)),
                List.of(new Seg(3, 0, 30, 0, -1)))));
  }

  static SourceMap parse(String json) throws SourceMap.Unreadable {
    return SourceMap.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
  }

  private static String quoted(List<String> values) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append('"').append(values.get(i)).append('"');
    }
    return out.toString();
  }
}
