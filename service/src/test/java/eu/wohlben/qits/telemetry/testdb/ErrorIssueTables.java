package eu.wohlben.qits.telemetry.testdb;

import io.quarkus.arc.InjectableInstance;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * The suite's direct line to the two error-issue tables, past the store: empties them before a
 * test or a provider state, and reads a row back where "it is in postgres, not in memory" is the
 * point. Test code only — production has no door that deletes an issue.
 */
@ApplicationScoped
public class ErrorIssueTables {

  /** An instance, not the manager: a profile with the datasource switched off must still boot. */
  @Inject
  @PersistenceUnit("observability")
  InjectableInstance<EntityManager> entityManager;

  /** Deletes every issue and, by cascade, every hour row. */
  @Transactional
  public void clear() {
    entityManager.getActive().createNativeQuery("DELETE FROM error_issue").executeUpdate();
  }

  /** {@code error_issue.occurrences} for the fingerprint, read by SQL; null when there is no row. */
  @Transactional
  public Long occurrences(String fingerprint) {
    List<?> rows =
        entityManager
            .getActive()
            .createNativeQuery("SELECT occurrences FROM error_issue WHERE fingerprint = ?1")
            .setParameter(1, fingerprint)
            .getResultList();
    return rows.isEmpty() ? null : ((Number) rows.getFirst()).longValue();
  }

  /** How many {@code error_issue_hour} rows the fingerprint has, read by SQL. */
  @Transactional
  public long hourRows(String fingerprint) {
    return ((Number)
            entityManager
                .getActive()
                .createNativeQuery("SELECT count(*) FROM error_issue_hour WHERE fingerprint = ?1")
                .setParameter(1, fingerprint)
                .getSingleResult())
        .longValue();
  }
}
