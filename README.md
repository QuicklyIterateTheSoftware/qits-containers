# qits-containers

The platform's container orchestrator: one service that starts, stops and remembers every container
the platform runs for itself.

Five modules shell out to the docker CLI today — qits-ci's pipeline steps, qits-workspaces'
workspaces, qits-projects' refinement agents, and two more — each with its own registry, its own
labels, and its own answer to what happens to a running container when the module restarts. This
service is the one place that answers: **a durable row is written before the container is started, a
restart adopts what is still running, and no code path removes a container that no row names.**

    ./mvnw verify                  # a clone alone, green — no monorepo, no docker, no credentials
    ./mvnw verify -DskipITs=false  # adds the packaged surface, and the real-docker adoption proof

## Layout

| Module | What |
|---|---|
| `core/` | The domain: the registry rows, the workload spec and its lifecycle policies, the docker seam. A library jar. It owns the datasource, the persistence unit and the Flyway lineage. |
| `client/` | What a consumer depends on to **call** this service: the wire records and the HttpClient behind them. It depends on `core` **not at all**. |
| `service/` | The deployable: the REST surface under `/containers/api`, the real docker driver (`dockerhost/`), the machine guard, the boot steps. |

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

## The surface

Everything lives under `/containers/api`, and a **place** is `{owner}/{workload}/{ref}`.

    PUT    /containers/{owner}/{workload}/{ref}      {spec, policy, recreate}  -> the place
    GET    /containers/{owner}/{workload}/{ref}      the place, 404 only when no row names it
    GET    /containers/{owner}[/{workload}]          this owner's places, from the ROWS
    POST   /containers/{owner}/{workload}/{ref}/stop | /touch
    GET    /containers/{owner}/{workload}/{ref}/logs?tail=N     bounded, and works while EXITED
    DELETE /containers/{owner}/{workload}/{ref}?volumes=&logs=  idempotent; the tail comes back
    DELETE /containers/{owner}/{workload}?createdBefore=<ISO>   the boot reap; the instant is REQUIRED
    PUT|GET|DELETE /volumes/{owner}/{name}

One envelope answers about a place: `{id, containerName, state:{desired,observed}, endpoint:{…},
specHash, created}`. `endpoint.proxy` is null today and is there because the data plane arrives
behind it. Errors are typed: 409 `SPEC_CONFLICT` for a recreate a run-once policy cannot answer,
409 `IMAGE_MISSING` for an image nothing published, 400 `INVALID` for a value that will not go into
an argv. **A failed read is a 5xx and never a 404** — a caller that read 404 would conclude its
workload was never started, and start a second one.

**`createdBefore` being required is the boot reap's whole shape.** An owner passes the instant it
came up, so what it started afterwards — including while the sweep runs — is not in the set.

### Who may call

Every route, reads included. The rest of the platform guards its writes and leaves its reads open
because a person reads through the gateway; nothing here is read by a person, and an inventory of
running containers is as much a module's own as the containers are.

The `{owner}` in the path must be the machine token's **subject, whole**: qits-idp mints
`dev-qits-ci` and `prod-qits-ci` as two client ids, and that environment prefix is exactly what
keeps two environments sharing one docker daemon out of each other's rows. Until the platform-wide
gate `qits.auth.machine.required` is on — it ships off, as it does everywhere — the path owner is
trusted and no bearer is needed.

## What is deliberately *not* here yet

- **The client.** The module exists so the boundary is stated before there is code to bend it.
- **The data plane.** The reverse tunnels two other modules carry today centralize here eventually.
- **The consumers.** qits-ci, qits-workspaces and qits-projects still run their own containers; the
  point of this service is that they stop, one at a time.

## What a deployment must set

| Env | Why it is not defaulted |
|---|---|
| `QITS_RESOURCE_DB_URL` / `_USERNAME` / `_PASSWORD` | The registry. Nothing is defaulted, so an unset triple is a boot failure at Flyway rather than an orchestrator answering from a database nobody meant. On the platform these are not set by hand: `.config/qits/deployments.yml` declares `resources: postgresql:db` and qits-deployments injects all three before the container starts. |
| `QITS_RESOURCE_EVENTSTREAM_URL` / `_USERNAME` / `_PASSWORD` | The event bus client's own store, on the same contract — `resources: postgresql:eventstream`. The resource must be named `eventstream`, because the variable names follow the resource name. |

**Refusing to boot without a database is deliberate.** The rows are the only record of which
containers may exist. An orchestrator that came up on an empty store it invented would see every
running container as named by no row — which is precisely the state the adoption rule exists to
prevent it from acting on.

Everything else has a shipped default and a deployment overrides what it means to:
`QITS_AUTH_MACHINE_REQUIRED=true` with `QITS_AUTH_MACHINE_AUDIENCE=<env>-qits-containers` turns the
gate on; `QITS_CONTAINERS_INSTANCE` distinguishes two instances in `docker ps`;
`QITS_CONTAINERS_NETWORK` and `QITS_CONTAINERS_SHARED_VOLUMES` name what the boot step makes sure
of. The container needs the docker socket, and it is the deployment that grants it — nothing here
mounts one for itself.

### What a boot does, in order

1. **`SharedResources`** makes the three shared volumes (`qits_shared_*`) and asks whether
   `qits-net` is there. **It never creates a network**: one invented here would be a network no
   other module's containers are on, and a bridge cannot be created on a swarm host at all.
2. **`BootSweep`** adopts what is still running, settles what stopped per policy, and replays a
   delete that never finished.
3. **`ContainerObserver`** starts its ticker — last, by CDI priority, so no observation pass meets
   an in-flight row before the sweep has decided about it.

None of the three fails a boot. A host that has just rebooted has this service up before its docker,
and an orchestrator that refused to start because it could not reach docker would be one that could
not be deployed to fix docker.

### The health probe

    GET /containers/q/health/ready     the gate; UP only when both stores are reachable
    GET /containers/q/health/live      the process is running

Readiness, not liveness: quarkus-agroal contributes a check per datasource, so a container that
booted and cannot reach its registry is red.
