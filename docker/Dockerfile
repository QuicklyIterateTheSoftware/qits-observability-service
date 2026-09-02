# qits-observability as a GraalVM-native container IMAGE — the same shape as qits-gateway's and
# qits-workspace-daemon's docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal
# runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces
# service/target/qits-observability in about 80 seconds with no container involved. What this
# Dockerfile adds is the image around it — and, incidentally, a way for a machine with no GraalVM (a
# CI runner, a laptop on a plain OpenJDK) to still get a binary, since the builder stage brings its
# own native-image.
#
# Build from the repo root:
#
#     git submodule update --init service/src/main/webui
#     (cd service/src/main/webui && npm ci && npm run build)
#     docker build -t qits/observability:latest -f docker/Dockerfile .
#
# The first two lines are not optional and are not a convenience: this image carries the client
# (service/src/main/webui = the qits-spa-observability submodule), and the builder stage below
# packages a bundle that was ALREADY BUILT rather than building one. See the Quinoa flags on the
# mvnw line for why the build cannot happen inside this stage.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is qits-platform-mirror's pull-through mirror of quay.io: the
# first build fetches the image from upstream and caches it, every later build is served from local
# disk. The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when
# quay moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole tree, not a module list. Every module here is an unpublished 1.0.0-SNAPSHOT (no
# distributionManagement, no <repositories>), so a builder container with an empty ~/.m2 has nowhere
# to get one but the sources beside it. Copying module by module would mean editing this file every
# time one is added, and forgetting to would fail the build with an unresolved dependency rather than
# anything that names the real cause. `.dockerignore` is what keeps this cheap — without it this line
# drags in the ~119 MB of target/ output sitting next to it, and a stale target/ can shadow what this
# stage compiles.
COPY . .

ARG QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL

# --- and where Maven Central is mirrored, when it is ----------------------------------------------
# The pipeline passes the platform's pull-through (qits-platform-mirror's central proxy, the same
# authority the FROM lines above pull through); EMPTY — the default — means direct Maven Central.
# `.qits-maven-settings.xml` activates its central-proxy profile only on a non-empty value, so a
# bootstrap or hand build that passes nothing resolves exactly as it always did.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL

# `-pl service -am` builds the app and its dependency closure, not the whole reactor. That is not a
# module list — it names the one module this image ships, which the COPY below already names anyway,
# so leaving it out gains nothing. What it buys: this repo will publish libraries as well as an
# image, and a reactor then holds modules that exist for consumers rather than for the binary. On a
# whole-reactor build a compile error in one of those would block deploying this service, and build
# time would grow with the published surface instead of with the app. The image build should depend
# on exactly what the image contains.
#
# -Dnative flips quarkus.native.enabled (this project carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# -DskipTests because the suite is not this stage's gate — `./mvnw verify` on a clone is, and it is
# the declared one (AGENTS.md: a clone of this repo alone builds and tests green). Note the `native`
# profile also flips skipITs to false, but `package` stops short of the integration-test phase, so
# OtelReceiverIT does not run here either; `./mvnw verify -Dnative` is what exercises the binary
# against real OTLP bodies.
#
# --- the Quinoa flags, and why they are HERE and not in application.properties --------------------
#
# package-manager-install: the Mandrel builder image ships no node at all, so this stage installs its
# own — from nodejs.org, which a docker build can reach. It lives here rather than in
# application.properties on purpose: a local or CI `mvnw` build must use the node on PATH, so that no
# build silently downloads a toolchain. The version is pinned so the image build is reproducible, not
# "whatever nodejs.org calls current"; 22.22.0 is the PLATFORM pin, the node every other qits
# service's builder stage installs and the version a developer's host carries. Note it is
# deliberately NOT the step container's node: node-docker-base is node:24-alpine, and the bundle this
# image consumes was built there. The two do not have to agree, because this stage does not build the
# bundle — the commands below are neutered to `--version`, so the only thing node does here is exist
# and prove its package manager runs.
#
# The three command overrides are the load-bearing part, and the reason this repo deviates from the
# platform's Quinoa recipe (docs/project-setup-quinoa-angular.md in the qits monorepo, which has the
# builder run the client's real install and build):
#
#   service/src/main/webui is qits-spa-observability, and it depends on @qits/ui-components. That
#   package exists ONLY on the platform's own npm registry (qits-artifacts). A RUN step in a docker
#   build reaches the public internet — Maven Central, npmjs.org, nodejs.org — and can reach the
#   platform registry by NO address: not the qits-net alias, not registry.dev.localhost:8080, not
#   host.docker.internal. So the install cannot happen here (it hangs and then dies — CI run
#   82c3893a), and an install that "worked" here would have resolved the @qits scope against
#   npmjs.org, where that name does not exist and never will.
#
# The bundle is therefore built BEFORE this image, by the pipeline step in
# .config/qits/ci-post-receive.yml, which runs on qits-net where the registry is reachable, and
# arrives in the build context as service/src/main/webui/dist/ (see .dockerignore). `--version` is
# the null command: it proves the package manager is really there and does nothing else.
#
# THIS DOES NOT FAIL QUIETLY. The `test -f` below stops a context with no bundle before the
# multi-minute native compile, and Quinoa's own build-dir check would stop it after. A forgotten
# `npm run build` in the step is a red build, not a service that ships without its client.
#
# A build context whose webui submodule is NOT initialised fails this stage with `No package.json
# found in Web UI directory: 'src/main/webui'` — git leaves the gitlink as an empty directory, and an
# empty directory is the one case Quinoa treats as a misconfiguration rather than as "no client".
# The repo's own CI initialises it in .config/qits/ci-post-receive.yml before `docker build`.
#
# --- and why the bundle is copied onto itself first -----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in with
# `DirectoryNotEmptyException: …/dist/qits-spa-observability/browser`, which names the source and
# explains nothing. Every other Quinoa build in the platform is immune by accident: their builder
# stage *creates* dist itself, so it is in the upper layer already. Ours arrives by COPY.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
RUN --mount=type=secret,id=qits-client-id,env=QITS_MAVEN_AUTH_USR \
    --mount=type=secret,id=qits-client-secret,env=QITS_MAVEN_AUTH_PSW \
    d=service/src/main/webui/dist/qits-spa-observability/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false
