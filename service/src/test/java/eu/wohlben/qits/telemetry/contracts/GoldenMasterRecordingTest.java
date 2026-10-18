package eu.wohlben.qits.telemetry.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import eu.wohlben.qits.telemetry.api.OtelReceiverResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * <b>Records qits-observability's provider golden masters</b> — {@code golden-masters/} at the
 * repository root, the source of the published golden-master packages ({@code
 * eu.wohlben.qits:qits-observability-golden-masters}, {@code @qits/observability-golden-masters})
 * consumers write their pacts against.
 *
 * <p>For each (state, operation) pair in {@link #INTERACTIONS} it runs the state ({@link
 * ProviderStates}), calls the operation and renders {@code
 * golden-masters/<state-slug>/<operationId>.json}; then it renders {@code golden-masters/index.json}
 * in the format qits-projects-service set (format version 1).
 *
 * <p><b>Freezing.</b> The states fix every id and every record time, so the one live value is an
 * instant the buffer stamps itself ({@code startedAt}). Every string value shaped like an ISO-8601
 * instant becomes {@value #FROZEN_INSTANT}, and its path goes in the index's {@code frozen.instants}.
 *
 * <p><b>Operation ids</b> are the ones the served OpenAPI declares ({@code @Operation} on {@code
 * WorkspaceTelemetryController}). Renaming one is a contract change. The index records each
 * request's route as {@code path} and its query as {@code query}, and a request body only for an
 * operation whose OpenAPI declares one — the recording fails on a mismatch.
 *
 * <p><b>The OTLP receiver</b> ({@code exportLogs}) is hidden from the served OpenAPI but carries an
 * operationId, and that is all a recording needs. Its request is protobuf, which no pact matches, so
 * it is recorded without a body: the index holds its {@code contentType} and its request {@code
 * headers} (the bearer, as {@code {authorization}}), and the call sends a zero-length body — an
 * empty {@code ExportLogsServiceRequest}, which decodes to nothing. Its answer is protobuf too, so
 * the file holds {@code {}}: a consumer reads its status only.
 *
 * <p>It <b>compares by default</b> and fails with a unified diff per differing file — including a
 * committed {@code .json} no interaction produces any more. {@code -Dgolden.update=true} (or {@code
 * QITS_GOLDEN_UPDATE=true}) rewrites instead, and deletes such stale files; see {@link
 * GoldenFiles}.
 */
@QuarkusTest
class GoldenMasterRecordingTest {

  static final int FORMAT_VERSION = 1;

  /** The application name, as every provider's index names itself. */
  static final String PROVIDER = "qits-observability";

  static final String FROZEN_INSTANT = "2026-01-01T00:00:00Z";

  static final Pattern INSTANT =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})");

  private static final String API = "/observability/api/telemetry";

  private static final String OTEL = "/observability/api/otel/v1";

  private static final String PROTOBUF = "application/x-protobuf";

  /**
   * One recorded interaction. {@code path}, {@code body} and the {@code headers}' values may name a
   * state param as {@code {name}}; the call uses the param's value, the index keeps the placeholder.
   * {@code contentType} names a request type other than JSON (null: JSON, when there is a body); a
   * non-JSON interaction is recorded without its body and with a {@code {}} answer.
   */
  record Interaction(
      String state,
      String operationId,
      String method,
      String path,
      String body,
      int status,
      String contentType,
      Map<String, String> headers) {

    Interaction(
        String state, String operationId, String method, String path, String body, int status) {
      this(state, operationId, method, path, body, status, null, Map.of());
    }
  }

  private static final String ONE = ProviderStates.TELEMETRY_FROM_ONE_SERVICE;
  private static final String EMPTY = ProviderStates.AN_EMPTY_TELEMETRY_BUFFER;
  private static final String EVERY_KIND = ProviderStates.TELEMETRY_RECORDS_OF_EVERY_KIND;

  static final List<Interaction> INTERACTIONS =
      List.of(
          new Interaction(ONE, "getTelemetryStore", "GET", API + "/store", null, 200),
          new Interaction(ONE, "listTelemetrySources", "GET", API + "/sources", null, 200),
          new Interaction(
              ONE, "listTelemetryErrors", "GET", API + "/errors?source={source}", null, 200),
          new Interaction(
              ONE, "listTelemetryTraces", "GET", API + "/traces?source={source}", null, 200),
          new Interaction(
              ONE,
              "getTelemetryTrace",
              "GET",
              API + "/traces/{traceId}?source={source}",
              null,
              200),
          new Interaction(
              ONE, "listSlowSpans", "GET", API + "/slow-spans?source={source}", null, 200),
          new Interaction(
              ONE, "searchTelemetryLogs", "GET", API + "/logs?source={source}", null, 200),
          new Interaction(
              ONE, "listTelemetryMetrics", "GET", API + "/metrics?source={source}", null, 200),
          new Interaction(
              ONE,
              "searchTelemetryRecords",
              "POST",
              API + "/records/search",
              "{\"subscribe\": [{\"conditions\": [{\"field\": \"service\", \"op\": \"exact\","
                  + " \"value\": \"{service}\"}]}], \"source\": \"{source}\"}",
              200),
          new Interaction(
              EVERY_KIND,
              "searchTelemetryRecords",
              "POST",
              API + "/records/search",
              "{\"subscribe\": [{\"conditions\": []}], \"since\": \"{since}\", \"until\":"
                  + " \"{until}\", \"limit\": 100, \"source\": \"{source}\"}",
              200),
          new Interaction(
              EVERY_KIND,
              "searchTelemetryLogs",
              "GET",
              API + "/logs?source={source}&sinceMinutes=60",
              null,
              200),
          new Interaction(EMPTY, "getTelemetryStore", "GET", API + "/store", null, 200),
          new Interaction(EMPTY, "listTelemetrySources", "GET", API + "/sources", null, 200),
          // What qits-runner-toolkit's Telemetry sends (qits-1163): protobuf under a bearer.
          new Interaction(
              EMPTY,
              OtelReceiverResource.EXPORT_LOGS,
              "POST",
              OTEL + "/logs",
              null,
              200,
              PROTOBUF,
              Map.of("Authorization", "{authorization}")));

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject ProviderStates states;
  @Inject ContractClock clock;

  @TestHTTPResource("/")
  URL base;

  /** A state fixes the clock; no other test may see it fixed. */
  @AfterEach
  void releaseClock() {
    clock.release();
  }

  @Test
  void goldenMastersMatchTheProvider() throws Exception {
    Path dir = GoldenFiles.repositoryRoot().resolve("golden-masters");
    boolean update = GoldenFiles.updating();
    List<String> failures = new ArrayList<>();
    Set<String> written = new TreeSet<>();

    Map<String, ObjectNode> indexStates = new TreeMap<>();
    Map<String, Map<String, ObjectNode>> indexOperations = new TreeMap<>();

    Set<String> takesBody = operationsTakingABody();
    for (Interaction interaction : INTERACTIONS) {
      if ((interaction.body() != null) != takesBody.contains(interaction.operationId())) {
        failures.add(
            interaction.operationId()
                + (interaction.body() != null
                    ? " takes no request body, but the recording sends one: record null."
                    : " takes a request body, but the recording sends none."));
      }
      Map<String, String> params = states.params(interaction.state());
      Set<String> instants = new LinkedHashSet<>();
      JsonNode body = freeze(call(interaction, params), "$", instants);
      String slug = ProviderStates.slug(interaction.state());
      String file = slug + "/" + interaction.operationId() + ".json";

      ObjectNode frozenParams = JsonNodeFactory.instance.objectNode();
      params.forEach(frozenParams::put);
      ObjectNode state = indexStates.get(slug);
      if (state == null) {
        state = JsonNodeFactory.instance.objectNode();
        state.put("name", interaction.state());
        state.put("slug", slug);
        state.set("params", frozenParams);
        state.set("dependsOn", JsonNodeFactory.instance.arrayNode());
        indexStates.put(slug, state);
      } else if (!state.get("params").equals(frozenParams)) {
        failures.add("State '" + interaction.state() + "' gave different params per operation");
      }

      ObjectNode operation = JsonNodeFactory.instance.objectNode();
      operation.put("operationId", interaction.operationId());
      operation.put("method", interaction.method());
      // The path is the route alone and the query its own object, as the consumers' pacts send it.
      int at = interaction.path().indexOf('?');
      operation.put("path", at < 0 ? interaction.path() : interaction.path().substring(0, at));
      if (at >= 0) {
        ObjectNode query = operation.putObject("query");
        for (String pair : interaction.path().substring(at + 1).split("&")) {
          int eq = pair.indexOf('=');
          query.put(
              URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
              eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
      }
      if (!interaction.headers().isEmpty()) {
        ObjectNode headers = operation.putObject("headers");
        new TreeMap<>(interaction.headers()).forEach(headers::put);
      }
      if (interaction.contentType() != null) {
        operation.put("contentType", interaction.contentType());
      }
      if (interaction.body() != null) {
        operation.set("body", JSON.readTree(interaction.body()));
      }
      operation.put("status", interaction.status());
      operation.put("file", file);
      ObjectNode frozen = operation.putObject("frozen");
      frozen.putArray("ids");
      ArrayNode instantPaths = frozen.putArray("instants");
      instants.forEach(instantPaths::add);
      frozen.putArray("strings");
      frozen.putNull("listFilteredTo");
      if (indexOperations
              .computeIfAbsent(slug, k -> new TreeMap<>())
              .put(interaction.operationId(), operation)
          != null) {
        failures.add("Duplicate interaction " + file);
      }

      written.add(file);
      check(dir.resolve(file), GoldenJson.render(body), update, failures);
    }

    ObjectNode index = JsonNodeFactory.instance.objectNode();
    index.put("formatVersion", FORMAT_VERSION);
    index.put("provider", PROVIDER);
    ArrayNode stateArray = index.putArray("states");
    indexStates.forEach(
        (slug, state) -> {
          ArrayNode operations = state.putArray("operations");
          indexOperations.get(slug).values().forEach(operations::add);
          stateArray.add(state);
        });
    written.add("index.json");
    check(dir.resolve("index.json"), GoldenJson.render(index), update, failures);

    for (String stale : committedJson(dir)) {
      if (written.contains(stale)) {
        continue;
      }
      if (update) {
        Files.delete(dir.resolve(stale));
      } else {
        failures.add(
            dir.resolve(stale)
                + " is committed but no interaction records it any more — rerun with"
                + " -Dgolden.update=true to delete it.");
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  /** The operation's answer, with the state's params put into its path and body. */
  private JsonNode call(Interaction interaction, Map<String, String> params) throws Exception {
    String path = fill(interaction.path(), params);
    HttpRequest.BodyPublisher body =
        interaction.body() == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(fill(interaction.body(), params));
    String type = interaction.contentType() == null ? "application/json" : interaction.contentType();
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(base.toString()).resolve(path))
            .header("Accept", type)
            .method(interaction.method(), body);
    if (interaction.body() != null || interaction.contentType() != null) {
      request.header("Content-Type", type);
    }
    interaction.headers().forEach((name, value) -> request.header(name, fill(value, params)));
    HttpResponse<String> response;
    try (HttpClient client = HttpClient.newHttpClient()) {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    if (response.statusCode() != interaction.status()) {
      throw new AssertionError(
          interaction.method()
              + " "
              + path
              + " in state '"
              + interaction.state()
              + "' answered "
              + response.statusCode()
              + ", expected "
              + interaction.status()
              + ": "
              + response.body());
    }
    if (interaction.contentType() != null) {
      // A protobuf answer: nothing a pact reads but the status.
      return JsonNodeFactory.instance.objectNode();
    }
    return JSON.readTree(response.body());
  }

  /** The operationIds whose operation declares a request body, read off the served openapi. */
  private Set<String> operationsTakingABody() throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(base.toString()).resolve("/observability/q/openapi?format=json"))
            .GET()
            .build();
    HttpResponse<String> response;
    try (HttpClient client = HttpClient.newHttpClient()) {
      response = client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    if (response.statusCode() != 200) {
      throw new AssertionError("The openapi answered " + response.statusCode());
    }
    Set<String> ids = new TreeSet<>();
    JSON.readTree(response.body())
        .path("paths")
        .forEach(
            path ->
                path.forEach(
                    operation -> {
                      if (operation.has("operationId") && operation.has("requestBody")) {
                        ids.add(operation.get("operationId").asText());
                      }
                    }));
    return ids;
  }

  private static String fill(String template, Map<String, String> params) {
    String filled = template;
    for (Map.Entry<String, String> param : params.entrySet()) {
      filled = filled.replace("{" + param.getKey() + "}", param.getValue());
    }
    return filled;
  }

  /** The node with every instant-shaped string frozen, its path added to {@code instants}. */
  static JsonNode freeze(JsonNode node, String path, Set<String> instants) {
    if (node.isTextual() && INSTANT.matcher(node.asText()).matches()) {
      instants.add(path);
      return TextNode.valueOf(FROZEN_INSTANT);
    }
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      List<String> names = new ArrayList<>();
      object.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        object.set(name, freeze(object.get(name), path + "." + name, instants));
      }
    } else if (node.isArray()) {
      ArrayNode array = (ArrayNode) node;
      for (int i = 0; i < array.size(); i++) {
        array.set(i, freeze(array.get(i), path + "[*]", instants));
      }
    }
    return node;
  }

  private static void check(Path golden, String actual, boolean update, List<String> failures) {
    String failure = GoldenFiles.check(golden, actual, update, UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  /** Every committed {@code .json} under the directory, relative and {@code /}-separated. */
  private static List<String> committedJson(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".json"))
          .map(p -> dir.relativize(p).toString().replace('\\', '/'))
          .sorted()
          .toList();
    }
  }
}
