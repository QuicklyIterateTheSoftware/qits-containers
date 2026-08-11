# qits-containers — working notes

Read `README.md` first: it defines the boundary and the module split. This file is the conventions
on top of it.

It is short because the repository is. Grow it with the code — a design document written before the
code is a document the code will contradict.

## The rule that shapes everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. `./mvnw verify` is the gate.

That rule bites hardest here, because docker is this repository's subject. A suite that needed a
daemon to say what the orchestrator does could never say it without one, so the docker seam is faked
in tests and the real driver is proved by an integration test that opts in. The store being postgres
costs no docker either: `testdb/EmbeddedPg` spawns zonky's real binaries as a child process — a
maven dependency, not a container. **Never Testcontainers, never a Quarkus dev service.**

**One address is the whole exception.** `qits-db-core`, `qits-arch-rules` and `qits-eventstream`
come from the platform Maven repository (`<repositories>` in the root pom), so a clone builds green
with that repository reachable and offline once the jars are in `~/.m2`. Nothing else may follow
them in.

## The two invariants this repo exists for

**1. Adopt on boot, never reap.** No code path may remove a container that no registry row names.
A restart of this service finds its containers still running and adopts them; a container with no
row is somebody else's — a compose original, a bootstrap seed, a container from before this service
existed — and unclaimed means left alone. The row is written **before** `docker run`, so a crash can
never leave a container the registry has no name for. Anything that would sweep the host by label
rather than by row is the regression this repo was built to remove.

**2. Every docker call carries a timeout and an output bound.** Both are security properties, not
tuning: a `docker logs` with no bound is a heap the caller chose the size of, and a call with no
timeout is a worker held forever by a daemon that stopped answering. There is no call shape that is
exempt, including the ones that "cannot" block.

## Conventions

- `eu.wohlben.qits.containers.*`, split across three maven modules with disjoint sub-packages, so
  there is no split package. `core` owns the root and `entity`; `client` owns `client`; `service`
  owns the adapters.
- **The datasource, the persistence unit and the Flyway lineage live in `core`**, shipped as
  ordinal-100 defaults in `META-INF/microprofile-config.properties`. The app's own settings are in
  `service/src/main/resources/application.properties` at ordinal 250. Never restate one file's key
  in the other, and never re-declare an app-level setting in test resources: a suite green because
  the *test* copy is right proves nothing about what ships.
- **Schema changes append to `core/src/main/resources/db/containers/migration/`.** Never edit an
  applied migration. V1's header records the two decisions it makes — no check constraints on enum
  columns, and the spec's environment is never persisted because it carries secrets.
- **`client` must not gain a dependency on `core`.** See README's Layout.
- `DatasourceBaselineTest` and `ArchRulesTest` are the platform's shared rules, test scope from
  `qits-arch-rules`. They fail this build for a datasource missing a line of the three-line
  resilience block, and for an entity that neither implements `CausedRow` nor declares `@Uncaused`.