# cyclonedx.skipNotDeployed=false is LOAD-BEARING, measured on qits-artifacts-service's first SBOM
# release (2026.902.45658): the service module skips maven-deploy — it ships as this image, not as a
# jar — and the plugin SKIPS deploy-skipping modules by default, with a green build and one INFO
# line. The sbom stage's COPY then fails on a file nothing wrote. The property spelling matters too:
# the bare -DskipNotDeployed=false is silently ignored; only the cyclonedx.-prefixed one reaches the
# plugin.

# --- sbom -----------------------------------------------------------------------------------------
# The service module's CycloneDX document, exported so the release step can PUT it to the platform's
# SBOM store. A stage rather than a layer of the runtime image, and BEFORE the runtime stage on
# purpose: the last stage is what a plain `docker build` builds, and that must stay the image. The
# release step reaches this one with `--target sbom --output type=local` using the SAME args and
# secrets as the image build, so every layer above is a cache hit and the export costs seconds.
# `makeBom` rides on the mvnw invocation above rather than a second one, for the same cache reason —
# one RUN, one layer, one truth.
FROM scratch AS sbom
COPY --from=build /src/service/target/sbom.json /sbom.json

# --- runtime --------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# `quarkus.package.output-name` names the binary `qits-observability` — the deployable's name, not
# the module's (the module is `qits-telemetry`, in service/) — so the process is identifiable in
# `ps` / `docker top` as the thing the gateway routes to.
COPY --from=build --chown=1001:root /src/service/target/qits-observability /work/qits-observability
RUN chmod +x /work/qits-observability

# One port, and it is the only one: the OTLP receiver is in-process JAX-RS on the ordinary HTTP port
# (`POST /observability/api/otel/v1/{traces,logs,metrics}`), not a collector's 4317/4318 listener.
# Nothing here enables a separate management interface either, so 8080 carries ingest, the REST query
# surface, the `observability` MCP server and /observability/q alike. Exporters are pointed at a
# literal URL under that same port — see application.properties on `quarkus.rest.path`.
EXPOSE 8080
USER 1001

# -Dquarkus.http.host=0.0.0.0 because Quarkus binds 127.0.0.1 by default, which inside a container
# means reachable by nothing. Unlike the workspace-daemon, that is the wanted posture here: this
# service is addressed by its peers on qits-net.
ENTRYPOINT ["/work/qits-observability", "-Dquarkus.http.host=0.0.0.0"]
