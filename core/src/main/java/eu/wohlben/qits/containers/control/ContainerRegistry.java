package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.CtVolume;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.entity.VolumeState;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.containers.persistence.CtVolumeRepository;
import eu.wohlben.qits.containers.spec.ContainerLabels;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.spec.VolumeSpec;
import eu.wohlben.qits.db.DbRetry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The registry: the state machine that turns an owner's ask into a container, and a durable row into
 * the only record of which containers may exist.
 *
 * <p><b>The row is written before the container is started, and that ordering is the repository.</b>
 * {@link #ensure} commits a row carrying the chosen {@code container_name} and {@code PENDING}
 * before it calls {@code docker run}; a crash anywhere after that leaves a container the registry
 * can still name. Everything else here — the boot sweep, the observer, the policy sweeps — is
 * downstream of that one guarantee, and none of them may remove a container that no row names.
 *
 * <p><b>No transaction ever spans a docker call.</b> A docker call is a child process on the far
 * side of a socket, so each state transition sits in its own bracket and the driver is called
 * between brackets, on plain values copied out of the entities. That is the {@code DeployService}
 * shape and it is not negotiable: a transaction held across a daemon that stopped answering is a
 * connection held for the length of a timeout.
 *
 * <p><b>Which spelling of {@link DbRetry} each bracket takes is decided by who owns the
 * transaction.</b> A read brackets itself and is wrapped in {@link DbRetry#call}; a state transition
 * <em>is</em> a {@link DbRetry#inNewTx}, because owning the boundary is what lets the retry tell "the
 * body threw it, so it certainly never committed" from "the transaction manager reported it" — and
 * every one of those bodies ends in a {@code flush()}, which is what moves the write into the
 * statement phase where that classification is certain. Without the flush the wrap reports rather
 * than helps.
 *
 * <p><b>The budget is {@link #CUTOVER_BUDGET} rather than the library default</b>, for the reason
 * qits-platform-deployments spends thirty seconds: this service will one day be reconciling rows
 * while the platform cuts its own postgres over, and the bookkeeping that runs <em>after</em> a
 * container is running is exactly the work whose loss leaves a live container with no row that
 * admits it.
 *
 * <p>Every operation is owner-scoped by its parameters. There is no ambient identity here and no
 * authorization: WP4's REST layer derives the owner from the caller's machine token and passes it
 * in, which is what keeps this class testable without one.
 */
@ApplicationScoped
public class ContainerRegistry {

  private static final Logger LOG = Logger.getLogger(ContainerRegistry.class);

  /**
   * How long a self-inflicted database blip may last before it is a failure worth reporting — longer
   * than {@link DbRetry#DEFAULT_DEADLINE}, and stated at every call site here rather than taken from
   * the library. Package-private because the sweeps and the observer wrap their brackets for the
   * same reason and one budget spelled twice would drift.
   */
  static final Duration CUTOVER_BUDGET = Duration.ofSeconds(30);

  /** Docker statuses that mean the container is not coming back on its own. */
  private static final List<String> TERMINAL_STATUSES = List.of("exited", "dead", "removing");

  private final ContainersDriver driver;
  private final CtContainerRepository containers;
  private final CtVolumeRepository volumes;

  /**
   * The clock every state transition reads. Injected rather than called statically, because half of
   * what this service decides is about elapsed time and the only way to test that without sleeping
   * through it is to move the clock.
   *
   * <p><b>There is no producer for it here</b>, and that is deliberate: the qits-eventstream jar
   * ships a {@code @DefaultBean} {@code Clock} for the whole platform, and a second default producer
   * of the same type is an ambiguous resolution that fails the build — measured, 2026-08-11. Nothing
   * in {@code core} imports an eventstream type for it: {@link Clock} is a JDK type, so this is one
   * bean the platform already has rather than a widening of the narrow dependency AGENTS.md allows.
   */
  private final Clock clock;

  /**
   * Which run of this service started a container. A diagnostic label and never a filter a sweep
   * acts on — {@code ContainerLabels#INSTANCE} says why: adopting is the rule, so "started by an
   * earlier instance" must not be readable as "removable".
   */
  @ConfigProperty(name = "qits.containers.instance")
  String instanceId;

  @Inject
  public ContainerRegistry(
      ContainersDriver driver,
      CtContainerRepository containers,
      CtVolumeRepository volumes,
      Clock clock) {
    this.driver = driver;
    this.containers = containers;
    this.volumes = volumes;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------------------------
  // What an operation answers with. Plain records: nothing hands an entity across a bracket.
  // ---------------------------------------------------------------------------------------------

  /** What {@link #ensure} did, and where the place stands now. */
  public record Ensured(
      UUID rowId,
      String containerName,
      DesiredState desired,
      ObservedState observed,
      boolean created,
      String detail) {}

  /** What {@link #stop} did. */
  public record Stopped(
      UUID rowId, String containerName, ObservedState observed, boolean ok, String detail) {}

  /**
   * What {@link #delete} did. {@code existed} is false for a place that was already absent, which is
   * a <b>success</b>: a delete is idempotent, and "there was nothing to remove" is the state the
   * caller asked for.
   */
  public record Deleted(
      UUID rowId, String containerName, boolean existed, String logs, String detail) {}

  /** One place's outcome inside a {@link #destroyAll}. */
  public record Destroyed(String ownerRef, String containerName, boolean removed, String detail) {}

  /** What the upsert decided, as plain values the docker phase can act on. */
  private enum Step {
    /** A new row and a first container. */
    START,
    /** The spec changed and the policy allows a replacement: stop, remove, run the same name. */
    REPLACE,
    /** The same spec, nothing running: start it again under the same name. */
    RESTART,
    /** Already running the spec that was asked for. Nothing to do and nothing to say. */
    ADOPTED,
    /** Something differs and the policy refuses to act on it. The row is left exactly as it was. */
    KEEP
  }

  private record Plan(
      UUID rowId,
      String containerName,
      Step step,
      List<String> ownVolumes,
      DesiredState desired,
      ObservedState observed,
      boolean created) {}

  // ---------------------------------------------------------------------------------------------
  // ensure
  // ---------------------------------------------------------------------------------------------

  /**
   * Put a container at this place, or confirm the one already there.
   *
   * <p>Three phases, and the boundaries between them are the design:
   *
   * <ol>
   *   <li><b>One transaction upserts the row</b> with the chosen name, the persisted spec and its
   *       hash, {@code desired=RUNNING} and {@code observed=PENDING}, plus a {@link CtVolume} row
   *       per volume this workload owns. It commits before anything docker-side happens.
   *   <li><b>Outside every transaction</b>, the volumes are created, the image is pulled when the
   *       policy demands it, and the container is run.
   *   <li><b>Two further transactions settle the row</b>: {@code STARTING} once docker accepted the
   *       run, then whatever an inspect confirms.
   * </ol>
   *
   * <p><b>The pull policy is answered by one call and one fallback, and the choice is recorded
   * here.</b> {@code ALWAYS} is an explicit {@code docker pull} before the run, so "the registry has
   * no such image" is its own recorded outcome rather than a failed run. {@code MISSING} and
   * {@code NEVER} pull nothing: docker's own {@code run} fetches an image the host does not have,
   * which is exactly {@code MISSING}'s meaning, and {@code NEVER} is honoured by the argv the driver
   * renders. Asking the daemon whether an image is present, to decide whether to ask it to fetch
   * one, would be a second round trip that answers a question the run already answers.
   *
   * <p><b>A run docker refuses is re-inspected before it is recorded as a failure</b>, and that is
   * the crash-retry idempotency this whole ordering exists for. The row named the container before
   * the run, so a container carrying that exact name <em>is</em> ours — a previous attempt that
   * started it and died before recording the fact. It is adopted, not replaced. The check is a
   * re-inspect rather than a match against docker's refusal text, because the wording of a
   * duplicate-name refusal is the CLI's to change and the name is ours.
   *
   * @param recreateIfChanged whether a spec change may replace the running container. False leaves
   *     what is running alone and reports it, which is what an owner that only wants the place
   *     occupied asks for.
   * @throws SpecConflictException when a replacement is asked for and the policy forbids one
   */
  public Ensured ensure(
      String owner,
      String workload,
      String ownerRef,
      ContainerSpec spec,
      LifecyclePolicy policy,
      boolean recreateIfChanged) {
    ContainersIdentifiers.requireOwner(owner);
    ContainersIdentifiers.requireWorkload(workload);
    ContainersIdentifiers.requireRef(ownerRef);
    String place = place(owner, workload, ownerRef);

    Plan plan =
        DbRetry.inNewTx(
            "The registry upsert of " + place,
            () -> upsert(owner, workload, ownerRef, spec, policy, recreateIfChanged),
            CUTOVER_BUDGET);

    if (plan.step() == Step.ADOPTED || plan.step() == Step.KEEP) {
      LOG.debugf("%s is already what was asked for (%s); nothing docker-side to do", place, plan.step());
      return new Ensured(
          plan.rowId(),
          plan.containerName(),
          plan.desired(),
          plan.observed(),
          false,
          plan.step() == Step.KEEP ? "[left as it is: the running spec differs and no recreate was asked for]" : null);
    }

    String name = plan.containerName();

    // From here to the end of the method, no transaction is open. Every call below is a child
    // process on the far side of a socket.
    if (plan.step() == Step.REPLACE) {
      driver.stop(name, ContainersTimeouts.STOP);
      driver.remove(name, ContainersTimeouts.REMOVE);
    }
    for (String volume : plan.ownVolumes()) {
      driver.ensureVolume(
          new VolumeSpec(volume),
          ContainerLabels.forVolume(owner, workload, ownerRef),
          ContainersTimeouts.VOLUME);
    }
    if (spec.pullPolicy() == ContainerSpec.PullPolicy.ALWAYS) {
      ContainersDriver.OpResult pulled =
          driver.pull(spec.image(), ContainersTimeouts.PULL, ContainersTimeouts.PULL_MAX_CHARS);
      if (!pulled.ok()) {
        LOG.warnf(
            "Could not pull %s for %s: %s — running anyway, so the run's own failure is the record",
            spec.image(), place, Details.brief(pulled.detail()));
      }
    }

    Map<String, String> labels =
        ContainerLabels.forContainer(owner, workload, ownerRef, plan.rowId().toString(), instanceId);
    ContainersDriver.Started started = driver.run(spec, name, labels, policy, ContainersTimeouts.RUN);

    if (!started.started()) {
      Optional<ContainersDriver.Observed> existing =
          driver.inspect(name, ContainersTimeouts.INSPECT);
      if (existing.isPresent() && running(existing.get())) {
        settle(
            plan.rowId(),
            ObservedState.RUNNING,
            "[adopted after a refused run: " + name + " is already running and this row names it]");
        LOG.infof("Adopted %s: docker refused a second run of a container this row already names", name);
        return new Ensured(
            plan.rowId(), name, DesiredState.RUNNING, ObservedState.RUNNING, plan.created(), null);
      }
      String detail = "[docker refused to start " + name + ": " + Details.brief(started.detail()) + "]";
      settle(plan.rowId(), ObservedState.MISSING, detail);
      LOG.warnf("Could not start %s for %s: %s", name, place, Details.brief(started.detail()));
      return new Ensured(
          plan.rowId(), name, DesiredState.RUNNING, ObservedState.MISSING, plan.created(), detail);
    }

    settle(plan.rowId(), ObservedState.STARTING, null);
    ObservedState observed = observedOf(driver.inspect(name, ContainersTimeouts.INSPECT));
    settle(plan.rowId(), observed, null);
    return new Ensured(
        plan.rowId(), name, DesiredState.RUNNING, observed, plan.created(), null);
  }

  /**
   * The whole decision, inside one transaction. It reads the live row of the place, works out which
   * {@link Step} the ask amounts to, writes the row that {@code docker run} will be measured against
   * and returns plain values.
   */
  private Plan upsert(
      String owner,
      String workload,
      String ownerRef,
      ContainerSpec spec,
      LifecyclePolicy policy,
      boolean recreateIfChanged) {
    Instant now = clock.instant();
    String json = SpecFingerprint.persistedJson(spec);
    String hash = SpecFingerprint.hash(spec);
    List<String> ownVolumes =
        spec.volumeMounts().stream().map(ContainerSpec.VolumeMount::volumeName).distinct().toList();

    CtContainer row = containers.findLive(owner, workload, ownerRef);
    boolean created = row == null;
    Step step;
    if (created) {
      row = new CtContainer();
      row.id = UUID.randomUUID();
      row.owner = owner;
      row.workload = workload;
      row.ownerRef = ownerRef;
      row.containerName =
          spec.explicitName().isEmpty()
              ? ContainerNames.of(owner, workload, ownerRef)
              : spec.explicitName();
      row.createdAt = now;
      step = Step.START;
    } else if (hash.equals(row.specHash)) {
      // The same workload. Whether anything has to happen depends only on what is running — a row
      // that was stopped and is asked for again is asked to run again, which is what ensure means.
      if (row.observedState == ObservedState.RUNNING || row.observedState.inFlight()) {
        step = Step.ADOPTED;
      } else if (policy.recreatable()) {
        step = Step.RESTART;
      } else {
        // An EPHEMERAL workload that already ran. Its exit IS the success path, and a second
        // container would do the work twice.
        step = Step.KEEP;
      }
    } else if (!recreateIfChanged) {
      step = Step.KEEP;
    } else if (!policy.recreatable()) {
      throw new SpecConflictException(
          "Cannot recreate "
              + place(owner, workload, ownerRef)
              + ": its lifecycle policy is "
              + policy.type()
              + ", which runs once and exits — a replacement would do the work a second time");
    } else {
      step = Step.REPLACE;
    }

    if (step == Step.START || step == Step.REPLACE || step == Step.RESTART) {
      row.image = spec.image();
      row.specJson = json;
      row.specHash = hash;
      row.policy = policy.type();
      row.idleAfterS = policy.idleAfter() == null ? null : policy.idleAfter().toSeconds();
      row.maxAgeS = policy.maxAge() == null ? null : policy.maxAge().toSeconds();
      row.desiredState = DesiredState.RUNNING;
      row.observedState = ObservedState.PENDING;
    }
    row.updatedAt = now;
    if (created) {
      containers.persist(row);
    }
    if (step != Step.KEEP && step != Step.ADOPTED) {
      for (String volume : ownVolumes) {
        upsertVolumeRow(owner, workload, ownerRef, volume, now);
      }
    }
    // Flushed rather than left to the commit: an ORM flushes at commit by default, which would put
    // every statement on the far side of the one round trip nothing can place. Flushed, a lost
    // connection is a body failure — certainly not committed, so safe to run again.
    containers.flush();
    return new Plan(
        row.id, row.containerName, step, ownVolumes, row.desiredState, row.observedState, created);
  }

  /** The converging volume row. Written before the volume exists, exactly as the container's is. */
  private void upsertVolumeRow(
      String owner, String workload, String ownerRef, String name, Instant now) {
    CtVolume row = volumes.findByOwnerAndName(owner, name);
    boolean fresh = row == null;
    if (fresh) {
      row = new CtVolume();
      row.id = UUID.randomUUID();
      row.owner = owner;
      row.name = name;
      row.createdAt = now;
    }
    row.labelsJson = SpecFingerprint.write(ContainerLabels.forVolume(owner, workload, ownerRef));
    row.desiredState = VolumeState.PRESENT;
    if (fresh) {
      // Persisted only once every column is set: this entity's id is assigned rather than
      // generated, so Hibernate is free to insert at persist() time and a field set afterwards
      // would be a not-null violation on a row that reads perfectly well in the debugger.
      volumes.persist(row);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // stop / touch / logs
  // ---------------------------------------------------------------------------------------------

  /**
   * Stop what is here, leaving it restartable. The desired state is written first, so a crash
   * between the row and the docker call leaves a row that says what was asked for.
   */
  public Stopped stop(String owner, String workload, String ownerRef) {
    String place = place(owner, workload, ownerRef);
    String name =
        DbRetry.inNewTx(
            "The stop of " + place,
            () -> {
              CtContainer row = containers.findLive(owner, workload, ownerRef);
              if (row == null) {
                return null;
              }
              row.desiredState = DesiredState.STOPPED;
              row.updatedAt = clock.instant();
              containers.flush();
              return row.containerName;
            },
            CUTOVER_BUDGET);
    if (name == null) {
      return new Stopped(null, "", null, true, "[nothing here to stop]");
    }

    ContainersDriver.OpResult result = driver.stop(name, ContainersTimeouts.STOP);
    if (!result.ok()) {
      String detail = "[docker could not stop " + name + ": " + Details.brief(result.detail()) + "]";
      UUID id = settleByName(name, null, detail);
      LOG.warnf("Could not stop %s for %s: %s", name, place, Details.brief(result.detail()));
      return new Stopped(id, name, null, false, detail);
    }
    UUID id = settleByName(name, ObservedState.EXITED, null);
    return new Stopped(id, name, ObservedState.EXITED, true, null);
  }

  /**
   * Record that the owner still wants this workload. One column, no docker call — an idle sweep
   * reads it and nothing else does.
   *
   * @return whether there was a live row to touch
   */
  public boolean touch(String owner, String workload, String ownerRef) {
    return DbRetry.inNewTx(
        "The touch of " + place(owner, workload, ownerRef),
        () -> {
          CtContainer row = containers.findLive(owner, workload, ownerRef);
          if (row == null) {
            return false;
          }
          Instant now = clock.instant();
          row.lastTouchedAt = now;
          row.updatedAt = now;
          containers.flush();
          return true;
        },
        CUTOVER_BUDGET);
  }

  /**
   * The tail of what the container printed. It works while the container is {@code EXITED}, which is
   * the case that matters: a workload that died on its first breath has nothing else to offer, and
   * that is the whole reason no argv here carries {@code --rm}.
   *
   * @param lines how many lines to ask for; anything at or below zero takes the default
   */
  public ContainersDriver.LogTail logs(String owner, String workload, String ownerRef, int lines) {
    String name = readContainerName(owner, workload, ownerRef);
    if (name == null) {
      return new ContainersDriver.LogTail("", false);
    }
    return driver.logsTail(
        name,
        lines <= 0 ? ContainersTimeouts.LOGS_DEFAULT_LINES : lines,
        ContainersTimeouts.LOGS,
        ContainersTimeouts.LOGS_MAX_CHARS);
  }

  // ---------------------------------------------------------------------------------------------
  // delete / destroyAll
  // ---------------------------------------------------------------------------------------------

  /**
   * Remove what is here.
   *
   * <p><b>Idempotent by construction.</b> A place with no live row is a success with
   * {@code existed=false}: the caller asked for nothing to be there and nothing is.
   *
   * <p><b>Logs are captured before the removal or they are lost with it.</b> That is the whole
   * ordering of the method, and it is what qits-ci's boot reap does today by hand.
   *
   * <p><b>The row is settled {@code GONE} only when the container is really gone</b> — the remove
   * reported success, or a follow-up inspect finds nothing. Anything else leaves an {@code ABSENT}
   * row that is not yet {@code GONE}, which is precisely the state {@link BootSweep} replays. A
   * delete that settled optimistically would abandon a container nothing would ever look at again.
   *
   * @param withVolumes remove the volumes this workload owns. Honoured only for policies that own
   *     them — an {@code IDLE_STOP} workload is stopped and never removed, so its volume is the
   *     thing it comes back to. Shared volumes are never touched: they are not this workload's.
   * @param withLogs capture a bounded tail before removing, and return it
   */
  public Deleted delete(
      String owner, String workload, String ownerRef, boolean withVolumes, boolean withLogs) {
    String place = place(owner, workload, ownerRef);
    Doomed doomed =
        DbRetry.inNewTx(
            "The delete of " + place,
            () -> {
              CtContainer row = containers.findLive(owner, workload, ownerRef);
              if (row == null) {
                return null;
              }
              row.desiredState = DesiredState.ABSENT;
              row.updatedAt = clock.instant();
              containers.flush();
              return new Doomed(row.id, row.containerName, row.policy, ownedVolumesOf(row));
            },
            CUTOVER_BUDGET);
    if (doomed == null) {
      return new Deleted(null, "", false, "", "[already absent]");
    }

    String logs = "";
    if (withLogs) {
      ContainersDriver.LogTail tail =
          driver.logsTail(
              doomed.containerName(),
              ContainersTimeouts.LOGS_DEFAULT_LINES,
              ContainersTimeouts.LOGS,
              ContainersTimeouts.LOGS_MAX_CHARS);
      logs = tail.text();
    }

    ContainersDriver.OpResult removed =
        driver.remove(doomed.containerName(), ContainersTimeouts.REMOVE);
    boolean gone =
        removed.ok() || driver.inspect(doomed.containerName(), ContainersTimeouts.INSPECT).isEmpty();

    if (withVolumes && ownsVolumes(doomed.policy())) {
      for (String volume : doomed.ownVolumes()) {
        ContainersDriver.OpResult dropped = driver.removeVolume(volume, ContainersTimeouts.VOLUME);
        if (dropped.ok()) {
          markVolumeAbsent(owner, volume);
        } else {
          LOG.warnf(
              "Could not remove the volume %s of %s: %s",
              volume, place, Details.brief(dropped.detail()));
        }
      }
    }

    String detail =
        gone ? null : "[docker could not remove " + doomed.containerName() + ": "
            + Details.brief(removed.detail()) + "]";
    if (gone) {
      settle(doomed.rowId(), ObservedState.GONE, null);
    } else {
      settle(doomed.rowId(), null, detail);
      LOG.warnf(
          "%s is still on the host after a delete; the next boot sweep replays it",
          doomed.containerName());
    }
    return new Deleted(doomed.rowId(), doomed.containerName(), true, logs, detail);
  }

  /**
   * Remove every one of an owner's workloads of this kind that was created before an instant.
   *
   * <p><b>It iterates the owner's ROWS and never a label listing</b>, which is the whole difference
   * between this and the host-wide reap it replaces. qits-ci's boot sweep removes every container
   * carrying its label on the daemon it talks to, so two instances sharing one docker daemon reap
   * each other's running steps; scoped to rows, an instance can only reach containers its own
   * registry named.
   *
   * <p>{@code createdBefore} is what makes it a boot reap rather than a purge: an owner restarting
   * passes the instant it came up, so workloads it started <em>after</em> that — including ones
   * started while the sweep runs — are not in the set.
   */
  public List<Destroyed> destroyAll(String owner, String workload, Instant createdBefore) {
    List<String> refs =
        read(
            "The destroy-all listing of " + owner + "/" + workload,
            () ->
                containers.listLive(owner, workload).stream()
                    .filter(row -> row.createdAt.isBefore(createdBefore))
                    .map(row -> row.ownerRef)
                    .toList());
    List<Destroyed> outcomes = new ArrayList<>(refs.size());
    for (String ref : refs) {
      // No logs: a destroy-all is a sweep, and nobody is holding a connection waiting to read them.
      // No volumes either — taking a volume is an explicit ask about one workload.
      Deleted deleted = delete(owner, workload, ref, false, false);
      outcomes.add(
          new Destroyed(ref, deleted.containerName(), deleted.detail() == null, deleted.detail()));
    }
    if (!outcomes.isEmpty()) {
      LOG.infof(
          "Destroyed %d %s/%s workload(s) created before %s", outcomes.size(), owner, workload,
          createdBefore);
    }
    return List.copyOf(outcomes);
  }

  // ---------------------------------------------------------------------------------------------
  // The seams the sweeps share
  // ---------------------------------------------------------------------------------------------

  /**
   * Write a row's observed state and append to its detail, in its own retried transaction. Either
   * argument may be null: a state with no detail is an ordinary transition, and a detail with no
   * state is a note about an attempt that changed nothing.
   */
  void settle(UUID rowId, ObservedState observed, String detail) {
    DbRetry.runInNewTx(
        "The settle of container row " + rowId,
        () -> {
          CtContainer row = containers.findById(rowId);
          if (row == null) {
            return; // deleted under us; there is nothing left to say about it
          }
          if (observed != null) {
            row.observedState = observed;
          }
          row.detail = Details.append(row.detail, detail);
          row.updatedAt = clock.instant();
          containers.flush(); // statement phase, so a lost connection is retriable — see the class doc
        },
        CUTOVER_BUDGET);
  }

  /** {@link #settle} addressed by container name, for the paths that only carry the name. */
  private UUID settleByName(String containerName, ObservedState observed, String detail) {
    return DbRetry.inNewTx(
        "The settle of " + containerName,
        () -> {
          CtContainer row = containers.findByContainerName(containerName);
          if (row == null) {
            return null;
          }
          if (observed != null) {
            row.observedState = observed;
          }
          row.detail = Details.append(row.detail, detail);
          row.updatedAt = clock.instant();
          containers.flush();
          return row.id;
        },
        CUTOVER_BUDGET);
  }

  /** A read, bracketed by itself and retried. See the class javadoc on the two spellings. */
  <T> T read(String what, Supplier<T> body) {
    return DbRetry.call(
        what, () -> QuarkusTransaction.requiringNew().call(body::get), CUTOVER_BUDGET);
  }

  private String readContainerName(String owner, String workload, String ownerRef) {
    return read(
        "The container-name read of " + place(owner, workload, ownerRef),
        () -> {
          CtContainer row = containers.findLive(owner, workload, ownerRef);
          return row == null ? null : row.containerName;
        });
  }

  private void markVolumeAbsent(String owner, String name) {
    DbRetry.runInNewTx(
        "The volume settle of " + owner + "/" + name,
        () -> {
          CtVolume row = volumes.findByOwnerAndName(owner, name);
          if (row != null) {
            row.desiredState = VolumeState.ABSENT;
          }
          volumes.flush();
        },
        CUTOVER_BUDGET);
  }

  /** The volume names a row's own workload owns. Shared mounts are excluded and always will be. */
  static List<String> ownedVolumesOf(CtContainer row) {
    try {
      return SpecFingerprint.fromPersistedJson(row.specJson).volumeMounts().stream()
          .map(ContainerSpec.VolumeMount::volumeName)
          .distinct()
          .toList();
    } catch (RuntimeException e) {
      LOG.warnf(
          "Could not read the stored spec of %s, so no volume of it is claimed: %s",
          row.containerName, e.getMessage());
      return List.of();
    }
  }

  /**
   * Whether a delete of this policy's workload may take the workload's volumes with it.
   *
   * <p>{@code IDLE_STOP} is the one that may not. Its container is stopped and never removed by
   * design, so its volume is exactly the state it comes back to; taking it would make an idle stop
   * indistinguishable from a delete. The decision lives here rather than on {@link LifecyclePolicy}
   * because it is a statement about what a <em>delete</em> does, not about what a policy renders.
   */
  static boolean ownsVolumes(LifecyclePolicy.Type policy) {
    return policy != LifecyclePolicy.Type.IDLE_STOP;
  }

  /** Whether an observation says the container is up. */
  static boolean running(ContainersDriver.Observed observed) {
    return status(observed).equals("running");
  }

  /** Whether an observation says it is not coming back on its own. */
  static boolean terminal(ContainersDriver.Observed observed) {
    return TERMINAL_STATUSES.contains(status(observed));
  }

  private static String status(ContainersDriver.Observed observed) {
    return observed == null || observed.status() == null
        ? ""
        : observed.status().strip().toLowerCase(Locale.ROOT);
  }

  /**
   * What one inspect means for a row. An absent answer is {@code MISSING} — docker having no such
   * container is a different statement from an unhealthy one, and the two must never be merged.
   * Anything docker still has a state for and that is not terminal is {@code STARTING}: created,
   * restarting and paused are all "not settled yet", and a restart loop coming back from a slow
   * first boot must not be recorded as an exit on the way.
   */
  static ObservedState observedOf(Optional<ContainersDriver.Observed> observed) {
    if (observed.isEmpty()) {
      return ObservedState.MISSING;
    }
    if (running(observed.get())) {
      return ObservedState.RUNNING;
    }
    return terminal(observed.get()) ? ObservedState.EXITED : ObservedState.STARTING;
  }

  static String place(String owner, String workload, String ownerRef) {
    return owner + "/" + workload + "/" + ownerRef;
  }

  /** What a delete carries across its docker phase. Plain values, never an entity. */
  private record Doomed(
      UUID rowId, String containerName, LifecyclePolicy.Type policy, List<String> ownVolumes) {}
}
