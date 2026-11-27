package eu.wohlben.qits.telemetry.sourcemap;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * The decoded source maps and site version lists the ingest has asked for (epic qits-2689,
 * qits-2766), plain Java so {@code SourceMapCacheTest} drives it with a fake source and clock.
 *
 * <ul>
 *   <li><b>Maps</b> are held in an LRU bounded by {@link SourceMap#estimatedBytes} ({@code
 *       maxBytes}, 64 MiB in production) and never re-fetched while held: a docs version is
 *       immutable, so a map once read is the map. A map larger than the whole bound is treated as
 *       unreadable.
 *   <li><b>Absences</b> are remembered too: a 404 or an unreadable map for {@code notFoundNanos}
 *       (10 min), a transient failure for {@code failureNanos} (1 min), so an error storm against a
 *       release without maps costs one request per window, not one per log. At most {@value
 *       #MAX_ABSENCES} are remembered; the oldest go first.
 *   <li><b>Version lists</b> are kept for {@code versionsNanos} (1 min).
 *   <li><b>Single-flight</b>: one fetch per key at a time, run on the given executor, whose size is
 *       the concurrency bound (4 in production). A caller waits only until its own deadline; a
 *       fetch it gave up on keeps running and fills the cache for the next log.
 * </ul>
 */
public final class SourceMapCache {

  static final int MAX_ABSENCES = 10_000;

  /** One map: site, version and the bundle file it maps. */
  public record Key(String site, String version, String file) {}

  private final SourceMapSource source;
  private final ExecutorService executor;
  private final long maxBytes;
  private final long notFoundNanos;
  private final long failureNanos;
  private final long versionsNanos;
  private final LongSupplier nanos;

  private final Object lock = new Object();
  private final LinkedHashMap<Key, SourceMap> maps = new LinkedHashMap<>(16, 0.75f, true);
  private long heldBytes;
  private final LinkedHashMap<Object, Long> absentUntil = new LinkedHashMap<>();
  private final Map<String, Versions> versions = new ConcurrentHashMap<>();
  private final Map<Object, CompletableFuture<?>> inFlight = new ConcurrentHashMap<>();

  private record Versions(List<String> list, long until) {}

  public SourceMapCache(
      SourceMapSource source,
      ExecutorService executor,
      long maxBytes,
      long notFoundNanos,
      long failureNanos,
      long versionsNanos,
      LongSupplier nanos) {
    this.source = source;
    this.executor = executor;
    this.maxBytes = maxBytes;
    this.notFoundNanos = notFoundNanos;
    this.failureNanos = failureNanos;
    this.versionsNanos = versionsNanos;
    this.nanos = nanos;
  }

  /**
   * The map, if it is held or arrives before {@code deadlineNanos} (on {@link #nanos}'s clock);
   * empty when it is absent, unreadable, failed, or still in flight at the deadline.
   */
  public Optional<SourceMap> map(Key key, long deadlineNanos) {
    synchronized (lock) {
      SourceMap held = maps.get(key);
      if (held != null) {
        return Optional.of(held);
      }
      if (absent(key)) {
        return Optional.empty();
      }
    }
    CompletableFuture<SourceMap> flight = flight(key, () -> load(key));
    return await(flight, deadlineNanos);
  }

  /** The site's versions, newest first, if known or fetched before the deadline; else empty. */
  public Optional<List<String>> versions(String site, long deadlineNanos) {
    Versions held = versions.get(site);
    if (held != null && held.until - nanos.getAsLong() > 0) {
      return Optional.of(held.list);
    }
    String key = "versions:" + site;
    synchronized (lock) {
      if (absent(key)) {
        return Optional.empty();
      }
    }
    CompletableFuture<List<String>> flight = flight(key, () -> loadVersions(site, key));
    return await(flight, deadlineNanos);
  }

  /** The bytes the held maps are estimated to cost. */
  public long heldBytes() {
    synchronized (lock) {
      return heldBytes;
    }
  }

  /** How many maps are held. */
  public int heldMaps() {
    synchronized (lock) {
      return maps.size();
    }
  }

  @FunctionalInterface
  private interface Load<V> {
    V run() throws Exception;
  }

  @SuppressWarnings("unchecked")
  private <V> CompletableFuture<V> flight(Object key, Load<V> load) {
    CompletableFuture<V> mine = new CompletableFuture<>();
    CompletableFuture<?> running = inFlight.putIfAbsent(key, mine);
    if (running != null) {
      return (CompletableFuture<V>) running;
    }
    try {
      executor.execute(
          () -> {
            // Out of the in-flight map BEFORE the waiters wake: one that asks again at once must
            // start a new flight (or read the cache), never be handed this finished one.
            V value = null;
            Throwable failure = null;
            try {
              value = load.run();
            } catch (Throwable t) {
              failure = t;
            }
            inFlight.remove(key, mine);
            if (failure != null) {
              mine.completeExceptionally(failure);
            } else {
              mine.complete(value);
            }
          });
    } catch (RuntimeException rejected) {
      inFlight.remove(key, mine);
      mine.completeExceptionally(rejected);
    }
    return mine;
  }

  private <V> Optional<V> await(CompletableFuture<V> flight, long deadlineNanos) {
    long wait = deadlineNanos - nanos.getAsLong();
    try {
      V value = wait <= 0 ? flight.getNow(null) : flight.get(wait, TimeUnit.NANOSECONDS);
      return Optional.ofNullable(value);
    } catch (TimeoutException | ExecutionException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  private SourceMap load(Key key) throws Exception {
    try {
      SourceMap map = source.fetch(key.site(), key.version(), key.file());
      if (map.estimatedBytes() > maxBytes) {
        throw new SourceMap.Unreadable("larger decoded than the whole cache");
      }
      synchronized (lock) {
        SourceMap previous = maps.put(key, map);
        if (previous != null) {
          heldBytes -= previous.estimatedBytes();
        }
        heldBytes += map.estimatedBytes();
        Iterator<Map.Entry<Key, SourceMap>> eldest = maps.entrySet().iterator();
        while (heldBytes > maxBytes && eldest.hasNext()) {
          Map.Entry<Key, SourceMap> entry = eldest.next();
          if (entry.getKey().equals(key)) {
            continue;
          }
          heldBytes -= entry.getValue().estimatedBytes();
          eldest.remove();
        }
      }
      return map;
    } catch (SourceMapSource.NotFound | SourceMap.Unreadable e) {
      remember(key, notFoundNanos);
      throw e;
    } catch (Exception e) {
      remember(key, failureNanos);
      throw e;
    }
  }

  private List<String> loadVersions(String site, Object key) throws Exception {
    try {
      List<String> list = List.copyOf(source.versions(site));
      versions.put(site, new Versions(list, nanos.getAsLong() + versionsNanos));
      return list;
    } catch (SourceMapSource.NotFound e) {
      remember(key, notFoundNanos);
      throw e;
    } catch (Exception e) {
      remember(key, failureNanos);
      throw e;
    }
  }

  private void remember(Object key, long forNanos) {
    synchronized (lock) {
      absentUntil.remove(key);
      absentUntil.put(key, nanos.getAsLong() + forNanos);
      Iterator<Object> oldest = absentUntil.keySet().iterator();
      while (absentUntil.size() > MAX_ABSENCES && oldest.hasNext()) {
        oldest.next();
        oldest.remove();
      }
    }
  }

  /** Under {@link #lock}. */
  private boolean absent(Object key) {
    Long until = absentUntil.get(key);
    if (until == null) {
      return false;
    }
    if (until - nanos.getAsLong() > 0) {
      return true;
    }
    absentUntil.remove(key);
    return false;
  }
}
