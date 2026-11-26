package eu.wohlben.qits.telemetry.error;

/** Domain error mapped to HTTP 503 by the web layer. */
public class ServiceUnavailableException extends DomainException {

  public ServiceUnavailableException(String message) {
    super(503, message);
  }
}
