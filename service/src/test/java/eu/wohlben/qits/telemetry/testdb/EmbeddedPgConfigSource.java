package eu.wohlben.qits.telemetry.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module as the six keys
 * a deployment fills for the TWO datasources this application opens: {@code observability} (the
 * error issues, from {@code QITS_RESOURCE_DB_*}) and {@code eventstream} (the event bus jar's
 * outbox, from {@code QITS_RESOURCE_EVENTSTREAM_*}) — {@code jdbc.url}, {@code username}, {@code
 * password} each.
 *
 * <p>The second is here because joining qits-eventstream (qits-2684) made this deployable one that
 * opens a second datasource: {@code qits.eventstream.enabled=false} under {@code %test} stops
 * publishing, and stops none of the connecting and migrating Quarkus does at boot. Its database has
 * a name of its own, never the library suite's {@code eventstream_test}.
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

  /** The event bus's outbox, on a database of its own. */
  private static final String EVENTSTREAM_DATABASE = "observability_eventstream";

  private static final String PREFIX = "quarkus.datasource.observability.";

  private static final String EVENTSTREAM_PREFIX = "quarkus.datasource.eventstream.";

  private final Map<String, String> values =
      Map.of(
          PREFIX + "jdbc.url", EmbeddedPg.url(DATABASE),
          PREFIX + "username", EmbeddedPg.USER,
          PREFIX + "password", EmbeddedPg.PASSWORD,
          EVENTSTREAM_PREFIX + "jdbc.url", EmbeddedPg.url(EVENTSTREAM_DATABASE),
          EVENTSTREAM_PREFIX + "username", EmbeddedPg.USER,
          EVENTSTREAM_PREFIX + "password", EmbeddedPg.PASSWORD);

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
