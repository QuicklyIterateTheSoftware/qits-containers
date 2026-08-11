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
  there is no split package. `core` owns the root, `spec`, `docker`, `control`, `entity` and
  `persistence`; `client` owns `client`; `service` owns the adapters.
- **`control` never touches docker directly and `entity` never decides anything.** The registry, the
  boot sweep, the observer and the policy sweeps all live in `control`, they all call the seam, and
  they all write rows through the repositories in `persistence`. A query that answers "which
  containers look like mine" belongs in neither: the rows are the registry, and a listing by label
  is the reap this repo exists to remove.
- **`core/docker` is argv and process, never a docker call.** `DockerArgv` is pure functions and
  `ContainerProcess` is the shell-out; the driver that puts them together is an interface here
  (`control/ContainersDriver`) and an implementation in `service/`. That is what lets the argvs — the
  sandbox itself — be asserted element for element with no daemon anywhere.
- **The fakes are duplicated per module, not shared.** Maven has no `testFixtures`, and a test-jar
  dependency between modules that otherwise have none is the higher price. `core`'s
  `FakeContainersDriver` is the original; a module that needs one copies it. Same stance as
  qits-workspaces' two `FakeContainerRuntime`s and qits-ci's two `FakeCiStepRunner`s.
- **The datasource, the persistence unit and the Flyway lineage live in `core`**, shipped as
  ordinal-100 defaults in `META-INF/microprofile-config.properties`. The app's own settings are in
  `service/src/main/resources/application.properties` at ordinal 250. Never restate one file's key
  in the other, and never re-declare an app-level setting in test resources: a suite green because
  the *test* copy is right proves nothing about what ships.
- **Schema changes append to `core/src/main/resources/db/containers/migration/`.** Never edit an
  applied migration. V1's header records the two decisions it makes — no check constraints on enum
  columns, and the spec's environment is never persisted because it carries secrets. V2 is that rule
  being followed: one nullable `max_age_s`, no backfill, because a policy value the sweeps read has
  to live on the row or a restart forgets it.

## The worker, and the brackets

**One worker.** `ContainerObserver` owns a bare daemon ticker (`ct-observation-ticker`) and a
single-threaded `ct-worker`, and every background write of this service runs there in queue order —
the observation pass, the idle sweep, the volume reconcile, the max-age collection and the row
prune. A tick arriving while a pass is queued collapses into it. Do not give a sweep a thread or a
scheduler of its own: a second concurrency model is how a sweep comes to stop a container an
`ensure` is halfway through starting.

**No transaction spans a docker call**, anywhere. Read the candidates in one bracket, copy them out
as plain values, ask docker between brackets, write each outcome in its own. A record crossing that
boundary is never an entity.

**Which `DbRetry` spelling to use is decided by who owns the transaction**, not by taste. A read is
`DbRetry.call` around a bracket the read opens itself; a state transition **is** a
`DbRetry.inNewTx`/`runInNewTx`, and every one of those bodies ends in a `flush()` — an ORM flushes
at commit by default, which would put the write on the far side of the one round trip nothing can
place. Without the flush the wrap reports rather than helps. The budget is
`ContainerRegistry.CUTOVER_BUDGET` (30s), package-private so one number is not spelled twice.

**Nothing in `control` sets a causation id.** The table's one insert is `ensure`, on the caller's own
thread, where `@PrePersist` still sees the ambient scope — measured, and `CtCausationStampTest` is
the measurement. A writer that ever **inserts** a row from a background thread must set the cause as
data (`CausedRow.causationId(UUID)`), the way qits-ci's `CiRun` does across its queue hop, and never
ship a stamp that writes nothing.

**The Clock is injected and this module produces none.** The qits-eventstream jar ships a
`@DefaultBean` `java.time.Clock` for the whole platform, and a second default producer of the same
type fails the build with an ambiguous resolution — measured 2026-08-11. `java.time.Clock` is a JDK
type, so nothing in `core` imports an eventstream class for it.
- **`client` must not gain a dependency on `core`.** See README's Layout.
- `DatasourceBaselineTest` and `ArchRulesTest` are the platform's shared rules, test scope from
  `qits-arch-rules`. They fail this build for a datasource missing a line of the three-line
  resilience block, and for an entity that neither implements `CausedRow` nor declares `@Uncaused`.
