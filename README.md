# qits-observability

The **telemetry** context of qits: an in-process OTLP/HTTP receiver, a bounded in-memory buffer of
what it receives, and a query surface over that buffer (REST, for the UI and the qits CLI).
Plus the managed-app relay that goes with it, the upstream OTLP tee.

Its **wire** surface lives under **`/observability`** — the edge path-routes verbatim by prefix on
every vhost, so the segment is part of the path this process itself serves, on `qits-net` as much as
through the edge. There is no unprefixed form.

The **client** is served at `/`. This service has a host of its own,
`observability.<env>.<domain>`, and the SPA owns every path on it the wire routes do not claim.

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker
    mvn verify -Dnative   # and compiles to a GraalVM binary, still no docker (see .sdkmanrc)

The qits services export their own spans, logs and metrics here, and each lands in a bucket named
for its `service.name`. People and agents read them through the `qits observe` CLI, which calls the
REST surface and the live stream.

**No workspace sends telemetry today.** The workspace daemon's `otel` launch toggle used to stamp
`qits.repository.id` / `qits.workspace.id` on processes started inside a workspace. It was removed in
`2d10d13` (2026-09-09), and nothing else on the estate sets those attributes. The store still
buckets the pair if an exporter sends it, but no exporter does. Reads are gated by role only (see
[Authentication](#authentication)), so **read scoping must come back before any workspace sender
does.**

## Two names, on purpose

`qits-observability` is the **bounded context and the deployable** — the gateway route is named for
it, and so are this repository, `qits-observability-service`, and the SPA's,
`qits-observability-frontend`. `qits-telemetry` is the **maven module and java package inside
it** — `eu.wohlben.qits.telemetry.*`. Settled in the superproject as
`1919396 Settle the observability naming question`. The earlier `qits-otel` (the seed README, the
gateway's `OTEL` enum constant and its default `qits-otel` host) is retired; reconciling the
gateway constant belongs to the gateway.

## Layout

| Path | What |
|---|---|
| `domain/` | The error issues (epic qits-2538), artifactId `qits-telemetry-domain`: the records and rules (`…/issues/`), the two JPA entities (`…/issues/entity/`), `ErrorIssueStore` (`…/issues/persistence/`) and the `observability` Flyway lineage (`db/observability/migration`). Internal, never published. |
| `service/` | Everything else, artifactId `qits-telemetry`: the receiver, the buffer, the web stack. |
| `service/src/main/webui/` | The SPA, as the `qits-observability-frontend` submodule. Built and served by Quinoa. |
| `…/api/` | `OtelReceiverResource` (OTLP ingest), `OtelForwarder` (the upstream tee), `WorkspaceTelemetryController` (the UI's JSON), `ErrorIssueController` (the error issues), `TelemetryExceptionMapper`. |
| `…/control/` | `TelemetryDecoder` (protobuf → records), `TelemetryStore` (the buffer), `TelemetryQueryService` (every query the REST surface answers from), `ErrorFingerprinter`, `ErrorIssueAggregator` and its flush, `ErrorIssuePurge`, `TelemetrySizeEstimator`. |
| `…/dto/` | The stored records and the wire DTOs. |
| `…/error/` | This context's own `DomainException` family (migration-plan.md §5). |

The usual `domain/` + `service/` pair, since epic qits-2538. For most of its life this was one
module: the only qits context whose business logic already lived entirely in the monorepo's `service`
module (migration-plan.md §3.6), with nothing persisted to split out. The error issues are its first
tables, and `domain/` is where they, their store and their Flyway lineage live; the buffer and the web
stack stay in `service/`. The directory is still called `service/` because the replayed git history is
anchored to `service/src/**`.

**An application, not a library jar.** `service/` carries `<packaging>quarkus</packaging>` and
produces a process that receives OTLP on its own port — as a JVM fast-jar or as a native binary. It
was extracted as a library on the assumption that some consuming Quarkus application would pull it
in and gain the receiver; no such application was ever written, and under the gateway topology none
will be. A receiver that cannot be started is not a receiver.

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080, ingest on /observability/api/otel/v1/*

    ./mvnw package -Dnative
    ./service/target/qits-observability                    # same routes, ~30ms to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain: the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
if it finds none it does not fail — it quietly falls back to pulling a 1.8 GB Mandrel image and
compiling under docker. That fallback still works and is what a CI without a GraalVM gets; it is
just not the intended path, and it is worth recognising by name when a build that normally takes
about 80 seconds starts downloading a container image.

`-Dnative` also flips `skipITs`, so the build runs `OtelReceiverIT` against the binary it just
compiled. That is not ceremony: this service's whole ingest surface is generated protobuf, which
native-image has to resolve ahead of time, and a mistake there is invisible to the JVM suite and
lands as a runtime failure on the first export. The IT posts real OTLP bodies and reads them back
out through the query surface, so a 200 on bytes that decoded to nothing cannot pass.

## The SPA

The UI is this context's own, and ships inside the same process: `service/src/main/webui` is the
`qits-observability-frontend` submodule (an Angular app), and `quarkus-quinoa` builds it during
augmentation and serves the bundle as static resources. One deployable, one origin — the page and
the API it calls are the same origin, so there is no CORS to configure.

    git submodule update --init            # the webui is a submodule; a bare clone has an empty dir
    ./mvnw -pl service package -am         # quinoa runs `npm install` + `npm run build` inside it

Served at **`/`**, above its own API rather than beside it. The submodule's `angular.json` sets
`baseHref` to `/`, so there is no segment left for the two halves to disagree about. SPA routing is
on, so deep links — `/traces/<id>` and its project-scoped form `/qits/traces` alike — fall back to
`index.html`; the whole `/observability` prefix is excluded from that fallback, which is what keeps
the API and the health endpoints answering for themselves.

**This makes node/npm a build prerequisite, and only a build one.** Quinoa is disabled in test mode
by default, so `mvn verify`'s suite is as offline and as fast as it was — the clone-alone rule holds
where it is checked. `mvn package` now reaches the npm registry, which is the price of serving the
UI from here rather than from a second container.

## What it owns, and what it deliberately does not

**The buffer owns no tables.** `TelemetryStore` is in-memory and ephemeral by design — a JVM
restart empties it, and `…/telemetry/store` reports `startedAt` so a UI can say so rather than
letting an empty screen read as broken. Bounding is three-tier: per-source count caps (spans / logs /
metric series), then a per-source byte budget — the tier that decides what a source retains, from
that source's own volume alone — and finally a global byte ceiling that evicts oldest-first from the
*fattest* bucket, kept as a backstop on this process's heap rather than as an arbiter between
sources. Tuning knobs default in code: `qits.telemetry.max-spans-per-workspace` (2000),
`.max-logs-per-workspace` (10000), `.max-metric-series-per-workspace` (500),
`.max-bytes-per-source` (15 MiB), `.max-total-bytes` (256 MiB). The three count keys keep their
`-per-workspace` spelling for compatibility; a bucket is a source, which is the word the new key
uses.

**Read buffer pressure per source.** The number to watch is a source's own `bytes` against
`maxBytesPerSource`: for an edge-shaped service that budget binds long before the log count cap
does, so counts can sit well under their caps while records are being evicted. `totalBytes` against
`maxTotalBytes` is the backstop gauge, and if it sits near full the answer is a smaller per-source
budget, not a bigger ceiling. `evictedSpans` is still the number that says which question you are
answering: zero means you are seeing everything that arrived, non-zero means you are seeing what
survived.

**Ingest is protobuf-only.** qits pins every launched exporter to `http/protobuf`, so OTLP/JSON
(which deviates from proto3 JSON) and gRPC are not implemented. Gzip is detected by magic bytes
rather than `Content-Encoding`, which is correct whether or not the server already decompressed.

**Inflating is capped at the same 64 MiB the wire body is**, and over it the answer is 413.
`quarkus.http.limits.max-body-size` only bounds what arrives on the socket, and a compressed body is
under that by definition — gzip of a repeated byte runs past 1000:1, so a few kilobytes that pass
the HTTP check can inflate into gigabytes of heap. The receiver therefore counts as it inflates and
stops one byte past the ceiling, reading the limit from that same key so the two cannot drift.

**It does not produce telemetry.** `quarkus-opentelemetry` — qits' own outbound SDK — is not a
dependency here; that is the app shell's business. This repo is the receiving end.

**It does not know what a workspace is.** Records are bucketed by the resource attributes an
exporter stamped, nothing more: the `qits.repository.id`/`qits.workspace.id` pair if both are there,
otherwise `service.name` under `_service/<name>`, otherwise `_unscoped`. Every bucket is bounded the
same way and every bucket is reachable, through `?source=`.

The `service.name` tier is not cosmetic. Nothing on this platform stamps the qits attributes, because
the workspace sender was removed (see the top of this README). Before this tier existed, all ten processes shared one
bucket, which defeated the fairness tier above and put the whole platform's telemetry somewhere no
query could name. Splitting them multiplied the worst-case retained set, which is why the span cap
dropped from 5,000 to 2,000 in the same change.

**The error issues are the one durable thing**, and the next section is about them. They are this
context's only tables, in a postgres database of its own.

## Error issues

Epic qits-2538. Every ERROR log from a platform service (ERROR or worse, a `service.name`, no
`qits.workspace.id`) is fingerprinted at ingest (see *Error fingerprints* below) and folded into a
durable **error issue**, keyed by that fingerprint. Issues survive restarts and buffer eviction; the
log lines behind them do not, and are found while they are buffered by `logs?fingerprint=`.

**Storage.** The `observability` datasource, persistence unit and Flyway lineage
(`domain/…/db/observability/migration`, `V1__error_issues.sql`): `error_issue`, one row per
fingerprint (state, service, exception type, location, title, newest samples, first/last seen and
version, occurrences, regressions, dismiss history), and `error_issue_hour`, its per-hour counts.
`.config/qits/deployments.yml` declares `postgresql:db`, so the deployment injects
`QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD`, which have no defaults: unset, a deployed process
refuses to boot. It also declares `postgresql:eventstream:qits_observability_eventstream`, the
eventstream outbox the issue events go through, and `idp:client`, the service client their publish
authenticates with (see *Events* below).

**Ingest never waits on the database.** `OtelReceiverResource` hands each stamped log batch to
`control/ErrorIssueAggregator` last, after the store and the live feed. `offer` folds it into an
in-memory window by fingerprint and returns; every `qits.observability.error-issues.flush-interval`
(10s) the window is written in one transaction (`ErrorIssueFlushTransaction`). A crash loses at most
one window of counts, which is accepted. At most `.max-pending` (1000) distinct fingerprints are held
per window; the rest, and a window whose write failed, are dropped and counted, with a WARN at most
once a minute — never an ERROR, which would feed this service's own issues.

**States.** A new fingerprint is an OPEN issue. A person dismisses it. A DISMISSED issue whose
fingerprint occurs again is OPEN again, with `regressions + 1` and `regressedAt`, and keeps
`dismissedAt` / `dismissedBy` as history. Issues never expire (owner ruling 2026-11-22). Hourly
counts are kept `.hour-retention` (`P30D`) and purged by an hourly job (`.purge-interval`, `1h`);
an issue's total and first/last seen are never purged.

| operationId | Route | Roles | Answers |
|---|---|---|---|
| `listErrorIssues` | `GET /observability/api/error-issues` | the telemetry readers | `{issues, total, truncated}`, newest `lastSeen` first; `?state=OPEN` (default) / `DISMISSED` / `ALL`, `?service=` (exact), `?limit=` 1–1000 (default 200) |
| `getErrorIssue` | `GET …/error-issues/{fingerprint}` | the telemetry readers | every list field plus `sampleStacktrace` and `hourly` (`[{hour, count}]`, oldest first, zero hours left out) over `?hours=` 1–720 (default 168); 404 unknown, 400 for an id not `^[0-9a-f]{32}$` |
| `dismissErrorIssue` | `POST …/error-issues/{fingerprint}/dismiss` | `qits:admin`, `qits:admin-agent`, `qits:desk-admin` | the issue; idempotent; 404 unknown. A commissioned agent (`qits:agent`) is 403: a person dismisses. |

Tickets made from an issue are not in these answers: they live in qits-projects. `GET
…/telemetry/errors` (`listTelemetryErrors`) is unchanged — the derived by-trace view for `qits
observe errors`.

**`%dev` has no database**, and still boots and ingests: the datasource, the persistence unit and
Flyway are switched off there, the aggregator holds nothing, and the three doors answer 503. The
outbox's datasource cannot be switched off the same way — qits-eventstream injects its persistence
unit directly, and an inactive one fails the boot — so in `%dev` it stays active, aimed at a local
postgres nothing has to run, with Flyway's migrate-at-start off and Hibernate started offline; the
bus is dark, so nothing connects. Dev services are off in every profile. The suite runs against a real postgres from zonky's embedded
binaries (`service/src/test/…/testdb/EmbeddedPg`), a child process rather than a container.

### Events

Epic qits-2538, task qits-2684. This service's first events on qits-events, published through the
`qits-eventstream` library (`QitsEventBus`), in package `eu.wohlben.qits.telemetry.events`; the wire
name is the simple class name.

| Event | When | `occurredAt` / `serviceVersion` | Further fields |
|---|---|---|---|
| `ErrorIssueOpened` | a flush inserted the issue | its `firstSeen`, that occurrence's version | `fingerprint`, `serviceName`, `exceptionType`, `location`, `message`, `traceId` |
| `ErrorIssueRegressed` | a flush turned a DISMISSED issue OPEN | the regressing occurrence's (the window's earliest) | the same, plus `dismissedAt`, `regressions` (after the write), `occurrences` (the total after the write) |

`message` is the normalised title, at most 200 characters, so an event never carries a stack trace;
`traceId` is the issue's newest sampled trace id and absent when there is none (the canonical
payload drops a null). **Exactly one event per fingerprint per flush**, and nothing for a count
increase on an OPEN issue.

**A rollback drops the issue rows and their events together.** `ErrorIssueFlushTransaction` calls
`control/ErrorIssueAnnouncer` inside the flush transaction, right after the store wrote the window.
The announcer builds the events there and registers them with the transaction, publishing only once
it has committed: `QitsEventBus.publish` attempts the PUT inline and writes an undelivered event to
its outbox in a transaction of its own, so a publish made mid-transaction would outlive a rollback.
After the commit the bus owns the event — delivered inline, or an outbox row its sweeper retries.

**Configuration.** `qits.eventstream.enabled` ships on; `%dev` and `%test` turn it off. The outbox
is the library's own datasource, persistence unit and Flyway lineage, `eventstream`, read from
`QITS_RESOURCE_EVENTSTREAM_URL` / `_USERNAME` / `_PASSWORD` with no defaults. The publish carries a
bearer from this service's own `qits` OIDC client (`quarkus.oidc-client.qits.*`, from the
`idp:client` resource's `QITS_RESOURCE_IDP_*`); without one the edge answers 401 and the event waits
in the outbox. `contracts/EventstreamPactTest` writes
`pacts/qits-observability-service_qits-eventstream-javalib_qits-events-service.json`, declared for
`qits-events-service` in `.config/qits/release.yml`. `events/EventWireReflection` registers both
records for native-image. `control/ErrorIssueAnnouncerTest` reads the real outbox with the bus aimed
at a closed port.

## The boundary

This context needs nothing from the rest of qits but its own postgres databases (the error issues
and the events outbox): ingest, the store and the REST query surface work standalone, and the issue
events wait in the outbox while qits-events is not there. Cross-context references are by string id, never a foreign key. Agents read
telemetry through the qits CLI, which calls the REST surface.

In the other direction this context publishes one thing: the error-issue events, to qits-events
(*Events* above). It publishes nothing per workspace. The observability UI polls, and that is the
settled answer. The live stream below is fed from ingest directly.

## Deploying it

`service/src/main/resources/application.properties` now carries what a deployment needs and this
repo can decide — `quarkus.rest.path=/observability/api`,
`quarkus.http.non-application-root-path=/observability/q`, the 64M body limit, and the OpenAPI/swagger-ui settings. Read that
file before adding anything here; it explains why each line is load-bearing.

What is still the deployment's to provide:

- the error-issue database: `QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD`, which
  qits-deployments provisions and injects because `.config/qits/deployments.yml` declares
  `postgresql:db`. There are no defaults; see [Error issues](#error-issues).
- the events outbox, `QITS_RESOURCE_EVENTSTREAM_*` (`postgresql:eventstream:qits_observability_eventstream`),
  and the service client, `QITS_RESOURCE_IDP_*` (`idp:client`), both declared in the same file; see
  [Events](#events). `QITS_DOMAIN` also names qits-events' public address.

- allow-list `/observability/api/otel/v1/*` for unauthenticated access. That is the ingest surface,
  and the exporters hitting it are the platform services' SDKs, not sessions. In the monorepo
  this lives in `auth/core`'s `PublicPaths`; under the gateway it is `PublicPaths` there.
- nothing, for the qits services themselves — **they now export here on their own.** Each of the
  seven carries `quarkus-opentelemetry` and points at one key, `qits.observability.url`, defaulting
  to `http://qits-observability:8080` on `qits-net`. This service is among them: it exports to
  itself at `http://localhost:8080`, with its three ingest uris suppressed so the export of a span cannot produce another one.
  A deployment that moves this receiver sets that one key per service; a deployment that wants a
  service silent sets `quarkus.otel.sdk.disabled=true` there.

Routes: `POST /observability/api/otel/v1/{traces,logs,metrics}` (ingest), the query surface below,
the live stream at `/observability/stream` (a WebSocket, see [The live stream](#the-live-stream)),
plus `/observability/q/{openapi,swagger-ui}`. Ingest is hidden from the OpenAPI document on purpose — it is a wire protocol spoken
by SDKs, not something a generated client calls; everything else is in `docs/openapi.yml`.

| Route | Answers |
|---|---|
| `GET …/telemetry/store` | the buffer's own state: `startedAt`, the caps, `sourceCount`, what it has evicted |
| `GET …/telemetry/sources` | every bucket — `key`, `kind`, `label`, per-signal counts, per-service breakdown, oldest/newest |
| `GET …/telemetry/traces` | the trace list: root name, duration, span count, error flag, `rootMissing` |
| `GET …/telemetry/traces/{traceId}` | one trace's spans and its correlated logs |
| `GET …/telemetry/{errors,slow-spans,logs,metrics}` | as before |
| `POST …/telemetry/records/search` | the live stream asked about the past — see [Searching the buffer](#searching-the-buffer) |
| `GET …/error-issues`, `GET …/error-issues/{fingerprint}`, `POST …/error-issues/{fingerprint}/dismiss` | the stored error issues — see [Error issues](#error-issues) |

**Naming a bucket.** Every query above takes either `?source=<key>` or the original
`?repositoryId=&workspaceId=` pair, and `source` wins when both are given. The key comes from
`…/telemetry/sources` and is **opaque** — pass it back verbatim, do not build one. That is the only
way to reach the buckets keyed on `service.name`, which is where all of this platform's own
telemetry lands; the pair cannot spell them, because a pair key always contains a `/`.

`logs` alone also answers with **neither**: then it reads every bucket, merged by record time into
one tail, and `?service=` is what narrows it to one service. Every log line names its `source`
(the bucket key), so a line from the merged tail can open its trace. `?traceId=` keeps one
trace's lines.

**Error fingerprints (epic qits-2538).** At ingest, after decoding and before the store takes the
batch, `OtelReceiverResource` stamps `qits.error.fingerprint` on every log that is ERROR or worse,
names a `service.name` and carries no `qits.workspace.id` (`control/ErrorFingerprinter`; the tee
relays the exporter's bytes untouched). The fingerprint is 32 lower-case hex characters keyed on
the service, `exception.type`, the class and method of the first in-app frame
(`qits.observability.error-issues.in-app-prefixes`, comma-separated, default `eu.wohlben.qits.`)
and, where either is missing, the normalised body — never the line number or the version.
`?fingerprint=` on `logs` keeps one fingerprint's lines, ANDed with the other params; a value not
matching `^[0-9a-f]{32}$` is a 400.

**The narrowing lenses are the service's, not a screen's.** `?service=` narrows every query above to
one reporting service, `?sinceMinutes=` windows on the ingest stamp, and on `logs` `?minSeverity=`
is a floor on the OTel severity — `TRACE`/`DEBUG`/`INFO`/`WARN`/`ERROR`/`FATAL` (case-insensitive,
`WARNING` accepted) or a raw number 1–24, so `WARN` answers warnings and worse. Anything else is a
400: a severity filter that silently stopped filtering would answer a page of INFO under a heading
that says ERROR. A named floor also excludes records carrying **no** severity, since 0 satisfies no
floor and an exporter's omission is not a band. They are parameters rather than something a UI does
to the answer because these endpoints truncate — filtering a page already cut to 200 shows "the
errors among the last 200 records" while reading as "the last 200 errors".

**Every list is bounded.** `?limit=` on `errors`, `slow-spans`, `logs` and `traces` defaults to 200
and is refused above 1000 with a 400 rather than quietly clamped. Those four answer `{ items…,
total, truncated }`, so a screen can say "showing 200 of 1,841" instead of implying it has
everything. `metrics` has no limit and needs none — one point per series, and the series count is
already capped.

**An unknown bucket is empty, not a 404.** An unknown source key, an unknown workspace pair and a
bucket that eviction emptied all answer 200 with nothing in it, because the store genuinely cannot
tell them apart. `…/telemetry/sources` and `…/telemetry/store` are what make them distinguishable —
whether the key is listed, and what has been dropped. The same holds for `traces/{traceId}`: an id
that never existed and one whose spans were evicted answer identically.

The repository and the workspace are a **filter**, not a container: this context owns neither, and
buckets by the ids an exporter stamped, so they are query parameters. `{traceId}` is in the path
because it identifies the thing being fetched.

`GET /api/config.json` used to be served here and is now **qits-gateway's**, at that same unchanged
path — it is web-component configuration and the gateway serves the web components.

The tee: when qits itself runs as a managed service the supervising qits injects
`OTEL_EXPORTER_OTLP_ENDPOINT`, and every export is forwarded byte-verbatim upstream *before*
decoding, in addition to being stored locally. Fire-and-forget — an unreachable or rejecting parent
is invisible to the local ingest.

## The live stream

`/observability/stream` is a WebSocket that pushes what this receiver takes in (logs, spans with
their events, metrics) as it arrives, filtered per connection on the server. `qits observe` is its
client. This section is the wire protocol, shared with the CLI. Change it here, and on both sides.

- **Auth.** `qits:admin`, `qits:admin-agent`, `qits:desk-admin` or `qits:agent`, checked on the
  upgrade. A person's `qits` token (`aud=qits-platform`, roles
  in `groups`) or the edge's forward-auth headers both work. No credential is 401, no role is 403.
- **Protocol.** A connection starts subscribed to nothing. Each `{"subscribe": [group, …]}` frame
  replaces its filters. Groups are ORed, the conditions in a group are ANDed, an empty group matches
  everything, an empty list matches nothing. A frame it cannot read is answered with `{"error": …}`
  and the old filters stay. Each matching record arrives as `{"kind", "receivedAtMillis", "source",
  "record"}`, where `record` is the DTO the query API returns for that kind and `source` is the
  bucket key. Live only: no replay.
- **Filters.** A group is `{"conditions": [condition, …]}`. A condition is
  `{"field", "op", "value"}`, plus `"key"` for the two keyed fields:

  | field | log | span | metric |
  | --- | --- | --- | --- |
  | `kind` | `log` | `span` | `metric` |
  | `service`, `attribute` (keyed), `resource` (keyed) | yes | yes | yes |
  | `traceId`, `spanId` | yes | yes | — |
  | `severity` (the number 1-24), `body` | yes | — | — |
  | `name` | — | yes | yes |
  | `status`, `event` (any span event's name) | — | yes | — |

  | op | value | holds when |
  | --- | --- | --- |
  | `exact` | a string | the field equals it, case included |
  | `prefix` | a string | the field starts with it, case included |
  | `contains` | a string | the field contains it, any case |
  | `exists` | `true` / `false` | the field is present / absent |
  | `min` | `TRACE` … `FATAL` or 1-24 | `severity` only: at or above it |

  A field a kind does not have (`—`), or has empty, is absent: it fails every op but
  `exists: false`. Numbers and booleans in `value` compare as their text. `control/TelemetryFilter`
  is the reader and `TelemetryFilterTest` pins the table; `qits observe --filter` writes the same
  groups in a compact syntax.
- **Back-pressure.** Ingest never waits for a reader. One dispatcher thread takes each batch from a
  bounded backlog (`qits.telemetry.stream.dispatch-backlog`, 1024 batches). Each connection queues
  at most `qits.telemetry.stream.queue-size` frames (256). A record that finds the queue full is
  dropped, and when the queue next runs empty the reader gets `{"dropped": N}`. The server pings
  every 30 seconds so the edge keeps an idle stream open.

## Searching the buffer

`POST /observability/api/telemetry/records/search` answers what the live stream *would have*
pushed, from what is still buffered, in a closed window. `qits observe query` is its client. Roles
are the query API's: `qits:admin`, `qits:admin-agent`, `qits:desk-admin` or `qits:agent`. A POST because the body is the filter; it
changes nothing.

```json
{
  "subscribe": [ {"conditions": [ {"field": "severity", "op": "min", "value": "ERROR"} ]} ],
  "since": "2026-10-01T18:00:00Z",
  "until": "2026-10-01T19:00:00Z",
  "limit": 100,
  "source": "_service/qits-ci"
}
```

```json
{ "records": [ {"kind", "receivedAtMillis", "source", "record"}, … ], "truncated": false, "bufferedSince": "2026-10-01T12:03:11Z" }
```

- **`subscribe`** is a live subscribe frame's groups, verbatim, read by the same code
  (`TelemetryFilter.parseGroups`) and matched by the same `TelemetryFilter.matches` the live feed
  calls. So a filter means one thing live and searched, and `[]` matches nothing here too. It is the
  one required field; an unreadable filter is a 400 whose message is the socket's `{"error": …}`
  reason, prefixed `unreadable filter: `.
- **`since` / `until`** are ISO-8601 instants, both inclusive. No `since` searches everything still
  buffered, no `until` is now, `since` after `until` is a 400, and so is an instant that does not
  parse. A record is in the window by **its own time** — a log's time, a span's start, a metric
  point's time (the ingest stamp only where an exporter left that at zero) — not by when it arrived.
  This is the one read that does not window on the ingest stamp.
- **A metric only by its latest point.** The buffer keeps one point per series and replaces it in
  place, so a series that reported inside the window and again after it is not found there.
- **`limit`** is 1..1000, default 200, and a 400 outside that, as on every list here. When more
  match, the **newest** `limit` are kept and returned **oldest first**, and `truncated` is true.
- **`source`** is a key from `…/telemetry/sources`; absent searches every source, as the live
  stream does.
- **`records`** are the live stream's frames, unchanged, so a client prints both with one reader.
- **`bufferedSince`** is the ingest stamp of the oldest record still held across the searched
  sources, null when they hold nothing. A window that starts before it reaches past what the buffer
  remembers: an empty answer there is not proof of absence.

`control/TelemetryRecordSearch` is the search; `TelemetryRecordSearchTest` pins the window, the
truncation, the metric rule and parity with the live feed (one record, one filter, the same frame
both ways), and `api/TelemetryRecordSearchControllerTest` the HTTP shape and the 400s.

## Authentication

Two ways in, and `@RolesAllowed` decides for both:

- **Forward-auth headers.** The edge turns a browser session into `X-Qits-User` / `X-Qits-Roles`.
  qits-auth-core's `ForwardAuthMechanism` reads them.
- **A person's bearer token.** A person's command-line tool calls through the edge with that
  person's token. The edge removes forward-auth headers from such a request, so `quarkus-oidc`
  checks the token instead, and its `groups` claim becomes the roles.

Ingest (`/observability/api/otel/v1/*`) is `@PermitAll` and takes an export with no credential.
Every other route refuses a request with neither: 401. See `AGENTS.md`.

**Reads are gated by role only.** Every `/telemetry/*` route, the search and the live stream admit
`qits:admin`, `qits:admin-agent`, `qits:desk-admin` and `qits:agent`, and nothing narrows what a
caller sees after that. `GET /telemetry/sources` lists every bucket unfiltered. Any reader can read
any bucket. That is safe only because every bucket today holds a platform service's telemetry. The
ports that once scoped reads by project were deleted with the MCP tools in qits-1174 (`16ccb0a`).

**Whoever brings back a workspace sender must first bring back read scoping**, failing closed by
project, before any workspace bucket carries data. Without it, one project's agent can read
another project's workspace telemetry.

## What is deliberately *not* here

- **A workspace telemetry sender.** Pointing a workspace process's exporter at this receiver
  belongs to whatever launches that process, not to this repository. No such sender exists today
  (see the top of this README).
- **Anything that authenticates.** `PublicPaths` and the auth variants are an open question
  (migration-plan.md §4).
