package eu.wohlben.qits.telemetry.sourcemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourceMapTest {

  @Test
  void aPositionMapsToTheLastSegmentAtOrBeforeIt() throws Exception {
    SourceMap map = TestMaps.parse(TestMaps.fixture());
    assertEquals(
        new SourceMap.Position("webpack:///./src/app/x.page.ts", 10, 2, null), map.lookup(0, 0));
    assertEquals(
        new SourceMap.Position("webpack:///./src/app/x.page.ts", 10, 2, null), map.lookup(0, 19));
    assertEquals(
        new SourceMap.Position("webpack:///./src/app/x.page.ts", 20, 4, "save"), map.lookup(0, 25));
    assertEquals(
        new SourceMap.Position("node_modules/@angular/core/fesm2022/core.mjs", 5, 0, "handle"),
        map.lookup(0, 400));
    assertEquals(30, map.lookup(1, 3).line(), "the second line's state carries over");
    assertNull(map.lookup(1, 2), "before the line's first segment");
    assertNull(map.lookup(2, 0), "no such line");
    assertNull(map.lookup(-1, 0));
  }

  @Test
  void sourceRootIsJoinedAndSourcesContentIsNotHeld() throws Exception {
    String json = TestMaps.fixture().replace("{\"version\":3,", "{\"version\":3,\"sourceRoot\":\"root\",");
    SourceMap map = TestMaps.parse(json);
    assertEquals("root/webpack:///./src/app/x.page.ts", map.lookup(0, 0).source());
    assertTrue(map.estimatedBytes() < 2_000, "small: the skipped sourcesContent is not counted");
  }

  @Test
  void whatIsNotAVersionThreeFlatMapIsUnreadable() {
    assertThrows(SourceMap.Unreadable.class, () -> TestMaps.parse("[]"));
    assertThrows(SourceMap.Unreadable.class, () -> TestMaps.parse("{\"version\":2,\"mappings\":\"\"}"));
    assertThrows(SourceMap.Unreadable.class, () -> TestMaps.parse("{\"version\":3,\"sources\":[]}"));
    assertThrows(
        SourceMap.Unreadable.class,
        () -> TestMaps.parse("{\"version\":3,\"sections\":[],\"mappings\":\"\"}"));
    assertThrows(
        SourceMap.Unreadable.class,
        () -> TestMaps.parse("{\"version\":3,\"sources\":[\"a\"],\"names\":[],\"mappings\":\"A!\"}"));
    assertThrows(
        SourceMap.Unreadable.class,
        () -> TestMaps.parse("{\"version\":3,\"sources\":[\"a\"],\"names\":[],\"mappings\":\"AC\"}"),
        "two fields");
    assertThrows(
        SourceMap.Unreadable.class,
        () -> TestMaps.parse("{\"version\":3,\"sources\":[\"a\"],\"names\":[],\"mappings\":\"ACAA\"}"),
        "a source index outside sources");
    assertThrows(SourceMap.Unreadable.class, () -> TestMaps.parse("{\"version\":3,"));
  }

  @Test
  void vlqRoundTripsThroughTheDecoder() throws Exception {
    String mappings =
        TestMaps.mappings(
            List.of(List.of(new TestMaps.Seg(1000, 0, 123456, 77, -1)), List.of(), List.of(new TestMaps.Seg(5, 0, 3, 1, -1))));
    SourceMap map =
        TestMaps.parse(TestMaps.json(List.of("a.ts"), List.of(), mappings));
    assertEquals(123456, map.lookup(0, 1000).line());
    assertNull(map.lookup(1, 10), "an empty line");
    assertEquals(3, map.lookup(2, 5).line(), "deltas go back down across lines");
  }
}
