package eu.wohlben.qits.telemetry.control;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.telemetry.dto.MetricPoint;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.dto.StoredSpan;
import eu.wohlben.qits.telemetry.dto.TelemetryLogDto;
import eu.wohlben.qits.telemetry.dto.TelemetryMetricDto;
import eu.wohlben.qits.telemetry.dto.TelemetrySpanDto;
import eu.wohlben.qits.telemetry.dto.TelemetryStreamFrame;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The live stream's fan-out: who is connected, what each one asked for, and the hand-off from
 * ingest to the sockets.
 *
 * <p><b>Fed by {@code OtelReceiverResource}</b>, right after each {@code store.add*}, with the list
 * it just decoded. Not by {@link TelemetryStore}, which stays free of CDI, and not by {@link
 * TelemetryChanged}, which is silent for everything that is not workspace-scoped.
 *
 * <p><b>Ingest never waits and never fails because of it.</b> With nobody subscribed a publish
 * returns at once. Otherwise it hands the batch to one dispatcher thread with a bounded backlog
 * ({@code qits.telemetry.stream.dispatch-backlog} batches) and returns. When the backlog is full
 * the batch is not sent, and each connection it would have matched is told how many records it
 * missed. Nothing here throws back into ingest.
 *
 * <p><b>The dispatcher</b> matches each record against each connection's filter, serializes a
 * matching record once (the same DTO the REST query API returns, in a {@link TelemetryStreamFrame}),
 * and queues the frame on every connection that matched. Each connection's queue is bounded ({@code
 * qits.telemetry.stream.queue-size} frames); see {@link LiveConnection}.
 *
 * <p>Logs at DEBUG only. This service exports its own logs to itself, so an INFO line per frame
 * would feed the stream it describes.
 */
@ApplicationScoped
public class TelemetryLiveFeed {

  private static final Logger LOG = Logger.getLogger(TelemetryLiveFeed.class);

  /**
   * How one record kind is matched, stamped and turned into its wire frame. Package-private because
   * {@link TelemetryRecordSearch} answers with the same matcher and the same frame: one record and
   * one filter give one verdict, live or searched.
   */
  record Kind<T>(
      String name,
      BiPredicate<TelemetryFilter, T> matcher,
      ToLongFunction<T> receivedAt,
      Function<T, Map<String, String>> resource,
      Function<T, Object> dto) {

    boolean matches(TelemetryFilter filter, T record) {
      return matcher.test(filter, record);
    }

    /** The live frame for one record, before serialization. */
    TelemetryStreamFrame frame(T record) {
      return new TelemetryStreamFrame(
          name,
          receivedAt.applyAsLong(record),
          TelemetryStore.keyFor(resource.apply(record)),
          dto.apply(record));
    }
  }

  static final Kind<StoredLog> LOGS =
      new Kind<>(
          TelemetryFilter.LOG,
          TelemetryFilter::matches,
          StoredLog::receivedAtMillis,
          StoredLog::resourceAttributes,
          TelemetryLogDto::of);

  static final Kind<StoredSpan> SPANS =
      new Kind<>(
          TelemetryFilter.SPAN,
          TelemetryFilter::matches,
          StoredSpan::receivedAtMillis,
          StoredSpan::resourceAttributes,
          TelemetrySpanDto::of);

  static final Kind<MetricPoint> METRICS =
      new Kind<>(
          TelemetryFilter.METRIC,
          TelemetryFilter::matches,
          MetricPoint::receivedAtMillis,
          MetricPoint::resourceAttributes,
          TelemetryMetricDto::of);

  @ConfigProperty(name = "qits.telemetry.stream.queue-size", defaultValue = "256")
  int queueSize = 256;

  @ConfigProperty(name = "qits.telemetry.stream.dispatch-backlog", defaultValue = "1024")
  int dispatchBacklog = 1024;

  @Inject ObjectMapper objectMapper;

  private final Map<String, LiveConnection> connections = new ConcurrentHashMap<>();
  private final AtomicLong subscribeFrames = new AtomicLong();

  /**
   * Built at runtime rather than as a constant: Quarkus initialises application classes at build
   * time, and a thread in a static field would end up in the native image heap.
   */
  private ThreadPoolExecutor dispatcher;

