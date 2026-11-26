package eu.wohlben.qits.telemetry.issues.persistence;

import eu.wohlben.qits.telemetry.issues.ErrorIssue;
import eu.wohlben.qits.telemetry.issues.ErrorIssueDelta;
import eu.wohlben.qits.telemetry.issues.ErrorIssueHour;
import eu.wohlben.qits.telemetry.issues.ErrorIssuePage;
import eu.wohlben.qits.telemetry.issues.ErrorIssueState;
import eu.wohlben.qits.telemetry.issues.ErrorIssues;
import eu.wohlben.qits.telemetry.issues.FlushOutcome;
import eu.wohlben.qits.telemetry.issues.entity.ErrorIssueEntity;
import eu.wohlben.qits.telemetry.issues.entity.ErrorIssueHourEntity;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The error issues in the {@code observability} database (epic qits-2538): the two tables of
 * {@code V1__error_issues.sql}, read and written through the {@code observability} persistence unit.
 *
 * <p><b>The persistence unit may be inactive.</b> {@code %dev} switches the datasource off, so a
 * {@code quarkus:dev} boots and ingests with no postgres at all; {@link #active()} says which case
 * this process is in, and every other method requires it. The entity manager is therefore an
 * {@link InjectableInstance}: injecting it directly would fail the boot of a process whose unit is
 * inactive.
 *
 * <p>Every method is {@code @Transactional} and joins a caller's transaction: {@link #apply} is
 * called inside the aggregator's flush transaction, so whatever else that transaction does (the
 * events qits-2684 publishes once it commits) succeeds or fails with the issue rows.
 */
@ApplicationScoped
public class ErrorIssueStore {

  private static final String UPSERT_HOUR =
      "INSERT INTO error_issue_hour (fingerprint, hour, count) VALUES (?1, ?2, ?3)"
          + " ON CONFLICT (fingerprint, hour) DO UPDATE SET count = error_issue_hour.count"
          + " + EXCLUDED.count";

  @Inject
  @PersistenceUnit("observability")
  InjectableInstance<EntityManager> entityManager;

