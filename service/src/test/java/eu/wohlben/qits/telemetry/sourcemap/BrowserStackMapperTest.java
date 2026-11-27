package eu.wohlben.qits.telemetry.sourcemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.dto.StoredLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BrowserStackMapperTest {

  private static final String SITE = "@sourcemaps/qits-landing";

  /**
   * Frame 1 is in `save` (column 5 → x.page.ts 11:3), called from column 25 of the same chunk
   * (x.page.ts 21:5, the call site named `save`), called from angular (column 45).
   */
  private static final String STACK =
      "TypeError: boom\n"
          + "    at Xe (https://qits.example/main-ABC.js:1:5)\n"
          + "    at Ye.run (https://qits.example/main-ABC.js:1:25)\n"
          + "    at ze (https://qits.example/main-ABC.js:1:45)\n"
          + "    at Array.forEach (<anonymous>)";

  private final FakeSource source = new FakeSource();
  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  @AfterEach
  void close() {
    pool.shutdownNow();
  }

  private BrowserStackMapper mapper(long budgetMillis) {
    SourceMapCache cache =
        new SourceMapCache(
            source,
            pool,
            1 << 20,
            TimeUnit.MINUTES.toNanos(10),
            TimeUnit.MINUTES.toNanos(1),
            TimeUnit.MINUTES.toNanos(1),
            System::nanoTime);
    return new BrowserStackMapper(
        cache, 10, 3, 3, TimeUnit.MILLISECONDS.toNanos(budgetMillis), System::nanoTime, () -> 0L);
  }

  static StoredLog log(String service, String version, String stack, int severity) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("exception.type", "TypeError");
    if (stack != null) {
      attributes.put("exception.stacktrace", stack);
    }
    Map<String, String> resource = new LinkedHashMap<>();
    resource.put("service.name", service);
    if (version != null) {
      resource.put("service.version", version);
    }
    return new StoredLog(1L, severity, "ERROR", "boom", "", "", service, attributes, resource, 1L);
  }

  private static String mapped(StoredLog log) {
    return log.attributes().get(BrowserStackMapper.MAPPED_STACKTRACE);
  }

  @Test
  void aBrowserErrorsFramesAreMappedThroughItsReleasesMap() {
    source.put(SITE, "2026.1127.1", "main-ABC.js", TestMaps.fixture());
    StoredLog log = log("qits-landing-browser", "2026.1127.1", STACK, 17);
    StoredLog out = mapper(5000).map(List.of(log)).getFirst();
    assertEquals(
        "TypeError: boom\n"
            + "    at save (src/app/x.page.ts:11:3)\n"
            + "    at handle (src/app/x.page.ts:21:5)\n"
            + "    at node_modules/@angular/core/fesm2022/core.mjs:6:1\n"
            + "    at Array.forEach (<anonymous>)",
        mapped(out));
    assertEquals(STACK, out.attributes().get("exception.stacktrace"), "the original is kept");
    assertEquals(List.of(SITE + "|2026.1127.1|main-ABC.js"), source.fetched);
  }

  @Test
  void aFileMissingAtTheReportedVersionIsFoundInAnOlderOne() {
    source.versions.put(SITE, List.of("v4", "v3", "v2", "v1"));
    source.put(SITE, "v2", "main-ABC.js", TestMaps.fixture());
    StoredLog out = mapper(5000).map(List.of(log("qits-landing-browser", "v4", STACK, 17))).getFirst();
    assertTrue(mapped(out).contains("src/app/x.page.ts:11:3"), String.valueOf(mapped(out)));
  }

  @Test
  void onlyThreeOlderVersionsAreAsked() {
    source.versions.put(SITE, List.of("v5", "v4", "v3", "v2", "v1"));
    source.put(SITE, "v1", "main-ABC.js", TestMaps.fixture());
    StoredLog log = log("qits-landing-browser", "v5", STACK, 17);
    assertSame(log, mapper(5000).map(List.of(log)).getFirst());
  }

  @Test
  void whatCannotBeMappedIsLeftAsItCame() {
    BrowserStackMapper mapper = mapper(5000);
    List<StoredLog> logs =
        List.of(
            log("qits-landing-browser", "v1", STACK, 17), // no map published
            log("qits-landing", "v1", STACK, 17), // not a browser service
            log("qits-landing-browser", "v1", STACK, 13), // a WARN
            log("qits-landing-browser", null, STACK, 17), // no version
            log("qits-landing-browser", "v1/../x", STACK, 17), // not a version
            log("qits-landing-browser", "v1", null, 17), // no stack
            log("qits-landing-browser", "v1", "Error: x\n    at <anonymous>", 17),
            log("Qits-Landing-browser", "v1", STACK, 17)); // not an application name
    List<StoredLog> out = mapper.map(logs);
    assertSame(logs, out, "nothing applied: the same batch back");
    for (StoredLog log : out) {
      assertNull(mapped(log));
    }
  }

  @Test
  void aWorkspaceLogIsNotMapped() {
    source.put(SITE, "v1", "main-ABC.js", TestMaps.fixture());
    StoredLog log = log("qits-landing-browser", "v1", STACK, 17);
    Map<String, String> resource = new LinkedHashMap<>(log.resourceAttributes());
    resource.put("qits.workspace.id", "w");
    StoredLog workspace =
        new StoredLog(1L, 17, "ERROR", "boom", "", "", log.serviceName(), log.attributes(), resource, 1L);
    assertSame(workspace, mapper(5000).map(List.of(workspace)).getFirst());
  }

  @Test
  void ingestWaitsAtMostTheBudget() {
    source.put(SITE, "v1", "main-ABC.js", TestMaps.fixture());
    source.hold = new CountDownLatch(1);
    StoredLog log = log("qits-landing-browser", "v1", STACK, 17);
    long start = System.nanoTime();
    List<StoredLog> out = mapper(200).map(List.of(log, log, log));
    long took = System.nanoTime() - start;
    assertTrue(took < TimeUnit.MILLISECONDS.toNanos(1500), "took " + took / 1_000_000 + " ms");
    assertNull(mapped(out.getFirst()), "timed out: as it came");
    source.hold.countDown();
  }

  @Test
  void theMappedStackIsCut() {
    StringBuilder stack = new StringBuilder("TypeError: boom");
    for (int i = 0; i < 3000; i++) {
      stack.append("\n    at Xe (https://qits.example/main-ABC.js:1:5)");
    }
    source.put(SITE, "v1", "main-ABC.js", TestMaps.fixture());
    StoredLog out =
        mapper(5000).map(List.of(log("qits-landing-browser", "v1", stack.toString(), 17))).getFirst();
    assertEquals(BrowserStackMapper.MAX_MAPPED_STACKTRACE, mapped(out).length());
  }

  @Test
  void sourcesAreMadeRepositoryRelative() {
    assertEquals("src/app/a.ts", BrowserStackMapper.normaliseSource("webpack:///./src/app/a.ts"));
    assertEquals("src/app/a.ts", BrowserStackMapper.normaliseSource("../../../src/app/a.ts"));
    assertEquals("src/app/a.ts", BrowserStackMapper.normaliseSource("/src/app/a.ts"));
    assertEquals("node_modules/x/y.mjs", BrowserStackMapper.normaliseSource("./node_modules/x/y.mjs"));
  }
}
