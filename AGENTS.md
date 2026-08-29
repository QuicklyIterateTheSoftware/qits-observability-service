# qits-observability — working notes

Read `README.md` first: it defines the boundary (receiver vs. producers), lists the routes and the
ports. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no network, no prior
`mvn install` elsewhere, no credentials. `mvn verify` is the gate. Anything that would break that is
not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why: the poms duplicate versions instead of inheriting them, the cross-context scoping
checks are ports with fakes in `src/test` rather than a real projects/workspaces database, and
`OtelStubTestResource` runs a `com.sun.net.httpserver.HttpServer` on an ephemeral port instead of
reaching a real OTLP collector. **Never make the suite depend on a live collector, on port 4317/4318,
or on the network.** `OtelTeeUnreachableTest` deliberately points at `http://localhost:1`, and that
must stay a fast connect-refused, not a timeout.

**`service/` compiles to a GraalVM native image**, the same rule qits-gateway and
qits-workspace-daemon carry, and it extends the clone-alone rule rather than qualifying it:
`.sdkmanrc` names `25.0.2-graalce`, so `sdk env` gives you a `native-image` and `./mvnw package
-Dnative` produces `service/target/qits-observability` in about 80 seconds with no container
involved.

Two consequences worth stating before you reach for a dependency or a static field:

- **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image ...
  Attempting to fall back to container build` and shells docker with a 1.8 GB Mandrel image. Green
  either way, so the fallback is easy to be in without noticing — recognise it by the image pull.
- **Reflection, dynamic proxies, `ServiceLoader`, resources loaded by computed name and JNI/JNA all
  have to be registered**, and the failure lands at *runtime* in the binary while the JVM suite
  stays green. So does live machinery in a `static final`: Quarkus initialises application classes
  at build time, and anything holding threads or sockets then ends up in the image heap and is
  rejected outright — which is why `OtelForwarder`'s `HttpClient` is a bean field built in
  `@PostConstruct` rather than a class constant. If a native build needs configuration or a
  restructure to pass, that is part of the change, not a workaround.

## Package and module conventions

`eu.wohlben.qits.telemetry.*`, one maven module, sub-packages `api` / `control` / `dto` / `mcp` /
`error`. The module directory is `service/` and the artifactId is `qits-telemetry`; they disagree on
purpose (see the poms' header comments — directories are the git-history anchor, artifactIds are the
settled name).

There is no `domain/` module and no reason to add one: nothing here is persisted, so the usual split
(framework-free entities + persistence + Flyway in `domain`, web stack in `service`) has nothing to
separate. If this context ever grows a table, split it then.

`api/` holds JAX-RS. `control/` holds the store, the decoder and the query service, and stays free
of JAX-RS annotations so it can be unit-tested without booting Quarkus — `TelemetryStoreTest` and
`TelemetryDecoderTest` are plain JUnit and should stay that way.

## Adding a dependency on another context

Don't. Declare a port in the package that needs it, inject it as `Instance<T>`, and make absent a
supported configuration with a documented behaviour — see the table in the README.

For the two existing ports the documented behaviour is **fail closed**, not "skip the check": they
are cross-project isolation boundaries. A future port that only enriches a response may of course
degrade gracefully instead; say which in its javadoc.

Never add a JPA relation to another context's entity, and never add an entity here at all without
first re-reading "Owns no tables" in the README. Telemetry references repositories and workspaces by
the string ids an exporter stamped into its resource attributes; those ids are not validated at
ingest and are not foreign keys.

## The buffer

`TelemetryStore`'s lock order is always `evictionLock → bucket monitor`, and appenders never take
`evictionLock` while holding a bucket monitor. Keep it that way; the two bounding tiers can
otherwise deadlock. Every mutation path must also stay byte-accounted — `account()` is called with a
negative delta on every eviction, and a missed one leaks the global ceiling. Eviction is likewise
counted: `evictOldestSpan` / `evictOldestLog` are the only places records leave, so route new
eviction paths through them rather than calling `removeFirst()` and leaving the counter behind.

Anything appended fires at most one `TelemetryChanged` per distinct scoped workspace per call. Do
not fire per record; a 1000-span batch is one event by design. Do not wire a stream to it either —
it is silent for everything that is not workspace-scoped, which today is all of it, and the reason
is written in its javadoc.

**Bucket keys are opaque, and that is load-bearing.** `keyFor()` picks the workspace pair, then
`service.name`, then `_unscoped`; the REST surface hands the key out through `…/telemetry/sources`
and takes it back as `?source=`. No caller constructs one, so the tiers can change without a wire
change. The MCP tools keep the pair-shaped vocabulary (`spans(repoId, workspaceId)` and friends),
which delegate to the `…In(sourceKey)` twins — an agent's scope is a workspace and it must never
learn a key that reaches past one.

Nothing here may need CDI: `TelemetryStoreTest` news the store up directly and stays plain JUnit.
That is why the eviction counters are `AtomicLong` fields and not metrics, and why `startedAt` is a
plain field that a `StartupEvent` observer merely re-stamps.

## Authentication

Authentication happens at `qits-gateway`. This service resolves a principal from a trusted header
(`X-Qits-User`, read by `telemetry/security/ForwardAuthMechanism`) and authenticates nothing.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select in this service. The shared `qits-auth-core` resolves both
`X-Qits-User` and `X-Qits-Roles`; human-facing REST boundaries use Jakarta
`@RolesAllowed("qits:admin")`. Machine-facing boundaries require an authenticated identity and
retain their narrower `MachineAuth` audience/scope checks.

**`X-Qits-*` is the gateway's reserved namespace, stripped from every inbound request
unconditionally**, so a client cannot forge one. That strip rule is the entire reason the header can
be trusted here — and it is why `ForwardAuthTest` sets the real header rather than reaching for
`@TestSecurity`. The header *is* the contract under test; a test that mocked the identity instead
would pass just as happily against a mechanism that never reads it.

None of this reaches ingest. `/observability/api/otel/v1/*` is allow-listed unauthenticated because
its callers are exporter SDKs inside workspace containers, not sessions — they carry no identity and
never traverse the front door at all, since every service sits unpublished on `qits-net` alongside
those containers (`migration-plan.md` §9 item 21). The gateway is a perimeter against the internet,
not a boundary on `qits-net`; do not write anything here as if it were. The scoping guards, not the
identity, are what keep one project's telemetry out of another's.

## Tests

- Register scope with `FakeRepositoryScopeGuard.allow(repoId)` and
  `FakeWorkspaceLookup.register(repoId, workspaceId)`. Both are `@ApplicationScoped` beans in
  `src/test/java`, so they are present in every `@QuarkusTest` here; reset them in `@BeforeEach`
  alongside `store.clear()`.
- `TelemetryFixtures` builds real `Export*ServiceRequest` protobufs. Seed the store through the real
  `TelemetryDecoder` rather than hand-constructing `StoredSpan`s where the decoding is part of what
  you're asserting.
- **The canary is the producer's-eye view.** `TelemetryFixtures.canaryLogsRequest` /
  `canaryTraceRequest` build one batch shaped like a platform service's OTel logging bridge —
  `service.name=qits-canary` plus an instance attribute, an INFO and an ERROR carrying
  `exception.type/message/stacktrace`, both inside one span. `CanaryLogStreamTest` posts it as bytes
  and answers every consumer question through the REST surface only; `OtelReceiverIT` runs the same
  batch through the packaged artifact once. Change the fixture and both move together, which is the
  point: the log-streaming plan's LB workstream is "one realistic payload, asserted where a reader
  reads it", not more decoder cases.
- App-level config lives in `src/main/resources/application.properties` —
  `quarkus.rest.path=/observability/api`, `quarkus.http.non-application-root-path=/observability/q`,
  the MCP root-path, the body limit, the OpenAPI info — and **the tests inherit it**. Quarkus merges
  main's copy into the test config rather than letting `src/test/resources/application.properties`
  shadow it, so the suite exercises the values that actually ship. Never re-declare an app-level key
  in test resources: the copy drifts, and the suite goes on asserting `/observability/*` while the
  packaged process serves something else. `src/test/resources/application.properties` is for values
  a test run genuinely needs to be *different*, and today there are none.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml` as a side effect. Regenerate and commit it
  whenever the REST surface changes:

      ./mvnw -pl service test -Dtest=OpenApiSchemaExportTest

  It runs as a `@QuarkusTest`, so **the test classpath is indexed too**: any `@Path` resource under
  `src/test` lands in the committed document unless it is `@Operation(hidden = true)`. That is why
  `IdentityEchoResource` carries the annotation. The document should hold exactly the eight
  telemetry query operations — ingest is hidden on purpose.

  Response records nested in the controller generate as `Response`, `Response1`, `Response2`… and a
  generated client inherits those names, so each carries `@Schema(name = …)`. Give any new one a
  name too; the alternative is a renumbering that silently reshuffles every existing schema.
- `OtelReceiverIT` is the first of three `@QuarkusIntegrationTest`s (see the userflow section
  below for the other two), and it runs against the *packaged* process
  — the fast-jar or, under `-Dnative`, the binary. It exists for native-image: protobuf decoding is
  the thing here that can be green in `OtelReceiverResourceTest` and broken in the image, and a boot
  check would not see it because nothing loads a message class until a body arrives. So it posts
  real OTLP bodies and reads them back through the REST query surface — a receiver that accepted the
  bytes and decoded nothing answers 200 all day. `skipITs` defaults true in the root pom and the
  `native` profile flips it, so a plain `mvn verify` stays as fast and as docker-free as it was;
  nothing here needs docker on either path. Keep it that way.
- **A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake**
  (`migration-plan.md` §9 item 14), not your change: `@QuarkusTest` restarts race for the test port.
  Re-run before investigating.

## The third IT: the round trip, and the userflow

`api/TelemetryBootstrapIT` boots the **packaged** artifact beside a `MockService` standing in for
the **parent qits-observability**, and tells the whole trip in two stories: an exporter with no
identity puts a batch in, the tee relays it upstream, and a named operator carrying `qits:admin`
takes the same records back out.

Four things about it are easy to undo:

- **Its one seam is `otel.exporter.otlp.endpoint`** — the env-var-shaped key `OtelForwarder` reads
  and a supervising qits injects as `OTEL_EXPORTER_OTLP_ENDPOINT`, not a `quarkus.*` one. Spelled
  that way on purpose: a rename on either side then fails here rather than in production.
- **`quarkus.otel.sdk.disabled=true` in the profile is a NEUTRALISATION, not tidiness.** The shipped
  config points this service's own SDK at `http://qits-observability:8080/…`, a name that resolves
  on `qits-net` and nowhere else, so a launched artifact would spend the run retrying an export into
  the void. Disabling it also makes the mock parent's recordings unambiguous: the only thing that
  can post to it is the tee.
- **It does not replace `OtelStubTestResource`.** That one is a real `HttpServer` capturing request
  *bodies*, which is how `OtelTeeTest` proves byte-verbatim relay on the JVM; `MockService` records
  method, path and headers, which is what a userflow interaction is made of. The IT's contribution
  is that the relay happens **at all** from the packaged (and, under `-Dnative`, compiled) process.
- **The two doors are only distinguishable here.** `OtelReceiverResource` is `@PermitAll` and
  `WorkspaceTelemetryController` is `@RolesAllowed("qits:admin")`, and under `@QuarkusTest`
  qits-auth-core's `%test` dev-user hands every request `qits:admin` before either annotation is
  consulted. A `NORMAL` launch has no dev-user, so 401 / 403 / 200 on one port is a fact no
  `@QuarkusTest` in this repository can state.

**It is opted in by NAME, not by `skipITs`.** The root pom keeps `skipITs=true`, because failsafe
has one run per module and half of `PackagedSurfaceIT` is about the SPA, which the userflow pipeline
deliberately does not build (`-Dquarkus.quinoa=false`). Run it — and
`.config/qits/ci-event-userflows.yml` runs it — as
`./mvnw verify -DskipITs=false -Dit.test=TelemetryBootstrapIT`.

It is also this repo's first **userflow**: the two stories are `@UserStory` methods in category
`telemetry`, so a `verify` also writes `service/target/userstories/` — the proof as documentation,
with the tee drawn as a sequence diagram. They are **browserless** (an `Interactions` parameter and
no `Flow`), so the framework's transitive Playwright never launches anything and no browser is
downloaded. They also share one launched process and one in-memory buffer, so they are written to be
**order-independent**: each reads a bucket the other never writes. Keep it that way, or add a
`@UserflowPrecondition` and say so. The class orderer is installed the one way Quarkus permits — the
`junit.quarkus.orderer.secondary-orderer` line in `service`'s test properties; a local
`junit-platform.properties` hard-fails surefire.

`.config/qits/ci-event-userflows.yml` publishes the reports per commit as the docs bundle
`@userflows/qits-observability`, and is **non-gating by design**: it is a separate file from
`ci-post-receive.yml` so a red story does not cost the branch its image.

## Known broken, not this rollout's to fix

`OtelReceiverIT` reads the query surface with **no identity**, and that surface became
`@RolesAllowed("qits:admin")` in the 2026-08-15 "protect observability APIs" sweep without the IT
moving with it. `skipITs` has hidden it ever since — nothing has run it — so expect 401s the first
time somebody runs `./mvnw verify -Dnative`. The fix is the two `X-Qits-*` headers
`TelemetryBootstrapIT` sends; it is a separate change on purpose.

## What is not ours to change

The gateway's `QitsService.OTEL` constant and its `qits-otel` default host still carry the retired
third name. Reconciling them is the gateway's change, tracked as migration-plan.md §9 items 1 and 9.