  @PostConstruct
  void start() {
    dispatcher =
        new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Math.max(1, dispatchBacklog)),
            runnable -> {
              Thread thread = new Thread(runnable, "telemetry-live-feed");
              thread.setDaemon(true);
              return thread;
            });
  }

  @PreDestroy
  void stop() {
    dispatcher.shutdownNow();
  }

  // --- connections ----------------------------------------------------------------------------

  /** A new connection. It is subscribed to nothing until it sends a subscribe frame. */
  public void opened(TelemetryStreamSink sink) {
    connections.put(sink.id(), new LiveConnection(sink, queueSize));
    LOG.debugf("Live stream connection %s opened", sink.id());
  }

  /** A connection went away. Idempotent. */
  public void closed(String connectionId) {
    LiveConnection connection = connections.remove(connectionId);
    if (connection != null) {
      connection.close();
      LOG.debugf("Live stream connection %s closed", connectionId);
    }
  }

  /**
   * Apply one subscribe frame: it replaces the connection's filter whole. A frame that cannot be
   * read is answered with {@code {"error": "<reason>"}}, and the previous filter stays.
   */
  public void subscribe(String connectionId, String frame) {
    try {
      LiveConnection connection = connections.get(connectionId);
      if (connection == null) {
        return;
      }
      TelemetryFilter filter;
      try {
        filter = TelemetryFilter.parse(objectMapper.readTree(frame));
      } catch (JsonProcessingException notJson) {
        connection.notice(error("not JSON: " + notJson.getOriginalMessage()));
        return;
      } catch (IllegalArgumentException unreadable) {
        connection.notice(error(unreadable.getMessage()));
        return;
      }
      connection.filter(filter);
      LOG.debugf("Live stream connection %s subscribed", connectionId);
    } finally {
      subscribeFrames.incrementAndGet();
    }
  }

  /**
   * How many subscribe frames this process has handled, readable or not. A test seam: the protocol
   * has no acknowledgement, so a test waits for this to move before it ingests. Public because a
   * normal-scoped bean's client proxy forwards public methods only.
   */
  public long subscribeFrames() {
    return subscribeFrames.get();
  }

  // --- ingest ---------------------------------------------------------------------------------

  public void publishSpans(List<StoredSpan> spans) {
    publish(spans, SPANS);
  }

  public void publishLogs(List<StoredLog> logs) {
    publish(logs, LOGS);
  }

  public void publishMetrics(List<MetricPoint> points) {
    publish(points, METRICS);
  }

  private <T> void publish(List<T> records, Kind<T> kind) {
    try {
      if (records == null || records.isEmpty() || !anyoneListening()) {
        return;
      }
      try {
        dispatcher.execute(() -> dispatch(records, kind));
      } catch (RejectedExecutionException full) {
        countDropped(records, kind);
      }
    } catch (RuntimeException unexpected) {
      LOG.debugf(unexpected, "Live feed skipped a batch of %s records", kind.name());
    }
  }

  private boolean anyoneListening() {
    for (LiveConnection connection : connections.values()) {
      if (!connection.filter().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /** On the dispatcher thread: match, serialize once per record, queue per connection. */
  private <T> void dispatch(List<T> records, Kind<T> kind) {
    for (T record : records) {
      String frame = null;
      for (LiveConnection connection : connections.values()) {
        try {
          if (!kind.matches(connection.filter(), record)) {
            continue;
          }
          if (frame == null) {
            frame = frame(kind, record);
            if (frame == null) {
              break;
            }
          }
          connection.offer(frame);
        } catch (RuntimeException unexpected) {
          LOG.debugf(
              unexpected, "Live feed could not deliver a %s to %s", kind.name(), connection.id());
        }
      }
    }
  }

  /**
   * The dispatcher had no room for this batch. Tell each connection how many of these records it
   * asked for; they are gone. This runs on the ingest thread, and only matches: it serializes and
   * sends nothing.
   */
  private <T> void countDropped(List<T> records, Kind<T> kind) {
    for (LiveConnection connection : connections.values()) {
      TelemetryFilter filter = connection.filter();
      if (filter.isEmpty()) {
        continue;
      }
      long matched = 0;
      for (T record : records) {
        if (kind.matches(filter, record)) {
          matched++;
        }
      }
      connection.dropped(matched);
    }
    LOG.debugf("Live feed dispatcher full: dropped a batch of %d %s records", records.size(), kind.name());
  }

  private <T> String frame(Kind<T> kind, T record) {
    try {
      return objectMapper.writeValueAsString(kind.frame(record));
    } catch (JsonProcessingException unserializable) {
      LOG.debugf(unserializable, "Live feed could not serialize a %s", kind.name());
      return null;
    }
  }

  private String error(String reason) {
    try {
      return objectMapper.writeValueAsString(objectMapper.createObjectNode().put("error", reason));
    } catch (JsonProcessingException cannotHappen) {
      return "{\"error\":\"unreadable frame\"}";
    }
  }
}
