package eu.wohlben.qits.telemetry.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module as the three
 * {@code quarkus.datasource.observability.*} keys a deployment fills from {@code
 * QITS_RESOURCE_DB_*}: {@code jdbc.url}, {@code username}, {@code password}.
 *
 * <p>A config source rather than three lines in {@code src/test/resources/application.properties}
 * because the port is chosen at run time. The shipped keys are {@code %prod} ones, so nothing has
 * to be overridden; the ordinal sits above application.properties all the same. Registered through
 * {@code META-INF/services}, which is how a config source joins a Quarkus application without being
 * a bean. Copied from qits-mirror.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /** This service's database on the embedded instance. */
  private static final String DATABASE = "observability";

  private static final String PREFIX = "quarkus.datasource.observability.";

  private final Map<String, String> values =
      Map.of(
          PREFIX + "jdbc.url", EmbeddedPg.url(DATABASE),
          PREFIX + "username", EmbeddedPg.USER,
          PREFIX + "password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
