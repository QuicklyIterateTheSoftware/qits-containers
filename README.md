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

## What is deliberately *not* here yet

This repository is a scaffold. It builds, boots, migrates its schema and serves its health probe;
everything below is a later work package, in roughly this order:

- **The docker driver and the spec vocabulary.** No `ProcessBuilder`, no argv, no labels yet.
- **The REST surface.** `quarkus.rest.path` is set and no resource answers under it.
- **The registry behaviour** — the boot sweep that adopts, the observer that reconciles, the policy
  sweeps.
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
