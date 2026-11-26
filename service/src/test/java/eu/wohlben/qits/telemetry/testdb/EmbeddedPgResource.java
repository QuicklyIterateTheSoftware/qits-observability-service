package eu.wohlben.qits.telemetry.testdb;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * The platform's generic resource triple for a launched artifact that has no profile of its own
 * ({@code OtelReceiverIT}, {@code PackagedSurfaceIT}): a packaged process runs the prod profile,
 * whose datasources read {@code QITS_RESOURCE_DB_*} and {@code QITS_RESOURCE_EVENTSTREAM_*} (the
 * event bus's outbox, qits-2684) and refuse to boot without them. Each database is one of their own
 * on the suite's one embedded postgres. The bus is dark: the prod profile ships it enabled, and
 * nothing here answers for qits-events. Nothing is stopped: the instance lives as long as the test
 * JVM.
 */
public class EmbeddedPgResource implements QuarkusTestResourceLifecycleManager {

  private static final String DATABASE = "observability_packaged_it";

  private static final String EVENTSTREAM_DATABASE = "observability_packaged_it_eventstream";

  @Override
  public Map<String, String> start() {
    return Map.of(
        "QITS_RESOURCE_DB_URL", EmbeddedPg.url(DATABASE),
        "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
        "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD,
        "QITS_RESOURCE_EVENTSTREAM_URL", EmbeddedPg.url(EVENTSTREAM_DATABASE),
        "QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER,
        "QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD,
        "qits.eventstream.enabled", "false");
  }

  @Override
  public void stop() {}
}
