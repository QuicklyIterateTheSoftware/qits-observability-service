package eu.wohlben.qits.telemetry.api;

import eu.wohlben.qits.http.RawHttpClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The upstream half of the OTLP tee: when qits itself runs as a managed service, the supervising
 * qits injects {@code OTEL_EXPORTER_OTLP_ENDPOINT} (surfaced by MicroProfile Config as {@code
 * otel.exporter.otlp.endpoint}) and everything the local receiver ingests — the child's own SPA
 * plus anything else exporting to it — is forwarded byte-verbatim to the parent as well, so both
 * telemetry views stay live. Standalone (no endpoint configured) this is a no-op and the receiver
 * behaves exactly as before.
 *
 * <p>Fire-and-forget: the forward runs async, never blocks the ingest request thread, and upstream
 * failures are ignored beyond a debug log — telemetry is best-effort and the local store must never
 * fail because the parent is down. Bodies are relayed exactly as received (still gzipped if the
 * exporter compressed), with Content-Type and Content-Encoding passed through.
 *
 * <p><b>Deliberately unwrapped.</b> {@link eu.wohlben.qits.http.QitsHttpClients#wrap} opens a CLIENT
 * span per request — exactly the shape of the thing this class forwards, an OTLP export — so wrapping
 * the tee would make every export trace its own exporter, recursively.
 */
@ApplicationScoped
@RawHttpClient(
    reason =
        "an OTLP tee to a parent observability instance: wrapping it would open a CLIENT span for"
            + " every export, i.e. trace its own exporter")
public class OtelForwarder {

  private static final Logger LOG = Logger.getLogger(OtelForwarder.class);

  @ConfigProperty(name = "otel.exporter.otlp.endpoint")
  Optional<String> endpoint;

  /**
   * A bean field built in {@link #openClient()}, not a {@code static final} one. An {@link
   * HttpClient} is live machinery — a selector thread and a connection pool — and Quarkus
   * initializes application classes at <em>build</em> time for the native image, so a static
   * initializer here put a running client into the image heap and native-image refused it outright:
   * "An object of type 'jdk.internal.net.http.HttpClientFacade' was found in the image heap". The
   * alternatives were to force the class to run-time initialization, or to build-time-initialize the
   * JDK's HTTP internals and ship a client that was constructed on the build machine; neither is
   * worth it for state that is per-application anyway, which is exactly what a bean field is.
   */
  private HttpClient client;

  @PostConstruct
  void openClient() {
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  public void forward(String signal, String contentType, String contentEncoding, byte[] body) {
    if (endpoint.isEmpty()) {
      return;
    }
    try {
      String base = endpoint.get().replaceAll("/+$", "");
      HttpRequest.Builder request =
          HttpRequest.newBuilder(URI.create(base + "/v1/" + signal))
              .timeout(Duration.ofSeconds(10))
              .header(
                  "Content-Type", contentType != null ? contentType : OtelReceiverResource.PROTOBUF)
              .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
      if (contentEncoding != null) {
        request.header("Content-Encoding", contentEncoding);
      }
      client
          .sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
          .whenComplete(
              (response, failure) -> {
                if (failure != null) {
                  LOG.debugf("OTLP forward of %s to %s failed: %s", signal, base, failure);
                } else if (response.statusCode() >= 400) {
                  LOG.debugf(
                      "OTLP forward of %s to %s rejected upstream: %d",
                      signal, base, response.statusCode());
                }
              });
    } catch (RuntimeException e) {
      LOG.debugf("OTLP forward of %s skipped: %s", signal, e.toString());
    }
  }
}
