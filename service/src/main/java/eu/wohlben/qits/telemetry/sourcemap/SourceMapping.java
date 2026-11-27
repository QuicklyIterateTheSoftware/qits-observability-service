package eu.wohlben.qits.telemetry.sourcemap;

import eu.wohlben.qits.telemetry.dto.StoredLog;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The ingest's door to source-map mapping (epic qits-2689, qits-2766): {@link #map} is what {@code
 * OtelReceiverResource.logs} calls between decode and the fingerprint stamp.
 *
 * <p>Built at run time in {@link #start()} — the fetch pool, the cache and the mapper are bean
 * fields, never statics, so nothing live reaches the native image heap. Off ({@value #ENABLED_KEY}
 * false, the %dev and %test default) it hands every batch back as it came and builds nothing.
 *
 * <p>The pool is the fetch concurrency bound ({@value #CONCURRENCY_KEY}, 4) with a bounded queue
 * (64); a fetch it cannot queue is refused, and the cache records that as a transient failure. Its
 * threads are daemons, so a hung fetch never holds shutdown.
 */
@ApplicationScoped
public class SourceMapping {

  static final String PREFIX = "qits.observability.sourcemaps.";
  static final String ENABLED_KEY = PREFIX + "enabled";
  static final String BASE_URL_KEY = PREFIX + "base-url";
  static final String MAX_MAP_SIZE_KEY = PREFIX + "max-map-size";
  static final String CACHE_SIZE_KEY = PREFIX + "cache-size";
  static final String BUDGET_KEY = PREFIX + "fetch-budget";
  static final String FRAMES_KEY = PREFIX + "frames";
  static final String FILES_KEY = PREFIX + "files";
  static final String OLDER_VERSIONS_KEY = PREFIX + "older-versions";
  static final String CONCURRENCY_KEY = PREFIX + "concurrency";

  @ConfigProperty(name = ENABLED_KEY, defaultValue = "true")
  boolean enabled;

  @ConfigProperty(name = CACHE_SIZE_KEY, defaultValue = "64M")
  MemorySize cacheSize;

  @ConfigProperty(name = BUDGET_KEY, defaultValue = "5s")
  Duration budget;

  @ConfigProperty(name = FRAMES_KEY, defaultValue = "10")
  int frames;

  @ConfigProperty(name = FILES_KEY, defaultValue = "3")
  int files;

  @ConfigProperty(name = OLDER_VERSIONS_KEY, defaultValue = "3")
  int olderVersions;

  @ConfigProperty(name = CONCURRENCY_KEY, defaultValue = "4")
  int concurrency;

  @Inject DocsSourceMapSource source;

  private ExecutorService pool;
  private BrowserStackMapper mapper;

  @PostConstruct
  void start() {
    if (!enabled) {
      return;
    }
    AtomicInteger threads = new AtomicInteger();
    pool =
        new ThreadPoolExecutor(
            concurrency,
            concurrency,
            30,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(64),
            runnable -> {
              Thread thread = new Thread(runnable, "sourcemap-fetch-" + threads.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            });
    ((ThreadPoolExecutor) pool).allowCoreThreadTimeOut(true);
    SourceMapCache cache =
        new SourceMapCache(
            source,
            pool,
            cacheSize.asLongValue(),
            TimeUnit.MINUTES.toNanos(10),
            TimeUnit.MINUTES.toNanos(1),
            TimeUnit.MINUTES.toNanos(1),
            System::nanoTime);
    mapper =
        new BrowserStackMapper(
            cache,
            frames,
            files,
            olderVersions,
            budget.toNanos(),
            System::nanoTime,
            System::currentTimeMillis);
  }

  @PreDestroy
  void stop() {
    if (pool != null) {
      pool.shutdownNow();
    }
  }

  /** The batch, browser errors mapped; the same list when mapping is off or nothing applied. */
  public List<StoredLog> map(List<StoredLog> logs) {
    return mapper == null || logs.isEmpty() ? logs : mapper.map(logs);
  }
}
