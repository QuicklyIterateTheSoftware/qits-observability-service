package eu.wohlben.qits.telemetry.control;

import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.dto.StoredSource;
import eu.wohlben.qits.telemetry.dto.StoredSpan;
import eu.wohlben.qits.telemetry.dto.TelemetryErrorGroupDto;
import eu.wohlben.qits.telemetry.dto.TelemetryLogDto;
import eu.wohlben.qits.telemetry.dto.TelemetryMetricDto;
import eu.wohlben.qits.telemetry.dto.TelemetrySourceDto;
import eu.wohlben.qits.telemetry.dto.TelemetrySpanDto;
import eu.wohlben.qits.telemetry.dto.TelemetryStoreStateDto;
import eu.wohlben.qits.telemetry.dto.TelemetryTraceDto;
import eu.wohlben.qits.telemetry.dto.TelemetryTraceSummaryDto;
import eu.wohlben.qits.telemetry.error.BadRequestException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one query surface over the {@link TelemetryStore}, shared verbatim by the MCP tools and the
 * REST twins so agents and the UI always see the same answers. All time windows filter on {@code
 * receivedAtMillis} (the server-clock ingest stamp) — a container with a skewed clock can't dodge
 * or fake a window. A null {@code sinceMinutes} means "everything still buffered": the buffers are
 * bounded and recent by construction.
 *
 * <p>Two vocabularies address a bucket, and the difference is not cosmetic. The {@code (repoId,
 * workspaceId)} methods are the MCP tools': an agent's scope is a workspace and it never learns a
 * key. The {@code …In(sourceKey)} methods are the REST surface's, because most telemetry on this
 * platform lands in buckets keyed on {@code service.name}, which no pair can spell. The pair
 * methods delegate to the key methods, so both vocabularies are the same code answering.
 *
 * <p>{@link #sources()} and {@link #storeState()} have no scope to check, which is why they are
 * REST-only and deliberately not MCP tools — see {@code TelemetryMcpTools}' javadoc.
 */
@ApplicationScoped
public class TelemetryQueryService {

  @Inject TelemetryStore store;

  /**
   * A bounded answer: the page, how many matched, and whether the caller is looking at all of them.
   * {@code total} counts matches before truncation, so a screen can say "showing 200 of 1,841"
   * rather than implying 200 is the whole story.
   */
  public record Page<T>(List<T> items, int total, boolean truncated) {

    static <T> Page<T> of(List<T> matches, int limit) {
      return matches.size() <= limit
          ? new Page<>(matches, matches.size(), false)
          : new Page<>(List.copyOf(matches.subList(0, limit)), matches.size(), true);
    }
  }

  /**
   * Which bucket a request names: {@code source} verbatim when given, otherwise the workspace pair.
   * The two are mutually exclusive and {@code source} wins, so a UI that threads {@code ?source=}
   * everywhere never has to clear the pair.
   */
  public static String sourceKey(String source, String repoId, String workspaceId) {
    return source == null || source.isBlank()
        ? TelemetryStore.key(repoId, workspaceId)
        : source;
  }

  /** Every bucket in the buffer, key order, with the counts and age span each one holds. */
  public List<TelemetrySourceDto> sources() {
    return store.sources().stream().map(TelemetryQueryService::toSourceDto).toList();
  }

  /** The buffer's own state: when it started holding this, what it caps at, what it has dropped. */
  public TelemetryStoreStateDto storeState() {
    return new TelemetryStoreStateDto(
        store.startedAt(),
        store.totalBytes(),
        store.maxTotalBytes(),
        new TelemetryStoreStateDto.Caps(
            store.maxSpansPerSource(), store.maxLogsPerSource(), store.maxMetricSeriesPerSource()),
        store.sourceCount(),
        store.evictedSpans(),
        store.evictedLogs(),
        store.droppedMetricSeries());
  }

  private static TelemetrySourceDto toSourceDto(StoredSource source) {
    String key = source.key();
    TelemetrySourceDto.Kind kind;
    String label;
    String repositoryId = null;
    String workspaceId = null;
    if (TelemetryStore.UNSCOPED_KEY.equals(key)) {
      kind = TelemetrySourceDto.Kind.UNSCOPED;
      label = "unscoped";
    } else if (key.startsWith(TelemetryStore.SERVICE_KEY_PREFIX)) {
      kind = TelemetrySourceDto.Kind.SERVICE;
      label = key.substring(TelemetryStore.SERVICE_KEY_PREFIX.length());
    } else {
      kind = TelemetrySourceDto.Kind.WORKSPACE;
      int slash = key.indexOf('/');
      repositoryId = slash < 0 ? key : key.substring(0, slash);
      workspaceId = slash < 0 ? "" : key.substring(slash + 1);
      label = workspaceId;
    }
    return new TelemetrySourceDto(
        key,
        kind,
        label,
        repositoryId,
        workspaceId,
        source.services(),
        source.spans(),
        source.logs(),
        source.metricSeries(),
        source.bytes(),
        instantOrNull(source.oldestReceivedAtMillis()),
        instantOrNull(source.newestReceivedAtMillis()));
  }

  private static Instant instantOrNull(Long millis) {
    return millis == null ? null : Instant.ofEpochMilli(millis);
  }

  /**
   * Error evidence grouped by trace: error-status spans, spans carrying {@code exception} events,
   * and ERROR-severity logs. Groups are newest-first; uncorrelated entries group under an empty
   * trace id.
   */
  public List<TelemetryErrorGroupDto> errors(
      String repoId, String workspaceId, Integer sinceMinutes) {
    return errorsIn(TelemetryStore.key(repoId, workspaceId), null, sinceMinutes, Integer.MAX_VALUE)
        .items();
  }

  /** {@link #errors} over one source, optionally narrowed to a service and bounded to {@code limit}. */
  public Page<TelemetryErrorGroupDto> errorsIn(
      String sourceKey, String service, Integer sinceMinutes, int limit) {
    long cutoff = cutoff(sinceMinutes);
    Map<String, List<StoredSpan>> spansByTrace = new LinkedHashMap<>();
    Map<String, List<StoredLog>> logsByTrace = new LinkedHashMap<>();
    Map<String, Long> newestByTrace = new LinkedHashMap<>();

    for (StoredSpan span : store.spansIn(sourceKey)) {
      if (span.receivedAtMillis() < cutoff
          || !matchesService(service, span.serviceName())
          || !(span.isError() || span.hasExceptionEvent())) {
        continue;
      }
      spansByTrace.computeIfAbsent(span.traceId(), t -> new ArrayList<>()).add(span);
      newestByTrace.merge(span.traceId(), span.receivedAtMillis(), Math::max);
    }
    for (StoredLog log : store.logsIn(sourceKey)) {
      if (log.receivedAtMillis() < cutoff
          || !matchesService(service, log.serviceName())
          || !log.isError()) {
        continue;
      }
      logsByTrace.computeIfAbsent(log.traceId(), t -> new ArrayList<>()).add(log);
      newestByTrace.merge(log.traceId(), log.receivedAtMillis(), Math::max);
    }

    List<TelemetryErrorGroupDto> groups =
        newestByTrace.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .map(
                entry -> {
                  String traceId = entry.getKey();
                  List<StoredSpan> spans = spansByTrace.getOrDefault(traceId, List.of());
                  List<StoredLog> logs = logsByTrace.getOrDefault(traceId, List.of());
                  String serviceName =
                      !spans.isEmpty()
                          ? spans.getFirst().serviceName()
                          : logs.getFirst().serviceName();
                  return new TelemetryErrorGroupDto(
                      traceId,
                      serviceName,
                      spans.stream()
                          .sorted(Comparator.comparingLong(StoredSpan::startEpochNanos))
                          .map(TelemetrySpanDto::of)
                          .toList(),
                      logs.stream()
                          .sorted(Comparator.comparingLong(StoredLog::epochNanos))
                          .map(TelemetryLogDto::of)
                          .toList());
                })
            .toList();
    return Page.of(groups, limit);
  }

  /** The full trace: its spans ordered by start time plus every log correlated to it. */
  public TelemetryTraceDto trace(String repoId, String workspaceId, String traceId) {
    return traceIn(TelemetryStore.key(repoId, workspaceId), traceId);
  }

  /**
   * {@link #trace} over one source. An unknown trace id and an evicted one answer identically — an
   * empty trace, never a 404 — because the buffer genuinely cannot tell them apart and the surface
   * must not invent a distinction the store does not hold.
   */
  public TelemetryTraceDto traceIn(String sourceKey, String traceId) {
    List<TelemetrySpanDto> spans =
        store.traceIn(sourceKey, traceId).stream()
            .sorted(Comparator.comparingLong(StoredSpan::startEpochNanos))
            .map(TelemetrySpanDto::of)
            .toList();
    List<TelemetryLogDto> logs =
        store.logsIn(sourceKey).stream()
            .filter(log -> traceId.equals(log.traceId()))
            .sorted(Comparator.comparingLong(StoredLog::epochNanos))
            .map(TelemetryLogDto::of)
            .toList();
    return new TelemetryTraceDto(traceId, spans, logs);
  }

  /**
   * The buffered traces of one source, summarised. The store already indexes spans by trace on
   * ingest, so this groups nothing — it reads the index and folds each entry into a row.
   *
   * <p>{@code thresholdMs} filters on the trace's whole span (0 admits everything, which in a
   * buffer this short is a reasonable list). {@code service} keeps a trace if any of its buffered
   * spans came from that service, so a cross-service trace stays visible from either end.
   */
  public Page<TelemetryTraceSummaryDto> tracesIn(
      String sourceKey,
      String service,
      long thresholdMs,
      Integer sinceMinutes,
      SpanSort sort,
      int limit) {
    long cutoff = cutoff(sinceMinutes);
    List<TelemetryTraceSummaryDto> summaries = new ArrayList<>();
    for (Map.Entry<String, List<StoredSpan>> entry : store.tracesIn(sourceKey).entrySet()) {
      List<StoredSpan> spans = entry.getValue();
      if (spans.isEmpty()) {
        continue;
      }
      if (spans.stream().noneMatch(s -> s.receivedAtMillis() >= cutoff)) {
        continue;
      }
      if (spans.stream().noneMatch(s -> matchesService(service, s.serviceName()))) {
        continue;
      }
      TelemetryTraceSummaryDto summary = summarise(entry.getKey(), spans);
      if (summary.durationMs() >= thresholdMs) {
        summaries.add(summary);
      }
    }
    Comparator<TelemetryTraceSummaryDto> order =
        sort == SpanSort.RECENT
            ? Comparator.comparingLong(TelemetryTraceSummaryDto::startEpochNanos).reversed()
            : Comparator.comparingLong(TelemetryTraceSummaryDto::durationMs).reversed();
    return Page.of(summaries.stream().sorted(order).toList(), limit);
  }

  private static TelemetryTraceSummaryDto summarise(String traceId, List<StoredSpan> spans) {
    StoredSpan earliest = spans.getFirst();
    long start = Long.MAX_VALUE;
    long end = Long.MIN_VALUE;
    int errorSpans = 0;
    boolean hasException = false;
    LinkedHashSet<String> services = new LinkedHashSet<>();
    StoredSpan root = null;
    for (StoredSpan span : spans) {
      if (span.startEpochNanos() < earliest.startEpochNanos()) {
        earliest = span;
      }
      start = Math.min(start, span.startEpochNanos());
      end = Math.max(end, span.endEpochNanos());
      if (span.isError()) {
        errorSpans++;
      }
      hasException |= span.hasExceptionEvent();
      services.add(span.serviceName());
      if (root == null && (span.parentSpanId() == null || span.parentSpanId().isEmpty())) {
        root = span;
      }
    }
    boolean rootMissing = root == null;
    StoredSpan shown = rootMissing ? earliest : root;
    return new TelemetryTraceSummaryDto(
        traceId,
        shown.name(),
        shown.serviceName(),
        routeOf(shown),
        List.copyOf(services),
        start,
        (end - start) / 1_000_000,
        spans.size(),
        errorSpans,
        hasException,
        rootMissing);
  }

  /** The shown span's templated route, its concrete path, or null — never derived from the name. */
  private static String routeOf(StoredSpan span) {
    String route = span.attributes().get("http.route");
    if (route != null && !route.isBlank()) {
      return route;
    }
    String path = span.attributes().get("url.path");
    return path != null && !path.isBlank() ? path : null;
  }

  /** How {@link #slowSpans} and the trace list order their results. */
  public enum SpanSort {
    /** Slowest first — the "what's slow" lens. */
    DURATION,
    /** Newest start time first — the "what did I just do" lens. */
    RECENT
  }

  /** Spans at least {@code thresholdMs} long, ordered per {@code sort}. */
  public List<TelemetrySpanDto> slowSpans(
      String repoId, String workspaceId, long thresholdMs, Integer sinceMinutes, SpanSort sort) {
    return slowSpansIn(
            TelemetryStore.key(repoId, workspaceId),
            null,
            thresholdMs,
            sinceMinutes,
            sort,
            Integer.MAX_VALUE)
        .items();
  }

  /** {@link #slowSpans} over one source, optionally narrowed to a service and bounded. */
  public Page<TelemetrySpanDto> slowSpansIn(
      String sourceKey,
      String service,
      long thresholdMs,
      Integer sinceMinutes,
      SpanSort sort,
      int limit) {
    long cutoff = cutoff(sinceMinutes);
    Comparator<StoredSpan> order =
        sort == SpanSort.RECENT
            ? Comparator.comparingLong(StoredSpan::startEpochNanos).reversed()
            : Comparator.comparingLong(StoredSpan::durationMs).reversed();
    List<TelemetrySpanDto> spans =
        store.spansIn(sourceKey).stream()
            .filter(span -> span.receivedAtMillis() >= cutoff && span.durationMs() >= thresholdMs)
            .filter(span -> matchesService(service, span.serviceName()))
            .sorted(order)
            .map(TelemetrySpanDto::of)
            .toList();
    return Page.of(spans, limit);
  }

  /**
   * The OTel severity floors, by the word an operator picks from a menu.
   *
   * <p>The scale is 1–24 in six bands of four ({@code TRACE}, {@code TRACE2}…), so a band is named
   * by its <em>first</em> number and the filter is {@code >=}: picking {@code WARN} admits WARN,
   * WARN2..4 and everything above, which is what "warnings and worse" means to the person asking.
   * {@link StoredLog#SEVERITY_ERROR} is this table's ERROR entry and stays the single definition of
   * where the error range starts.
   */
  private static final Map<String, Integer> SEVERITY_FLOORS =
      Map.of(
          "TRACE", 1,
          "DEBUG", 5,
          "INFO", 9,
          "WARN", 13,
          "ERROR", StoredLog.SEVERITY_ERROR,
          "FATAL", 21);

  /**
   * The severity floor a caller named, or null for "every severity", which is the default.
   *
   * <p>Takes either a band name ({@code TRACE}/{@code DEBUG}/{@code INFO}/{@code WARN}/{@code
   * ERROR}/{@code FATAL}, case-insensitively) or a raw number on the OTel 1–24 scale. Both are
   * accepted because both are what callers have: a UI offers the six words, and an agent reading a
   * record's {@code severityNumber} back has the number.
   *
   * <p><strong>An unrecognised value is a 400, not a silent "everything".</strong> A misspelt
   * filter that quietly stopped filtering would answer a screen full of INFO under a heading that
   * says ERROR, which is the one wrong answer a log filter must never give. {@code WARNING} is
   * accepted as a synonym of {@code WARN} because the OTel text field routinely spells it that way
   * and refusing the word an exporter itself prints would be a puzzle rather than a guard.
   */
  public static Integer severityFloor(String minSeverity) {
    if (minSeverity == null || minSeverity.isBlank()) {
      return null;
    }
    String name = minSeverity.trim().toUpperCase(Locale.ROOT);
    if ("WARNING".equals(name)) {
      name = "WARN";
    }
    Integer floor = SEVERITY_FLOORS.get(name);
    if (floor != null) {
      return floor;
    }
    try {
      int number = Integer.parseInt(name);
      if (number >= 1 && number <= 24) {
        return number;
      }
    } catch (NumberFormatException ignored) {
      // Fall through to the one message: a word we do not know and a number we cannot use are the
      // same mistake from the caller's side, and listing the accepted words fixes both.
    }
    throw new BadRequestException(
        "minSeverity must be one of TRACE, DEBUG, INFO, WARN, ERROR, FATAL or a number 1-24");
  }

  /**
   * Logs whose body or severity text contains {@code query} (case-insensitive), oldest first.
   * {@code service} additionally narrows to one service name (the UI's log-tail filter).
   */
  public List<TelemetryLogDto> searchLogs(
      String repoId, String workspaceId, String query, Integer sinceMinutes, String service) {
    return searchLogsIn(
            TelemetryStore.key(repoId, workspaceId),
            query,
            sinceMinutes,
            service,
            null,
            Integer.MAX_VALUE)
        .items();
  }

  /**
   * {@link #searchLogs} over one source, bounded. Bounding keeps the <em>newest</em> matches and
   * still returns them oldest-first: a tail wants the end of the buffer, not its beginning.
   *
   * <p>{@code minSeverity} is a floor on the OTel severity number ({@link #severityFloor}), and it
   * is applied here rather than left to the caller for one reason: this method truncates. A screen
   * that asked for 200 records and filtered them itself would be filtering a page the buffer had
   * already cut, so "the 3 errors in the last 200 records" would masquerade as "the last 3 errors".
   *
   * <p><strong>A floor excludes records with no severity at all.</strong> {@code severityNumber} is
   * 0 when an exporter stamped none, and 0 satisfies no floor — an unset record is not quietly
   * promoted into a band it never claimed. Those records are still there with no floor applied,
   * which is the default.
   */
  public Page<TelemetryLogDto> searchLogsIn(
      String sourceKey,
      String query,
      Integer sinceMinutes,
      String service,
      Integer minSeverity,
      int limit) {
    long cutoff = cutoff(sinceMinutes);
    String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
    List<TelemetryLogDto> logs =
        store.logsIn(sourceKey).stream()
            .filter(log -> log.receivedAtMillis() >= cutoff)
            .filter(log -> matchesService(service, log.serviceName()))
            .filter(log -> minSeverity == null || log.severityNumber() >= minSeverity)
            .filter(
                log ->
                    needle.isEmpty()
                        || log.body().toLowerCase(Locale.ROOT).contains(needle)
                        || log.severityText().toLowerCase(Locale.ROOT).contains(needle))
            .sorted(Comparator.comparingLong(StoredLog::epochNanos))
            .map(TelemetryLogDto::of)
            .toList();
    return logs.size() <= limit
        ? new Page<>(logs, logs.size(), false)
        : new Page<>(List.copyOf(logs.subList(logs.size() - limit, logs.size())), logs.size(), true);
  }

  /** The latest point of every metric series, optionally narrowed to one metric name. */
  public List<TelemetryMetricDto> metrics(String repoId, String workspaceId, String name) {
    return metricsIn(TelemetryStore.key(repoId, workspaceId), name, null);
  }

  /**
   * {@link #metrics} over one source. There is no limit here on purpose: the store keeps one point
   * per series and the series count is already capped, so the answer is bounded by construction.
   */
  public List<TelemetryMetricDto> metricsIn(String sourceKey, String name, String service) {
    return store.metricsIn(sourceKey).stream()
        .filter(point -> name == null || name.isBlank() || name.equals(point.name()))
        .filter(point -> matchesService(service, point.serviceName()))
        .sorted(Comparator.comparing(point -> point.name()))
        .map(TelemetryMetricDto::of)
        .toList();
  }

  private static boolean matchesService(String service, String serviceName) {
    return service == null || service.isBlank() || service.equals(serviceName);
  }

  private static long cutoff(Integer sinceMinutes) {
    return sinceMinutes == null
        ? Long.MIN_VALUE
        : System.currentTimeMillis() - sinceMinutes * 60_000L;
  }
}