  /**
   * An instant as a {@code timestamptz} keeps it: to the microsecond. What a write answers must be
   * what a later read of the same row answers.
   */
  private static Instant stored(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MICROS);
  }

  /** Whether this process has an error-issue database ({@code false} under {@code %dev}). */
  public boolean active() {
    return entityManager.getHandle().getBean().isActive();
  }

  private EntityManager em() {
    return entityManager.getActive();
  }

  /**
   * Folds one flush window into the tables: a new fingerprint is inserted OPEN, an existing one is
   * merged by {@link ErrorIssues#merge} under a row lock, and every hour count is added by upsert.
   * Fingerprints are written in sorted order, so two writers can never lock in opposite orders.
   */
  @Transactional
  public FlushOutcome apply(List<ErrorIssueDelta> deltas, Instant now) {
    EntityManager em = em();
    Instant at = stored(now);
    List<ErrorIssueDelta> ordered =
        deltas.stream().sorted(Comparator.comparing(ErrorIssueDelta::fingerprint)).toList();
    List<ErrorIssue> opened = new ArrayList<>();
    List<FlushOutcome.Regression> regressed = new ArrayList<>();
    for (ErrorIssueDelta delta : ordered) {
      ErrorIssueEntity row =
          em.find(ErrorIssueEntity.class, delta.fingerprint(), LockModeType.PESSIMISTIC_WRITE);
      if (row == null) {
        ErrorIssue issue = ErrorIssues.open(delta);
        row = new ErrorIssueEntity();
        row.assign(issue);
        em.persist(row);
        opened.add(issue);
      } else {
        ErrorIssues.Merged merged = ErrorIssues.merge(row.toIssue(), delta, at);
        row.assign(merged.issue());
        if (merged.reopened()) {
          regressed.add(
              new FlushOutcome.Regression(
                  merged.issue(), delta.firstSeen(), delta.firstVersion()));
        }
      }
    }
    // The hour rows reference the issue rows, so the inserts above reach the database first.
    em.flush();
    for (ErrorIssueDelta delta : ordered) {
      for (Map.Entry<Instant, Long> hour : delta.hourly().entrySet()) {
        em.createNativeQuery(UPSERT_HOUR)
            .setParameter(1, delta.fingerprint())
            .setParameter(2, hour.getKey())
            .setParameter(3, hour.getValue())
            .executeUpdate();
      }
    }
    return new FlushOutcome(opened, regressed);
  }

  /**
   * Issues newest {@code lastSeen} first (the fingerprint breaks ties), at most {@code limit} of
   * them. A null {@code state} is every state; a null {@code service} every service.
   */
  @Transactional
  public ErrorIssuePage list(ErrorIssueState state, String service, int limit) {
    EntityManager em = em();
    StringBuilder where = new StringBuilder(" where 1 = 1");
    if (state != null) {
      where.append(" and i.state = :state");
    }
    if (service != null) {
      where.append(" and i.serviceName = :service");
    }
    TypedQuery<ErrorIssueEntity> rows =
        em.createQuery(
            "select i from ErrorIssueEntity i"
                + where
                + " order by i.lastSeen desc, i.fingerprint asc",
            ErrorIssueEntity.class);
    TypedQuery<Long> count =
        em.createQuery("select count(i) from ErrorIssueEntity i" + where, Long.class);
    if (state != null) {
      rows.setParameter("state", state);
      count.setParameter("state", state);
    }
    if (service != null) {
      rows.setParameter("service", service);
      count.setParameter("service", service);
    }
    List<ErrorIssue> issues =
        rows.setMaxResults(limit).getResultList().stream().map(ErrorIssueEntity::toIssue).toList();
    long total = count.getSingleResult();
    return new ErrorIssuePage(issues, total, total > issues.size());
  }

  /** The issue with this fingerprint, if there is one. */
  @Transactional
  public Optional<ErrorIssue> find(String fingerprint) {
    return Optional.ofNullable(em().find(ErrorIssueEntity.class, fingerprint))
        .map(ErrorIssueEntity::toIssue);
  }

  /** The issue's hour rows from {@code since} on (inclusive), oldest first. */
  @Transactional
  public List<ErrorIssueHour> hours(String fingerprint, Instant since) {
    return em()
        .createQuery(
            "select h from ErrorIssueHourEntity h where h.fingerprint = :fingerprint"
                + " and h.hour >= :since and h.count > 0 order by h.hour asc",
            ErrorIssueHourEntity.class)
        .setParameter("fingerprint", fingerprint)
        .setParameter("since", since)
        .getResultList()
        .stream()
        .map(ErrorIssueHourEntity::toHour)
        .toList();
  }

  /**
   * Dismisses the issue ({@link ErrorIssues#dismiss}: idempotent). Empty when there is no such
   * issue.
   */
  @Transactional
  public Optional<ErrorIssue> dismiss(String fingerprint, String by, Instant now) {
    ErrorIssueEntity row =
        em().find(ErrorIssueEntity.class, fingerprint, LockModeType.PESSIMISTIC_WRITE);
    if (row == null) {
      return Optional.empty();
    }
    ErrorIssue dismissed = ErrorIssues.dismiss(row.toIssue(), by, stored(now));
    row.assign(dismissed);
    return Optional.of(dismissed);
  }

  /**
   * Deletes every hour row older than {@code cutoff} and answers how many went. The issue rows,
   * and their totals and first/last seen, are never purged.
   */
  @Transactional
  public int purgeHoursBefore(Instant cutoff) {
    return em()
        .createQuery("delete from ErrorIssueHourEntity h where h.hour < :cutoff")
        .setParameter("cutoff", cutoff)
        .executeUpdate();
  }
}
