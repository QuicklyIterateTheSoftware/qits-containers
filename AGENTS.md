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
in tests and the real driver is proved by an integration test that opts in — `ContainersRestartAdoptionIT`,
tagged `extended`, run by `./mvnw verify -DskipITs=false` and excluded from the `-Dnative` gate
through `qits.it.excluded-groups`. It is the one place the headline claim is really made: a
container's docker `Id` and `StartedAt` are unchanged after a restart adopts it, and those two
numbers only exist on a host.

The store being postgres costs no docker either: `testdb/EmbeddedPg` spawns zonky's real binaries as
a child process — a maven dependency, not a container. **Never Testcontainers, never a Quarkus dev
service.**

**One address is the whole exception.** `qits-db-core`, `qits-arch-rules`, `qits-eventstream` and
`qits-auth-core` come from the platform Maven repository (`<repositories>` in the root pom), so a
clone builds green with that repository reachable and offline once the jars are in `~/.m2`. Nothing
else may follow them in.

`qits-auth-core` is the fourth and it arrived in WP4, which is what widening that list costs: every
route here is addressed to an owner, the owner **is** the caller, and the platform has exactly one
answer to "who is calling". A second answer invented in this repository would be the interim static
token the platform has a standing decision against. It brings no server and no transport —
quarkus-oidc validates the bearer, and the jar reads the identity that validation leaves behind.

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
- **A docker daemon that did not answer is not a docker daemon with no such container.**
  `DockerContainersDriver.inspect` **throws** when the call could not be made or timed out, and
  answers empty only for a refusal that says "no such container". Its empty answer is a positive
  statement the boot sweep settles rows on and that `delete` reads as "it is really gone" — a delete
  that took "we could not find out" for that would settle `GONE` over a container still running,
  which nothing would ever look at again. Every caller already treats the throw as "say nothing".
  The listings degrade to empty with a warning instead, because an empty listing is a statement
  about no particular container.
- **`core/docker` is argv and process, never a docker call.** `DockerArgv` is pure functions and
  `ContainerProcess` is the shell-out; the driver that puts them together is an interface here
  (`control/ContainersDriver`) and an implementation in `service/`. That is what lets the argvs — the
  sandbox itself — be asserted element for element with no daemon anywhere.
- **The fakes are duplicated per module, not shared.** Maven has no `testFixtures`, and a test-jar
  dependency between modules that otherwise have none is the higher price. `core`'s
  `FakeContainersDriver` is the original; a module that needs one copies it. Same stance as
  qits-workspaces' two `FakeContainerRuntime`s and qits-ci's two `FakeCiStepRunner`s. **The
  `service` copy is an `@Alternative` with no priority**, because that module ships the real driver:
  an ordinary bean would be an ambiguous resolution and a globally enabled alternative would take
  the daemon away from the one test that needs it, so each suite names the driver it means in its
  profile's `getEnabledAlternatives()`.
- **Every route is guarded, reads included** (`api/OwnerGuard`). The rest of the fleet guards its
  writes and leaves its reads open because a person reads through the gateway; nothing here is read
  by a person, and an inventory of running containers is as much a module's own as the containers
  are. The owner in the path is compared against the machine token's **subject, whole** —
  `dev-qits-ci` and `prod-qits-ci` are two owners, and that prefix is what keeps two environments
  sharing one docker daemon apart. With the rollout gate off (the shipped default) the path owner is
  trusted, exactly as every sibling behaves.
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
- **`client` must not gain a dependency on `core`.** See README's Layout, and the client section
  below.
- `DatasourceBaselineTest` and `ArchRulesTest` are the platform's shared rules, test scope from
  `qits-arch-rules`. They fail this build for a datasource missing a line of the three-line
  resilience block, and for an entity that neither implements `CausedRow` nor declares `@Uncaused`.

## The data plane, and the two rules it is under

`service/…/proxy/` is the reverse tunnel, ported from qits-projects' `AgentTunnels` and
qits-workspaces' `WorkspaceTunnels` — near-identical twins whose javadocs carry the measurements and
are reproduced rather than summarized. README's "The data plane" says what it is and what round 2
owes; these are the two rules you can break without the build noticing.

**1. `TunnelProtocol` is APPEND-ONLY.** Every constant in it — both paths, both frame names, the
field names, the handshake header — is baked into a container's environment at creation, and only a
*recreate* re-injects it. So a container started this morning is still dialling the string that file
held this morning. Add a constant, never repurpose one; a behaviour change bumps
`CAPABILITY_VERSION` and the host branches on what a daemon announces. The one derivation allowed is
the stream prefix being built on the control prefix, so the two cannot drift.

**2. The per-tunnel secret is deliberate, and it is where this port departs from its sources.** Both
control sockets it was ported from are token-free and take their caller's identity from a **path
parameter**, so anything on the platform's network can claim to be any project's or any workspace's
daemon. Both repositories record that and carry it, correctly: containers are already running
against those contracts and an interim token is what the platform has a standing decision against.
Neither reason applies here — nothing runs against this contract yet — so the row id in the path is
the *claim* and `X-Qits-Tunnel-Secret` is the evidence, checked constant-time, before the row is even
read. **Do not "simplify" it back to the sources' shape**, and do not add a second credential beside
it: the dial-back stays nonce-only, because a dial-back repeating the secret would be a second place
for it to leak.

The durable-secret question is *deferred, not open*: re-issue on adopt is the answer, a column on
the row is the one to argue against. README says why.

Three smaller things, each a trap:

