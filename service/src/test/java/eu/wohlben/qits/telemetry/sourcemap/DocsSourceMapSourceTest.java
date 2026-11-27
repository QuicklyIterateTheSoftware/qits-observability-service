package eu.wohlben.qits.telemetry.sourcemap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.http.QitsHttpClients;
import eu.wohlben.qits.http.RetryPolicy;
import io.opentelemetry.api.OpenTelemetry;
import io.quarkus.runtime.configuration.MemorySize;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link DocsSourceMapSource} against a stand-in docs wire on a loopback port. */
class DocsSourceMapSourceTest {

  private HttpServer server;
  private final List<String> asked = new ArrayList<>();
  private DocsSourceMapSource source;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/artifacts/docs/docs/",
        exchange -> {
          String path = exchange.getRequestURI().getRawPath();
          asked.add(path);
          byte[] body;
          int status = 200;
          if (path.endsWith("/@sourcemaps/qits-landing/-/v1/main-ABC.js.map")) {
            body = TestMaps.fixture().getBytes(StandardCharsets.UTF_8);
          } else if (path.endsWith("/@sourcemaps/qits-landing/-/v1/big.js.map")) {
            body = ("{\"version\":3,\"x\":\"" + "a".repeat(5000) + "\"}").getBytes(StandardCharsets.UTF_8);
          } else if (path.endsWith("/@sourcemaps/qits-landing/-/v1/down.js.map")) {
            body = new byte[0];
            status = 503;
          } else if (path.endsWith("/@sourcemaps/qits-landing")) {
            body =
                ("{\"name\":\"@sourcemaps/qits-landing\",\"versions\":["
                        + "{\"version\":\"v1\",\"publishedAt\":\"2026-11-27T09:00:00Z\"},"
                        + "{\"version\":\"v3\",\"publishedAt\":\"2026-11-27T11:00:00Z\"},"
                        + "{\"version\":\"../x\",\"publishedAt\":\"2026-11-27T12:00:00Z\"},"
                        + "{\"version\":\"v2\",\"publishedAt\":\"2026-11-27T10:00:00Z\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
          } else {
            body = "not found".getBytes(StandardCharsets.UTF_8);
            status = 404;
          }
          exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
          if (body.length > 0) {
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
    source = new DocsSourceMapSource();
    source.clients = QitsHttpClients.of(OpenTelemetry.noop(), null, RetryPolicy.none());
    source.json = new ObjectMapper();
    source.bearerEnabled = false;
    source.baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/artifacts/docs/docs/";
    source.maxMapSize = new MemorySize(BigInteger.valueOf(4096));
    source.open();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void aPublishedMapIsReadFromItsSiteVersionAndFile() throws Exception {
    SourceMap map = source.fetch("@sourcemaps/qits-landing", "v1", "main-ABC.js");
    assertEquals("save", map.lookup(0, 25).name());
    assertEquals(List.of("/artifacts/docs/docs/@sourcemaps/qits-landing/-/v1/main-ABC.js.map"), asked);
  }

  @Test
  void aMissingMapIsNotFoundAndADownStoreIsTransient() {
    assertThrows(
        SourceMapSource.NotFound.class,
        () -> source.fetch("@sourcemaps/qits-landing", "v1", "other.js"));
    assertThrows(
        IOException.class, () -> source.fetch("@sourcemaps/qits-landing", "v1", "down.js"));
  }

  @Test
  void aMapOverTheCapIsUnreadable() {
    assertThrows(
        SourceMap.Unreadable.class,
        () -> source.fetch("@sourcemaps/qits-landing", "v1", "big.js"));
  }

  @Test
  void nothingButAValidatedCoordinateIsEverAsked() {
    assertThrows(
        SourceMapSource.NotFound.class,
        () -> source.fetch("@sourcemaps/qits-landing", "../v1", "main-ABC.js"));
    assertThrows(
        SourceMapSource.NotFound.class,
        () -> source.fetch("@routes/qits-landing", "v1", "main-ABC.js"));
    assertThrows(
        SourceMapSource.NotFound.class,
        () -> source.fetch("@sourcemaps/qits-landing", "v1", "a/b.js"));
    assertTrue(asked.isEmpty(), "refused before any request: " + asked);
  }

  @Test
  void versionsAreNewestFirstAndOnlyValidOnes() throws Exception {
    assertEquals(List.of("v3", "v2", "v1"), source.versions("@sourcemaps/qits-landing"));
    assertThrows(SourceMapSource.NotFound.class, () -> source.versions("@sourcemaps/qits-other"));
  }
}
