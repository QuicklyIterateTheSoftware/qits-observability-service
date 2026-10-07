package eu.wohlben.qits.telemetry.api;

import eu.wohlben.qits.telemetry.control.TelemetryLiveFeed;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

/**
 * The live stream: what this receiver takes in (logs, spans, metrics), filtered per connection and
 * pushed as it arrives. The wire protocol is README "The live stream"; the {@code qits observe}
 * command is its client.
 *
 * <p>A connection starts subscribed to nothing. Each {@code {"subscribe": [...]}} frame replaces
 * its filter whole. The server then pushes one frame per matching record, plus {@code
 * {"dropped": N}} and {@code {"error": "…"}} notices. Live only: no replay.
 *
 * <p>This class owns the socket and nothing else. {@link TelemetryLiveFeed} owns the table of who
 * wants what, and the fan-out. Same split as qits-events' {@code EventStreamSocket}.
 *
 * <p><b>The path spells {@code /observability} itself.</b> A {@code @WebSocket} path does not follow
 * {@code quarkus.rest.path}. {@code quarkus.quinoa.ignored-path-prefixes=/observability} covers it,
 * so a plain GET here never reaches the SPA fallback.
 *
 * <p>The role is checked on the upgrade: a person's session through the edge, a person's bearer
 * token, or in-network forward-auth headers, all ending in {@code qits:admin}. A commissioned
 * agent's {@code qits:agent} opens it too: the stream only reads.
 */
@WebSocket(path = "/observability/stream")
@RolesAllowed({"qits:admin", "qits:admin-agent", "qits:agent"})
public class TelemetryStreamSocket {

  @Inject TelemetryLiveFeed feed;

  @OnOpen
  public void onOpen(WebSocketConnection connection) {
    feed.opened(new WebSocketSink(connection));
  }

  @OnTextMessage
  public void onMessage(String message, WebSocketConnection connection) {
    feed.subscribe(connection.id(), message);
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    feed.closed(connection.id());
  }
}
