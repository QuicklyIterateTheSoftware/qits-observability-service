package eu.wohlben.qits.telemetry.testdb;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * The platform's generic resource triple for a launched artifact that has no profile of its own
 * ({@code OtelReceiverIT}, {@code PackagedSurfaceIT}): a packaged process runs the prod profile,
 * whose datasource reads {@code QITS_RESOURCE_DB_*} and refuses to boot without them. The database
 * is one of their own on the suite's one embedded postgres. Nothing is stopped: the instance lives
 * as long as the test JVM.
 */
public class EmbeddedPgResource implements QuarkusTestResourceLifecycleManager {

  private static final String DATABASE = "observability_packaged_it";

  @Override
  public Map<String, String> start() {
    return Map.of(
        "QITS_RESOURCE_DB_URL", EmbeddedPg.url(DATABASE),
        "QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER,
        "QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
  }

  @Override
  public void stop() {}
}
