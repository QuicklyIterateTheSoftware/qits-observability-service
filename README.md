# qits-observability

The **telemetry** context of qits: an in-process OTLP/HTTP receiver, a bounded in-memory buffer of
what it receives, and a query surface over that buffer for both humans (REST) and coding agents
(MCP). Plus the managed-app relay that goes with it, the upstream OTLP tee.

Its **wire** surface lives under **`/observability`** — the edge path-routes verbatim by prefix on
every vhost, so the segment is part of the path this process itself serves, on `qits-net` as much as
through the edge. There is no unprefixed form.

The **client** is served at `/`. This service has a host of its own,
`observability.<env>.<domain>`, and the SPA owns every path on it the wire routes do not claim.

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker
    mvn verify -Dnative   # and compiles to a GraalVM binary, still no docker (see .sdkmanrc)

Services launched inside a workspace with the `otel` toggle get `OTEL_EXPORTER_OTLP_ENDPOINT` and
`OTEL_RESOURCE_ATTRIBUTES` pointed back at qits. Their spans, logs and metrics land here, bucketed
by the `qits.repository.id` / `qits.workspace.id` attributes they carry. An agent debugging that
workspace then asks `telemetryErrors` instead of scraping logs, and gets exceptions as structured
span events with stack traces, correlated by trace id.

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
| `service/` | The whole context, artifactId `qits-telemetry`. |
| `service/src/main/webui/` | The SPA, as the `qits-observability-frontend` submodule. Built and served by Quinoa. |
| `…/api/` | `OtelReceiverResource` (OTLP ingest), `OtelForwarder` (the upstream tee), `WorkspaceTelemetryController` (the UI's JSON), `TelemetryExceptionMapper`. |
| `…/control/` | `TelemetryDecoder` (protobuf → records), `TelemetryStore` (the buffer), `TelemetryQueryService` (every query both surfaces answer from), `TelemetrySizeEstimator`, `TelemetryChangePublisher`. |
| `…/dto/` | The stored records and the wire DTOs. |
| `…/mcp/` | `TelemetryMcpTools` (five tools on the `observability` MCP server), `TelemetryToolFilter`, `RepositoryScope`, `WorkspaceScope`, and the two ports. |
| `…/error/` | This context's own `DomainException` family (migration-plan.md §5). |

One module, not the usual `domain/` + `service/` pair. This is the only qits context whose business
logic already lived entirely in the monorepo's `service` module (migration-plan.md §3.6) — there is
no `domain/telemetry` to replay. The directory is still called `service/` because the replayed git
history is anchored to `service/src/**`.

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
the API, the health endpoints and the MCP server answering for themselves.

**This makes node/npm a build prerequisite, and only a build one.** Quinoa is disabled in test mode
by default, so `mvn verify`'s suite is as offline and as fast as it was — the clone-alone rule holds
where it is checked. `mvn package` now reaches the npm registry, which is the price of serving the
UI from here rather than from a second container.

## What it owns, and what it deliberately does not

**Owns no tables, and no datasource.** `TelemetryStore` is in-memory and ephemeral by design — a JVM
restart empties it, and `…/telemetry/store` reports `startedAt` so a UI can say so rather than
letting an empty screen read as broken. Bounding is two-tier: per-source count caps (spans / logs /
metric series) plus a global byte ceiling that evicts oldest-first from the *fattest* bucket, so one
chatty service pays for its own volume instead of evicting a quieter one's telemetry. Tuning knobs
default in code: `qits.telemetry.max-spans-per-workspace` (2000), `.max-logs-per-workspace` (10000),
`.max-metric-series-per-workspace` (500), `.max-total-bytes` (64 MiB). The keys keep their
`-per-workspace` spelling for compatibility; a bucket is a source.

**Report buffer pressure in counts, not bytes.** With these caps the count caps bind first every
time — ten full span buckets estimate at roughly 40 MB against a 64 MiB ceiling — so a byte gauge
sits low and still while records are being evicted. `evictedSpans` is the number that matters: zero
means you are seeing everything that arrived, non-zero means you are seeing what survived.

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

The `service.name` tier is not cosmetic. Nothing on this platform stamps the qits attributes — the
sender that would is a known gap, recorded below — so before it existed all ten processes shared one
bucket, which defeated the fairness tier above and put the whole platform's telemetry somewhere no
query could name. Splitting them multiplied the worst-case retained set, which is why the span cap
dropped from 5,000 to 2,000 in the same change.

## The boundary

Everything this context needs from the rest of qits goes through a port it declares and the
consuming application implements. Cross-context references are by string id, never a foreign key.

| Port | Required? | Absent means |
|---|---|---|
| `RepositoryScopeGuard` | no | the telemetry MCP tools are **hidden and rejected** (fail closed) |
| `WorkspaceLookup` | no | likewise |

Both are the MCP scoping checks: "is this repository inside the session's project?" (the projects
context's answer) and "is this still an active workspace of that repository?" (the workspaces
context's answer). They are cross-project isolation checks, so *unconfigured* must never degrade to
*unchecked* — without them `TelemetryToolFilter` does not list the tools and a direct call gets a
404. Ingest, the store and the REST query surface need neither and work standalone.

In the other direction this context publishes `TelemetryChanged(repoId, workspaceId)` as a CDI async
event whenever a scoped ingest fills a workspace's buffers (deduped — a 1000-span batch for one
workspace is one event). An application that also runs the workspaces context bridges it to the SSE
channel:

```java
void onTelemetry(@ObservesAsync TelemetryChanged changed) {
  workspaceChangePublisher.fire(changed.repoId(), changed.workspaceId(), Topic.TELEMETRY);
}
```

With no observer the event is a no-op, which is the supported standalone configuration: browsers
just re-read on their own schedule instead of being pushed at. The event carries no payload, so a
dropped one self-heals on the next.

**Do not build a live channel on this.** The hint fires only for records carrying both qits
attributes, and on this platform nothing stamps them, so it is silent for effectively all the
telemetry that exists. A stream wired to it would look live and never fire, which is worse than
having none. It is also a same-process CDI event, and under the gateway topology qits-workspaces and
qits-observability are separate containers. The observability UI polls, and that is the settled
answer rather than a gap waiting to be filled.

## Deploying it

`service/src/main/resources/application.properties` now carries what a deployment needs and this
repo can decide — `quarkus.rest.path=/observability/api`,
`quarkus.http.non-application-root-path=/observability/q`, the MCP root-path (without which the
process does not boot at all), the 64M body limit, and the OpenAPI/swagger-ui settings. Read that
file before adding anything here; it explains why each line is load-bearing.

What is still the deployment's to provide:

- allow-list `/observability/api/otel/v1/*` for unauthenticated access. That is the ingest surface,
  and the exporters hitting it are SDKs inside workspace containers, not sessions. In the monorepo
  this lives in `auth/core`'s `PublicPaths`; under the gateway it is `PublicPaths` there.
- nothing, for the qits services themselves — **they now export here on their own.** Each of the
  seven carries `quarkus-opentelemetry` and points at one key, `qits.observability.url`, defaulting
  to `http://qits-observability:8080` on `qits-net`. This service is among them: it exports to
  itself, with its three ingest uris suppressed so the export of a span cannot produce another one.
  A deployment that moves this receiver sets that one key per service; a deployment that wants a
  service silent sets `quarkus.otel.sdk.disabled=true` there.

The sender that is still missing, and it is the workspace half:

- **a workspace's dev servers still send nothing.** The overlay that set
  `OTEL_EXPORTER_OTLP_ENDPOINT` on launched services (`OtelEnvironment` in the monorepo) was dropped
  during the daemon extraction as dead code, and the live launch path — the daemon's
  `ServiceSupervisor` — never had it. The `otel:` toggle that used to be parsed and round-tripped
  without ever being acted on has since been removed too, so there is no half-wired remnant to
  mistake for a sender: rebuilding this means building the overlay beside `ServiceSupervisor`,
  aiming it at this service's address, and reintroducing whatever declares it. See
  `migration-deployables-plan.md` §6 in the superproject, which records the deferral.

Routes: `POST /observability/api/otel/v1/{traces,logs,metrics}` (ingest), the query surface below,
plus `/observability/mcp` (the MCP server, named `observability`) and `/observability/q/{openapi,
swagger-ui}`. Ingest is hidden from the OpenAPI document on purpose — it is a wire protocol spoken
by SDKs, not something a generated client calls; everything else is in `docs/openapi.yml`.

| Route | Answers |
|---|---|
| `GET …/telemetry/store` | the buffer's own state: `startedAt`, the caps, `sourceCount`, what it has evicted |
| `GET …/telemetry/sources` | every bucket — `key`, `kind`, `label`, per-signal counts, per-service breakdown, oldest/newest |
| `GET …/telemetry/traces` | the trace list: root name, duration, span count, error flag, `rootMissing` |
| `GET …/telemetry/traces/{traceId}` | one trace's spans and its correlated logs |
| `GET …/telemetry/{errors,slow-spans,logs,metrics}` | as before |

**Naming a bucket.** Every query above takes either `?source=<key>` or the original
`?repositoryId=&workspaceId=` pair, and `source` wins when both are given. The key comes from
`…/telemetry/sources` and is **opaque** — pass it back verbatim, do not build one. That is the only
way to reach the buckets keyed on `service.name`, which is where all of this platform's own
telemetry lands; the pair cannot spell them, because a pair key always contains a `/`.

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

## What is deliberately *not* here

- The **`otel` launch toggle** and the env-var injection that points exporters at this receiver:
  that is the service-supervision half, and lives with workspaces / the workspace-daemon.
- **Anything that authenticates.** `PublicPaths` and the auth variants are an open question
  (migration-plan.md §4).
