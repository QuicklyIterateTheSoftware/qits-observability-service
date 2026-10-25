package eu.wohlben.qits.telemetry.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Verifies every consumer's pact against the running qits-observability</b>, the way
 * qits-projects-service and qits-edge-service do.
 *
 * <p>The pacts come off the test classpath: each consumer publishes its pact as a jar holding
 * {@code pacts/<consumer>_qits-observability-service.json} (repository names on both sides), this
 * repo pins that jar as a test dependency, and qits-maintenance bumps the pin when the consumer
 * releases a changed pact. {@link ClasspathPactLoader} finds them all.
 *
 * <p><b>Three consumer pacts are pinned.</b> qits-projects-desk-runner-daemon's call through
 * qits-runner-toolkit's {@code Telemetry} (ticket qits-1163), {@code
 * pacts/qits-projects-desk-runner-daemon_qits-runner-javalib_qits-observability-service.json} — one
 * interaction, "the log export interval: exportLogs", state "an empty telemetry buffer";
 * qits-system-runner-daemon's call through the same {@code Telemetry} (ticket qits-1163), {@code
 * pacts/qits-system-runner-daemon_qits-runner-javalib_qits-observability-service.json} — the same
 * one interaction and state; and qits-platform-access-cli's {@code qits observe}
 * subcommands (ticket qits-1149), {@code pacts/qits-platform-access-cli_qits-observability-service.json}
 * — errors, metrics, query and sources. So this
 * class carries no {@code @IgnoreNoPactsToVerify} and {@link ClasspathPactLoader#REQUIRED} is true:
 * an empty classpath is now an error, not a silent pass.
 *
 * <p>Each interaction runs against this {@code @QuarkusTest} application over real HTTP, as the
 * {@code %test} dev user. Every {@code @State} method delegates to {@link ProviderStates}; {@link
 * #target} fails an unknown state, and an interaction without {@code comments.references.qits-call}
 * or {@code qits-trigger}.
 *
 * <p><b>A pinned pact that is wrong is named in {@link #KNOWN_WRONG}, by its jar's version</b>, and
 * its interaction is reported as skipped with the reason.
 */
@QuarkusTest
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
class ConsumerPactVerificationTest {

  /** The provider's name in a pact: the repository name, not the application name. */
  static final String PROVIDER = "qits-observability-service";

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  /** A pinned pact interaction this provider cannot honour, and why: the consumer must fix it. */
  record KnownWrong(String consumer, String version, String description, String reason) {

    boolean names(Pact pact, Interaction interaction) {
      return consumer.equals(pact.getConsumer().getName())
          && description.equals(interaction.getDescription())
          && String.valueOf(pact.getSource()).contains("/" + version + "/");
    }
  }

  /**
   * The pinned interactions that are wrong, each by the version of the consumer's jar: the entry
   * is for that version alone, so the bump carrying the consumer's fix verifies the interaction
   * for real, and a bump that does not fix it goes red here. Each is skipped with its reason —
   * never answered by bending the provider to it (the pattern of qits-maintenance-service's).
   * None at present: qits-platform-access-cli 2026.1019.65035's metrics and query rows, which
   * bound exponent-form doubles with a matcher that rejects them, were fixed in 2026.1019.92700,
   * the version pinned.
   */
  static final List<KnownWrong> KNOWN_WRONG = List.of();

  @Inject ProviderStates states;
  @Inject ContractClock clock;

  /** A state fixes the clock; no other test may see it fixed. */
  @AfterEach
  void releaseClock() {
    clock.release();
  }

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!states.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-observability does not answer for — it answers for "
                + states.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    for (KnownWrong wrong : KNOWN_WRONG) {
      if (wrong.names(pact, interaction)) {
        Assumptions.abort(
            consumer + " " + wrong.version() + " '" + interaction.getDescription() + "': "
                + wrong.reason());
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    context.verifyInteraction();
  }

  // --- the states: each one line into the registry -------------------------------------------

  @State(ProviderStates.TELEMETRY_FROM_ONE_SERVICE)
  Map<String, String> telemetryFromOneService() {
    return states.params(ProviderStates.TELEMETRY_FROM_ONE_SERVICE);
  }

  @State(ProviderStates.TELEMETRY_RECORDS_OF_EVERY_KIND)
  Map<String, String> telemetryRecordsOfEveryKind() {
    return states.params(ProviderStates.TELEMETRY_RECORDS_OF_EVERY_KIND);
  }

  @State(ProviderStates.AN_EMPTY_TELEMETRY_BUFFER)
  Map<String, String> anEmptyTelemetryBuffer() {
    return states.params(ProviderStates.AN_EMPTY_TELEMETRY_BUFFER);
  }
}
