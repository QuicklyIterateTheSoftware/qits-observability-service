package eu.wohlben.qits.telemetry.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.http.UpgradeRejectedException;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A live-stream client that never leaves this JVM: a real Vert.x WebSocket dialling the real
 * endpoint. The server cannot tell it from {@code qits observe}. It holds no state and answers
 * nothing, so a test can send the wrong frames too.
 */
public final class StreamClient implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Vertx vertx;
  private final WebSocketClient client;
  private final WebSocket socket;
  private final BlockingQueue<String> received = new LinkedBlockingQueue<>();

  /** Dial with no headers: the identity is the test's own {@code @TestSecurity}, if any. */
  public static StreamClient dial(URI endpoint) throws Exception {
    return dial(endpoint, Map.of());
  }

  /** Dial with headers on the upgrade, which is where the socket's role check runs. */
  public static StreamClient dial(URI endpoint, Map<String, String> headers) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      WebSocketClient client = vertx.createWebSocketClient();
      WebSocketConnectOptions options =
          new WebSocketConnectOptions()
              .setHost(endpoint.getHost())
              .setPort(endpoint.getPort())
              .setURI(endpoint.getPath());
      headers.forEach(options::addHeader);
      WebSocket socket =
          client.connect(options).toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
      return new StreamClient(vertx, client, socket);
    } catch (Exception refused) {
      vertx.close();
      throw refused;
    }
  }

  /**
   * The HTTP status the server refused the upgrade with. 101 when it accepted, so an assertion on
   * 401 or 403 fails with a readable number.
   */
  public static int refusal(URI endpoint, Map<String, String> headers) {
    try (StreamClient accepted = dial(endpoint, headers)) {
      return 101;
    } catch (Exception refused) {
      for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
        if (cause instanceof UpgradeRejectedException rejected) {
          return rejected.getStatus();
        }
      }
      throw new AssertionError("the upgrade failed without an HTTP status", refused);
    }
  }

  private StreamClient(Vertx vertx, WebSocketClient client, WebSocket socket) {
    this.vertx = vertx;
    this.client = client;
    this.socket = socket;
    socket.textMessageHandler(received::offer);
  }

  /** Send {@code {"subscribe": <groups>}}, where {@code groups} is a JSON array. */
  public void subscribe(String groups) throws Exception {
    send("{\"subscribe\":" + groups + "}");
  }

  /** Send any text, readable or not. */
  public void send(String text) throws Exception {
    socket.writeTextMessage(text).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
  }

  /** The next frame the server pushed, or null if none arrived in time. */
  public String next(Duration timeout) throws InterruptedException {
    return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** The next frame as JSON, or null if none arrived in time. */
  public JsonNode nextJson(Duration timeout) throws Exception {
    String frame = next(timeout);
    return frame == null ? null : JSON.readTree(frame);
  }

  public boolean isOpen() {
    return !socket.isClosed();
  }

  @Override
  public void close() {
    try {
      socket.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception alreadyGone) {
      // an already-dead socket is closed
    }
    client.close();
    vertx.close();
  }
}