- **The gate refuses at open; it cannot unregister.** A websockets-next endpoint is registered at
  augmentation, so with `qits.containers.proxy.enabled` off the socket exists and closes every dial
  with `TunnelProtocol.Close.DISABLED`. `TunnelGateTest` asserts the refusal, the route's 404 **and**
  that no loopback listener is bound — that third one is what keeps the gate from becoming a gate in
  name only. It runs on `FakeDriverProfile`, the profile with no config overrides, because the claim
  is about the value the jar ships.
- **The stream route is raw Vert.x and must stay raw**, even though the extension is now in this
  module. `io.quarkus.websockets.next.Connection` has `sendBinary` and no
  `writeQueueFull`/`drainHandler`, and a byte tunnel with no backpressure signal is an unbounded heap
  buffer. Same reason both sources give.
- **The socket reads the row through `ContainerRegistry.place`, never through the repository.** An
  empty answer there disconnects a daemon, so it must mean "no live row" and never "could not ask" —
  the retried bracket is what keeps those apart, and a direct `findById` would refuse every healthy
  container the moment postgres blinked.

## The client module, and its three rules

**1. No `core`, no `service`, and no framework.** The first two are the module split; the third is
what makes the first two survivable. `ContainersClient` is a plain class with a constructor —
nothing annotated, nothing injected — so the jar's whole dependency list is `jackson-databind` and
`qits-eventstream`. A `quarkus-arc` here would put a CDI container into a consumer that deliberately
runs none, and an OIDC extension would put a second answer to "who is calling" beside qits-idp's.
The consumer writes a three-line producer; the README has it.

The one dependency that is not obviously free is **qits-eventstream, and it is taken on purpose**.
The client stamps `X-Qits-Causation-Id` by hand, which is what `CausationHeader`'s own javadoc
prescribes for a caller speaking `java.net.http.HttpClient` — `CausationClientFilter` is a JAX-RS
provider and there is no JAX-RS here to discover it. Copying the header name into this jar would be
a second answer to a settled question that stops matching the day the name moves; declaring the jar
`provided` would make it a line every consumer must restate and a `NoClassDefFoundError` for the one
that forgets. Compile scope, the same reasoning qits-eventstream's own pom gives for taking
qits-db-core at runtime scope rather than optional.

**2. The wire is the contract, and both sides restate it.** `client/ContainersWire` mirrors
`service/api/ContainersWire`; neither jar sees the other and neither may be made to. That is what
lets a consumer be built and released without this repository, and it is why the client's records
**validate nothing** — the belts are `ContainersIdentifiers`', on the far side, where a refusal can
name the field and come back as a 400. Two sets of rules would drift the first time the service's
widened.

The client is **forward compatible in the direction the platform deploys in**: unknown JSON fields
are ignored and an unknown enum constant reads as null (`ContainersJson`, which says what that
costs). The service ships first; a client that refused a body it did not fully recognise would
break every consumer on the day the service was deployed.

**3. Four answers, and the last two never merge.** `ContainersAnswer` is a sealed interface —
`Created`, `Ready`, `Refused`, `Unreachable` — and it carries **no `retryable()`**, for the reason
`EventsPublisher.Delivery` carries none. A 2xx whose body will not bind is a `Refused` with
`UNREADABLE` on it and never an `Unreachable`: something answered, so the evidence is about the
response. Nothing throws; a throw would be a fifth answer with no place in the four, arriving on the
caller's own worker thread.

**Where the client's tests live is decided by what they can see.** The client module's suite is
pure unit tests against a JDK `HttpServer` stub — outcome mapping, URL building, header stamping,
the HTTP/1.1 pin — with no application, no database and no docker. The pairing with the real routes
is `service`'s `ContainersClientWireTest`, because test scope on the deployable is the only
classpath both jars may share. **The HTTP/1.1 pin is asserted on the client's configuration, not on
a response**: `HttpResponse.version()` reports what the two ends negotiated, and every server this
client meets speaks HTTP/1.1, so that assertion would pass with the `version(...)` line deleted.

## The pipelines, and the one thing that is new about them

`README.md`'s "Deploying it" has the shape. Two things about `.config/qits/` are this file's.

**Both pipelines are two steps, and no build image would let them be one.** A `./mvnw` needs a JDK
and `ci-base` is `docker:cli` plus bash/curl/git/jq; `maven-base` carries no docker CLI. So the
suite runs on one image and the image build on the other. Each step is its own container with its
own clone, which is why the release pipeline's two steps each read the version and check out the tag
rather than one doing it for both.

**`.config/qits/ci-event-release.yml` is the platform's FIRST dual maven+docker release**, and the
probe-skip semantics are the part to get right. `artifacts:` declares three things — the two library
jars and the image — because a declaration is a claim about what **this script pushes**, and qits-ci
announces one `SoftwareRelease` per entry without being able to see what a step really did.
`qits-containers-service` is not declared, because nothing uploads it; the deployable ships as the
image. qits-githost's file is the same rule pointing the other way and is worth reading beside this
one: it publishes library jars, does not push them from its release pipeline, and therefore does not
declare them.

The jar step is skip-if-published, and **the two probes are AND-chained on purpose**. `deploy` runs
across one reactor and ships both modules together, so a version with one module missing has to go
again whole; an `||` there would skip on a half-published version and leave it half-published
forever. The probes read the module poms and not the root: the root can only be absent if the run
never reached either module, which the two probes already say.

**`-pl .,core,client` — the root rides along, and `-N` is the wrong tool.** Both module poms declare
this repository's root as their `<parent>`, so a consumer resolving either jar resolves that parent
pom first; deploying the modules alone publishes jars whose parent exists in no registry, and the
failure lands on the consumer's build. `-N` means "do not recurse into `<modules>`", so it would
produce the mirror-image failure — the parent pom with neither jar. `-pl` is already the selection
that is wanted.
