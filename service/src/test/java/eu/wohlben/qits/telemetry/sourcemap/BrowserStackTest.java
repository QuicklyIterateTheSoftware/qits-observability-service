package eu.wohlben.qits.telemetry.sourcemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class BrowserStackTest {

  @Test
  void v8FramesAreRead() {
    List<BrowserStack.Line> lines =
        BrowserStack.parse(
            "TypeError: Cannot read properties of undefined (reading 'x')\n"
                + "    at Xe.save (https://qits.example/chunk--PJIe9Ye.js:1:2345)\n"
                + "    at https://qits.example/main-VSPTXLVR.js:75:10\n"
                + "    at async Object.load (https://qits.example/chunk-A_b.js?v=1:3:4)\n"
                + "    at new Foo (https://qits.example/x/y/chunk-B.js#frag:5:6)\n"
                + "    at Array.forEach (<anonymous>)\n"
                + "    at chrome-extension://abc/content.js:1:1");
    assertFalse(lines.get(0).isFrame());
    assertEquals(new BrowserStack.Line(lines.get(1).text(), "chunk--PJIe9Ye.js", 1, 2345, "Xe.save"), lines.get(1));
    assertEquals("main-VSPTXLVR.js", lines.get(2).file());
    assertEquals("", lines.get(2).function());
    assertEquals(75, lines.get(2).line());
    assertEquals("chunk-A_b.js", lines.get(3).file());
    assertEquals("Object.load", lines.get(3).function());
    assertEquals("chunk-B.js", lines.get(4).file());
    assertEquals("Foo", lines.get(4).function());
    assertFalse(lines.get(5).isFrame());
    assertFalse(lines.get(6).isFrame(), "only http(s) frames");
  }

  @Test
  void geckoFramesAreRead() {
    List<BrowserStack.Line> lines =
        BrowserStack.parse(
            "save@https://qits.example/chunk-X.js:1:20\n@https://qits.example/main-Y.js:2:3\n");
    assertEquals("chunk-X.js", lines.get(0).file());
    assertEquals("save", lines.get(0).function());
    assertEquals(20, lines.get(0).column());
    assertEquals("main-Y.js", lines.get(1).file());
    assertEquals("", lines.get(1).function());
    assertFalse(lines.get(2).isFrame(), "the trailing empty line");
  }

  @Test
  void onlyABundleBasenameIsEverAFile() {
    assertNull(BrowserStack.basename("https://evil.example/../../etc/passwd"));
    assertNull(BrowserStack.basename("https://h/page.html"));
    assertNull(BrowserStack.basename("https://h/a%2Fb.js"));
    assertEquals("chunk-1.js", BrowserStack.basename("https://h/a/b/chunk-1.js?x#y"));
    assertFalse(BrowserStack.line("    at f (https://h/a b.js:1:1)").isFrame());
    assertEquals(List.of(), BrowserStack.parse(null));
  }
}
