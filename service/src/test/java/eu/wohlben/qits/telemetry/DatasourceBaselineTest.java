package eu.wohlben.qits.telemetry;

import eu.wohlben.qits.archrules.DatasourceBaselineRules;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The {@code observability} datasource carries the platform's resilience baseline — the patient
 * driver, validation at borrow, and a 15s acquisition timeout. {@code @QuarkusTest} so it judges the
 * config the application actually runs on, on the default profile, at no extra start.
 */
@QuarkusTest
class DatasourceBaselineTest {

  @Test
  void everyPostgresDatasourceCarriesTheBaseline() {
    DatasourceBaselineRules.assertBaseline();
  }
}
