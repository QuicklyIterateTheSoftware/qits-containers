# qits-containers

The platform's container orchestrator: one service that starts, stops and remembers every container
the platform runs for itself.

Five modules shell out to the docker CLI today — qits-ci's pipeline steps, qits-workspaces'
workspaces, qits-projects' refinement agents, and two more — each with its own registry, its own
labels, and its own answer to what happens to a running container when the module restarts. This
service is the one place that answers: **a durable row is written before the container is started, a
restart adopts what is still running, and no code path removes a container that no row names.**

    ./mvnw verify     # a clone of this repo alone builds and tests green — no monorepo, no docker

## Layout

| Module | What |
|---|---|
| `core/` | The domain: the registry rows, the workload spec and its lifecycle policies, the docker seam. A library jar. It owns the datasource, the persistence unit and the Flyway lineage. |
| `client/` | What a consumer depends on to **call** this service: the wire records and the HttpClient behind them. It depends on `core` **not at all**. |
| `service/` | The deployable: the REST surface under `/containers/api`, the real docker driver, the sweeps. |

The directories are short and the artifactIds are namespaced (`qits-containers-*`): generic
coordinates like `eu.wohlben:core` would collide in the shared `~/.m2` that every workspace
container mounts.

`client/` not depending on `core` is the boundary worth defending. A caller wants a few records and
an HTTP client; `core` carries entities, a Flyway lineage and datasource defaults at ordinal 100,
and every one of those would arrive in qits-ci, qits-workspaces and qits-projects the day they
depend on it.

## The boundary

**qits-containers runs containers on behalf of an owner. It decides nothing about what should
run.** A caller says which workload belongs at which place, and under which lifecycle policy; the
image, the command and the schedule are the caller's knowledge and stay there.

Two things are deliberately outside it:

- **Deployed applications.** qits-platform-deployments owns those, and it must keep owning them: the
  deployer has to survive the platform being down, including this service.
- **Anything a container talks to.** Containers dial out to stable DNS aliases and re-dial on their
  own, which is what makes a restart of this service invisible to traffic that is already flowing.

## The registry, and what a restart does

`core/control` is the state machine, and it is proved end to end against a scripted driver rather
than a daemon.

- **`ContainerRegistry`** — `ensure`, `stop`, `touch`, `logs`, `delete`, `destroyAll`. Every one of
  them writes the row before it calls docker, and no transaction spans a docker call. `ensure`
  stores the spec **without its environment** and hashes a canonical form **with** it, so a rotated
  credential is a change the registry can see without ever having stored one. A run docker refuses
  is re-inspected: a container carrying the row's own name is a previous attempt that died before
  recording itself, and it is adopted rather than replaced.
- **`BootSweep`** — a restart adopts what is still running, settles what stopped according to its
  policy, and replays a delete that never finished. Docker being down at boot is a warning and not a
  failed start.
- **`ContainerObserver`** — one ticker, one `ct-worker`, rows only. A running workload is demoted
  after two consecutive passes agree its container is gone; one that comes back recovers, with the
  original failure text kept under the recovery. `IdleSweep`, `VolumeReconcile`, `MaxAgeGc` and
  `RowPrune` run on the same worker, so there is one concurrency model rather than five.

**`destroyAll` is what a consumer's boot reap becomes**: an owner's own rows, filtered by
`createdBefore`. Never a listing by label — that is the reap this repository exists to remove.

## What is deliberately *not* here yet

It builds, boots, migrates its schema, serves its health probe and reconciles its registry. What is
missing is everything that makes it reachable and everything that makes it real:

- **The real docker driver.** The seam (`control/ContainersDriver`) and its scripted fake exist, and
  a build that wires no implementation gets `UnwiredContainersDriver`, which refuses every call
  rather than quietly answering "done".
- **The REST surface.** `quarkus.rest.path` is set and no resource answers under it, so nothing
  outside this process can reach the registry yet.
- **The client.** The module exists so the boundary is stated before there is code to bend it.
- **The data plane.** The reverse tunnels two other modules carry today centralize here eventually.

## What a deployment must set

| Env | Why it is not defaulted |
|---|---|
| `QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD` | The registry. Nothing is defaulted, so an unset triple is a boot failure at Flyway rather than an orchestrator answering from a database nobody meant. On the platform these are not set by hand: `.config/qits/deployments.yml` declares `resources: postgresql:db` and qits-deployments injects all three before the container starts. |
| `QITS_RESOURCE_EVENTSTREAM_URL` / `_USERNAME` / `_PASSWORD` | The event bus client's own store, on the same contract — `resources: postgresql:eventstream`. The resource must be named `eventstream`, because the variable names follow the resource name. |

**Refusing to boot without a database is deliberate.** The rows are the only record of which
containers may exist. An orchestrator that came up on an empty store it invented would see every
running container as named by no row — which is precisely the state the adoption rule exists to
prevent it from acting on.

### The health probe

    GET /containers/q/health/ready     the gate; UP only when both stores are reachable
    GET /containers/q/health/live      the process is running

Readiness, not liveness: quarkus-agroal contributes a check per datasource, so a container that
booted and cannot reach its registry is red.
