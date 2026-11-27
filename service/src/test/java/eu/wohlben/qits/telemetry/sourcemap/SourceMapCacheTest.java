package eu.wohlben.qits.telemetry.sourcemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SourceMapCacheTest {

  private static final String SITE = "@sourcemaps/qits-landing";
  private static final long MIN = TimeUnit.MINUTES.toNanos(1);

  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final FakeSource source = new FakeSource();
  private final ExecutorService pool = Executors.newFixedThreadPool(2);

  private SourceMapCache cache(long maxBytes) {
    return new SourceMapCache(source, pool, maxBytes, 10 * MIN, MIN, MIN, clock::get);
  }

  private long later() {
    return clock.get() + TimeUnit.SECONDS.toNanos(5);
  }

  @AfterEach
  void close() {
    pool.shutdownNow();
  }

  @Test
  void aMapIsFetchedOnceAndThenHeld() {
    source.put(SITE, "v1", "main-A.js", TestMaps.fixture());
    SourceMapCache cache = cache(1 << 20);
    SourceMapCache.Key key = new SourceMapCache.Key(SITE, "v1", "main-A.js");
    assertTrue(cache.map(key, later()).isPresent());
    assertTrue(cache.map(key, later()).isPresent());
    clock.addAndGet(100 * MIN);
    assertTrue(cache.map(key, later()).isPresent(), "a version is immutable: held for good");
    assertEquals(1, source.fetched.size());
  }

  @Test
  void aMissingMapIsRememberedForTenMinutesAndAFailureForOne() {
    SourceMapCache cache = cache(1 << 20);
    SourceMapCache.Key missing = new SourceMapCache.Key(SITE, "v1", "main-A.js");
    assertTrue(cache.map(missing, later()).isEmpty());
    assertTrue(cache.map(missing, later()).isEmpty());
    assertEquals(1, source.fetched.size(), "the 404 is remembered");
    clock.addAndGet(9 * MIN);
    assertTrue(cache.map(missing, later()).isEmpty());
    assertEquals(1, source.fetched.size());
    clock.addAndGet(2 * MIN);
    source.put(SITE, "v1", "main-A.js", TestMaps.fixture());
    assertTrue(cache.map(missing, later()).isPresent(), "asked again after ten minutes");

    source.failing = true;
    SourceMapCache.Key failed = new SourceMapCache.Key(SITE, "v2", "main-A.js");
    assertTrue(cache.map(failed, later()).isEmpty());
    int asked = source.fetched.size();
    clock.addAndGet(MIN / 2);
    assertTrue(cache.map(failed, later()).isEmpty());
    assertEquals(asked, source.fetched.size(), "a failure is remembered for a minute");
    clock.addAndGet(MIN);
    source.failing = false;
    source.put(SITE, "v2", "main-A.js", TestMaps.fixture());
    assertTrue(cache.map(failed, later()).isPresent());
  }

  @Test
  void theHeldMapsStayWithinTheByteBoundOldestOut() throws Exception {
    long one = TestMaps.parse(TestMaps.fixture()).estimatedBytes();
    SourceMapCache cache = cache(one * 2);
    for (String v : List.of("v1", "v2", "v3")) {
      source.put(SITE, v, "main-A.js", TestMaps.fixture());
      assertTrue(cache.map(new SourceMapCache.Key(SITE, v, "main-A.js"), later()).isPresent());
    }
    assertEquals(2, cache.heldMaps());
    assertTrue(cache.heldBytes() <= one * 2);
    cache.map(new SourceMapCache.Key(SITE, "v1", "main-A.js"), later());
    assertEquals(4, source.fetched.size(), "v1 was evicted and is fetched again");
  }

  @Test
  void aMapLargerThanTheWholeBoundIsNotHeld() {
    source.put(SITE, "v1", "main-A.js", TestMaps.fixture());
    SourceMapCache cache = cache(10);
    assertTrue(cache.map(new SourceMapCache.Key(SITE, "v1", "main-A.js"), later()).isEmpty());
    assertEquals(0, cache.heldMaps());
  }

  @Test
  void aCallerWaitsOnlyUntilItsDeadlineAndTheFetchFillsTheCacheLater() throws Exception {
    source.put(SITE, "v1", "main-A.js", TestMaps.fixture());
    source.hold = new CountDownLatch(1);
    SourceMapCache cache = new SourceMapCache(source, pool, 1 << 20, 10 * MIN, MIN, MIN, System::nanoTime);
    SourceMapCache.Key key = new SourceMapCache.Key(SITE, "v1", "main-A.js");
    long start = System.nanoTime();
    assertTrue(cache.map(key, start + TimeUnit.MILLISECONDS.toNanos(100)).isEmpty());
    assertTrue(cache.map(key, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50)).isEmpty());
    assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2), "never past the deadline");
    source.hold.countDown();
    assertTrue(cache.map(key, System.nanoTime() + TimeUnit.SECONDS.toNanos(5)).isPresent());
    assertEquals(1, source.fetched.size(), "single-flight: one fetch for three asks");
  }

  @Test
  void versionsAreKeptForAMinute() {
    source.versions.put(SITE, List.of("v3", "v2", "v1"));
    SourceMapCache cache = cache(1 << 20);
    assertEquals(List.of("v3", "v2", "v1"), cache.versions(SITE, later()).orElseThrow());
    cache.versions(SITE, later());
    assertEquals(1, source.fetched.size());
    clock.addAndGet(2 * MIN);
    cache.versions(SITE, later());
    assertEquals(2, source.fetched.size());
  }
}
