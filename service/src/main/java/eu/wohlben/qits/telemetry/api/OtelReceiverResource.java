package eu.wohlben.qits.telemetry.api;

import com.google.protobuf.InvalidProtocolBufferException;
import eu.wohlben.qits.telemetry.error.BadRequestException;
import eu.wohlben.qits.telemetry.error.PayloadTooLargeException;
import eu.wohlben.qits.telemetry.control.TelemetryDecoder;
import eu.wohlben.qits.telemetry.control.TelemetryLiveFeed;
import eu.wohlben.qits.telemetry.control.TelemetryStore;
import eu.wohlben.qits.telemetry.dto.MetricPoint;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.dto.StoredSpan;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The in-process OTLP/HTTP receiver: the three standard export endpoints under {@code
 * /observability/api/otel/v1/*} — SDKs pointed at {@code
 * OTEL_EXPORTER_OTLP_ENDPOINT=http://<host>:<port>/observability/api/otel} append {@code
 * /v1/<signal>} themselves. Protobuf-only by design: qits pins every launched exporter to {@code
 * http/protobuf} via the injected env vars, so OTLP/JSON (which deviates from proto3 JSON) and gRPC
 * are deliberately not implemented.
 *
 * <p>Hidden from the OpenAPI document: these are wire-protocol endpoints for OTel SDKs, not part of
 * the JSON API the generated Angular client consumes.
 *
 * <p>{@link #logs} nevertheless carries the operationId {@value #EXPORT_LOGS}, because it has a
 * consumer that pacts it (ticket qits-1163): qits-runner-toolkit's {@code Telemetry} posts OTLP log
 * batches here for the runner daemons, and its pact names the call by that id. The golden-master
 * recording keys operations by operationId, not by the served document, so a hidden operation is
 * recordable once it has one; hiding it keeps it out of the public document, as before. The id is
 * a contract: renaming it breaks the toolkit's pact.
 *
 * <p>When qits itself runs as a managed service, every export is additionally teed byte-verbatim to
 * the parent qits via {@link OtelForwarder} — before decoding, so the forward carries the exact
 * wire bytes (still gzipped if the exporter compressed them).
 *
 * <p><b>Ingest is unauthenticated, and that is the doctrine, not an omission</b> (AGENTS.md: "none
 * of this reaches ingest"). Its callers are OTel SDK exporters — the one inside every qits service
 * and the ones inside workspace containers — which carry no identity at all: the exporter sends
 * what {@code quarkus.otel.exporter.otlp.endpoint} names and nothing else, and qits-auth-core's
 * forward-auth or a bearer is not something an SDK can be handed. {@code @PermitAll} is written
 * out rather than left implicit because this class once carried {@code
 * @RolesAllowed("qits:system")} (the 2026-08-15 "protect observability APIs" sweep, which meant the
 * UI routes and caught this one too): every export answered 401, the SDK retried silently, nothing
 * logged, and the store stayed empty for five days on wohlben.eu — measured 2026-08-20, an
 * anonymous POST answered 401 and the same body under {@code X-Qits-Roles: qits:system} answered
 * 200. The boundary here is the network: the edge never routes a session-less request in, and on
 * qits-net the per-source caps bound what any sender can cost. {@code OtelIngestAnonymousTest} pins
 * it.
 */
@Path("otel/v1")
@Consumes(OtelReceiverResource.PROTOBUF)
@Produces(OtelReceiverResource.PROTOBUF)
@jakarta.annotation.security.PermitAll
public class OtelReceiverResource {

  static final String PROTOBUF = "application/x-protobuf";

  /** The logs receiver's operationId, which qits-runner-toolkit's pact names. */
  public static final String EXPORT_LOGS = "exportLogs";

  /**
   * The request ceiling this process already enforces on the wire, read rather than restated: a
   * second copy of the number would be free to drift from the one the HTTP layer uses, and the two
   * have to agree for a gzipped body and a plain one to be refused at the same size.
   */
  @ConfigProperty(name = "quarkus.http.limits.max-body-size")
  MemorySize maxBodySize;

  @Inject TelemetryDecoder decoder;

  @Inject TelemetryStore store;

  @Inject OtelForwarder forwarder;

  /**
   * The live stream. Handed each decoded batch right after the store took it; it returns at once and
   * never throws, so ingest does not wait on a socket.
   */
  @Inject TelemetryLiveFeed liveFeed;

  @POST
  @Path("/traces")
  @Operation(hidden = true)
  public byte[] traces(
      @HeaderParam(HttpHeaders.CONTENT_TYPE) String contentType,
      @HeaderParam("Content-Encoding") String contentEncoding,
      byte[] body) {
    forwarder.forward("traces", contentType, contentEncoding, body);
    ExportTraceServiceRequest request = parse(body, ExportTraceServiceRequest::parseFrom);
    List<StoredSpan> decoded = decoder.decodeSpans(request, System.currentTimeMillis());
    store.addSpans(decoded);
    liveFeed.publishSpans(decoded);
    return ExportTraceServiceResponse.getDefaultInstance().toByteArray();
  }

  @POST
  @Path("/logs")
  @Operation(hidden = true, operationId = EXPORT_LOGS)
  public byte[] logs(
      @HeaderParam(HttpHeaders.CONTENT_TYPE) String contentType,
      @HeaderParam("Content-Encoding") String contentEncoding,
      byte[] body) {
    forwarder.forward("logs", contentType, contentEncoding, body);
    ExportLogsServiceRequest request = parse(body, ExportLogsServiceRequest::parseFrom);
    List<StoredLog> decoded = decoder.decodeLogs(request, System.currentTimeMillis());
    store.addLogs(decoded);
    liveFeed.publishLogs(decoded);
    return ExportLogsServiceResponse.getDefaultInstance().toByteArray();
  }

  @POST
  @Path("/metrics")
  @Operation(hidden = true)
  public byte[] metrics(
      @HeaderParam(HttpHeaders.CONTENT_TYPE) String contentType,
      @HeaderParam("Content-Encoding") String contentEncoding,
      byte[] body) {
    forwarder.forward("metrics", contentType, contentEncoding, body);
    ExportMetricsServiceRequest request = parse(body, ExportMetricsServiceRequest::parseFrom);
    List<MetricPoint> decoded = decoder.decodeMetrics(request, System.currentTimeMillis());
    store.addMetrics(decoded);
    liveFeed.publishMetrics(decoded);
    return ExportMetricsServiceResponse.getDefaultInstance().toByteArray();
  }

  @FunctionalInterface
  private interface ProtoParser<T> {
    T parse(byte[] bytes) throws InvalidProtocolBufferException;
  }

  private <T> T parse(byte[] body, ProtoParser<T> parser) {
    try {
      return parser.parse(gunzipIfNeeded(body == null ? new byte[0] : body));
    } catch (InvalidProtocolBufferException e) {
      throw new BadRequestException("Malformed OTLP protobuf payload");
    }
  }

  /**
   * Decompresses by the gzip magic bytes instead of trusting {@code Content-Encoding} — correct
   * whether or not the server already decompressed, and unambiguous: a valid {@code
   * Export*ServiceRequest} starts with field tag {@code 0x0a}, never {@code 0x1f 0x8b}.
   *
   * <p><strong>Bounded by the same ceiling the wire body has.</strong> {@code
   * quarkus.http.limits.max-body-size} caps what arrives on the socket, and a compressed body is
   * under it by definition — gzip of a repeated byte runs past 1000:1, so a few kilobytes that pass
   * the HTTP check can inflate into gigabytes of heap. Inflating is therefore a counted, streaming
   * read that stops one byte past the ceiling and answers 413, which is the same answer and the same
   * number the HTTP layer would have given had the sender not compressed. Nothing is materialised
   * before the check: {@code readAllBytes()} here would be the bomb going off.
   */
  private byte[] gunzipIfNeeded(byte[] body) {
    if (body.length < 2 || body[0] != 0x1f || body[1] != (byte) 0x8b) {
      return body;
    }
    long ceiling = maxBodySize.asLongValue();
    try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
      ByteArrayOutputStream inflated = new ByteArrayOutputStream(body.length * 2);
      byte[] chunk = new byte[8192];
      long total = 0;
      int read;
      while ((read = gz.read(chunk)) != -1) {
        total += read;
        if (total > ceiling) {
          throw new PayloadTooLargeException(
              "Decompressed OTLP payload exceeds the " + ceiling + " byte limit");
        }
        inflated.write(chunk, 0, read);
      }
      return inflated.toByteArray();
    } catch (IOException e) {
      throw new BadRequestException("Malformed gzip payload");
    }
  }
}
