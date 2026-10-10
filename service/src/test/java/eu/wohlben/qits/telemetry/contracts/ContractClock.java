package eu.wohlben.qits.telemetry.contracts;

import eu.wohlben.qits.telemetry.control.TelemetryClock;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

/**
 * The test suite's {@link TelemetryClock}: the wall clock until a provider state fixes "now", so a
 * {@code sinceMinutes} window or a search's default {@code until} lands on the state's fixed
 * record times. The contract tests {@link #release} it after each run, so no other test sees a
 * fixed clock.
 */
@Mock
@ApplicationScoped
public class ContractClock extends TelemetryClock {

  private volatile Instant fixed;

  @Override
  public Instant now() {
    Instant at = fixed;
    return at == null ? super.now() : at;
  }

  /** From now on, "now" is {@code at}. */
  public void fixAt(Instant at) {
    fixed = at;
  }

  /** Back to the wall clock. */
  public void release() {
    fixed = null;
  }
}
