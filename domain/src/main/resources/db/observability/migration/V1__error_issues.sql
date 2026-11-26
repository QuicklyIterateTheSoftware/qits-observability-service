-- Error issues (epic qits-2538): one row per fingerprint, and its hourly occurrence counts.
--
-- The issue's id IS its fingerprint (ErrorFingerprinter: 32 lower-case hex characters), the value
-- the receiver stamps on every stored ERROR log as qits.error.fingerprint. An issue is never expired
-- (owner ruling 2026-11-22): only error_issue_hour rows are purged, after
-- qits.observability.error-issues.hour-retention, so occurrences, first_seen and last_seen are kept
-- forever.
CREATE TABLE error_issue (
  fingerprint        char(32)    PRIMARY KEY,
  state              varchar(16) NOT NULL,          -- OPEN | DISMISSED
  service_name       text        NOT NULL,
  exception_type     text,                          -- null when none
  location           text,                          -- null when none
  message            text        NOT NULL,          -- normalised body, <= 200 chars: the issue's title
  sample_message     text,                          -- raw body of the newest occurrence, cut at 1000 chars
  sample_stacktrace  text,                          -- newest exception.stacktrace, cut at 16384 chars
  sample_trace_id    varchar(32),                   -- newest non-empty traceId
  first_seen         timestamptz NOT NULL,
  last_seen          timestamptz NOT NULL,
  first_version      text,                          -- resource service.version
  last_version       text,
  occurrences        bigint      NOT NULL,
  regressions        integer     NOT NULL DEFAULT 0,
  regressed_at       timestamptz,
  dismissed_at       timestamptz,
  dismissed_by       text,                          -- SecurityIdentity principal name
  row_version        bigint      NOT NULL DEFAULT 0
);

CREATE INDEX error_issue_state_last_seen ON error_issue (state, last_seen DESC);

CREATE TABLE error_issue_hour (
  fingerprint char(32)    NOT NULL REFERENCES error_issue ON DELETE CASCADE,
  hour        timestamptz NOT NULL,                 -- truncated to the hour, UTC
  count       bigint      NOT NULL,
  PRIMARY KEY (fingerprint, hour)
);
