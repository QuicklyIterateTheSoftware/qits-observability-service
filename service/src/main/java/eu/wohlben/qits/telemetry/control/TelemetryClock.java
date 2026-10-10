package eu.wohlben.qits.telemetry.control;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

/**
 * The time the query API calls "now": the end of a {@code sinceMinutes} window and a records
 * search's default {@code until}. The wall clock; a contract test replaces it, so a provider state
 * can fix "now" next to its fixed record times.
 */
@ApplicationScoped
public class TelemetryClock {

  public Instant now() {
    return Instant.now();
  }

  public long millis() {
    return now().toEpochMilli();
  }
}
