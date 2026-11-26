package eu.wohlben.qits.telemetry.issues.entity;

import eu.wohlben.qits.telemetry.issues.ErrorIssueHour;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * A row of {@code error_issue_hour} (V1): one issue's occurrences in one hour. Served by {@code
 * getErrorIssue} as its {@code hourly} list. Written only by the store's upsert (a native {@code ON
 * CONFLICT} add), so this mapping is the read side; rows older than the hour retention are purged.
 * The foreign key to {@code error_issue} is the schema's, deliberately not a JPA relation: a read
 * of the hours never needs the issue loaded.
 */
@Entity
@Table(name = "error_issue_hour")
@IdClass(ErrorIssueHourEntity.Key.class)
public class ErrorIssueHourEntity {

  @Id
  @Column(name = "fingerprint", columnDefinition = "char(32)", length = 32)
  public String fingerprint;

  @Id
  @Column(name = "hour")
  public Instant hour;

  @Column(name = "count", nullable = false)
  public long count;

  /** The hour as the domain reads it. */
  public ErrorIssueHour toHour() {
    return new ErrorIssueHour(hour, count);
  }

  /** The composite key: one row per fingerprint and hour. */
  public static class Key implements Serializable {
    public String fingerprint;
    public Instant hour;

    public Key() {}

    public Key(String fingerprint, Instant hour) {
      this.fingerprint = fingerprint;
      this.hour = hour;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(fingerprint, key.fingerprint)
          && Objects.equals(hour, key.hour);
    }

    @Override
    public int hashCode() {
      return Objects.hash(fingerprint, hour);
    }
  }
}
