package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.spec.VolumeSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The seam between this service's orchestration and the host's docker daemon — the {@code
 * DeploymentDriver} / {@code CiStepRunner} arrangement: {@code core} owns the interface and
 * everything that calls it, {@code service/dockerhost} owns the sole production implementation
 * (shelling the docker CLI through {@link eu.wohlben.qits.containers.docker.ContainerProcess}), and
 * the suites install a scripted fake so a clone's {@code ./mvnw verify} needs no docker.
 *
 * <p>Everything crossing this seam is names, specs and references — never entities. The driver knows
 * nothing about registry rows, owners or sweeps; it starts, watches and removes containers, and it
 * makes and lists volumes.
 *
 * <p><b>Every container-touching method takes an explicit {@link Duration} timeout, and that is the
 * interface making a deployment rule unavoidable rather than documenting one.</b> Patience is not
 * tuning here: a docker call with no deadline is a worker held forever by a daemon that stopped
 * answering, and this service has exactly one worker. A default would let a caller be untimed by
 * omission, and a timeout stored on the implementation would put the deadline out of reach of the
 * caller that knows what it can afford to wait for. So the parameter is there on every one of them,
 * including the ones that "cannot" block — {@link #selfContainerId()} is the sole exception and it
 * reads a file rather than a daemon.
 *
 * <p><b>The reads that can be unbounded take a bound too</b>, for the same reason and stated the
 * same way: {@link #logsTail} and {@link #pull} capture output an owner influences.
 *
 * <p><b>Nothing here removes a container by label.</b> {@link #listByLabels} narrows a listing; what
 * may be removed is what a registry row names, and the decision is the caller's.
 */
public interface ContainersDriver {

  /** Whether the container started, its docker id, and what docker said if it did not. */
  record Started(boolean started, String containerId, String detail) {}

  /**
   * One observation: the docker id, the {@code running}/{@code exited}/… status, the health
   * ({@code none} when the image declares no check), and when this run began.
   *
   * <p>An <b>absent</b> {@link Optional} from {@link #inspect} is docker having no such container.
   * That is a different statement from an unhealthy one and the two must never be merged: absent is
   * what a row's own container being gone looks like, and unhealthy is a container that is there.
   */
  record Observed(String id, String status, String health, Instant startedAt) {}

  /** Whether the call did what was asked, and what docker said if it did not. */
  record OpResult(boolean ok, String detail) {}

  /** A bounded tail of a container's own output, and whether the front of it was dropped. */
  record LogTail(String text, boolean truncated) {}

  /**
   * Start the container, detached, under this exact name. The row that names it was written first,
   * so a crash between this call and its answer leaves a container the registry can still find.
   */
  Started run(
      ContainerSpec spec,
      String name,
      Map<String, String> labels,
      LifecyclePolicy policy,
      Duration timeout);

  /** One inspect. Empty when docker has no such container — see {@link Observed}. */
  Optional<Observed> inspect(String name, Duration timeout);

  /** Stop it, leaving it restartable. */
  OpResult stop(String name, Duration timeout);

  /** Remove it, running or not. Only ever for a container a row names. */
  OpResult remove(String name, Duration timeout);

  /** The tail of what the container printed — captured <b>before</b> any removal, or lost with it. */
  LogTail logsTail(String name, int lines, Duration timeout, int maxChars);

  /** Container ids carrying every one of these labels. A listing, never a licence to remove. */
  List<String> listByLabels(Map<String, String> filters, Duration timeout);

  /** Create the volume if it is absent, labelled. Idempotent — docker's own create is. */
  OpResult ensureVolume(VolumeSpec spec, Map<String, String> labels, Duration timeout);

  /** Remove the named volume. Always an explicit ask; nothing sweeps one. */
  OpResult removeVolume(String name, Duration timeout);

  /** Volume names carrying every one of these labels. */
  List<String> listVolumesByLabels(Map<String, String> filters, Duration timeout);

  /** Fetch the image, so a missing one is its own recorded outcome rather than a failed run. */
  OpResult pull(String imageRef, Duration timeout, int maxChars);

  /**
   * Whether the network exists. There is no create: a network this service invented would be one no
   * other module's containers are on, and a bridge cannot be created on a swarm host at all.
   */
  boolean networkPresent(String network, Duration timeout);

  /**
   * This process's own container id, blank when unknown. The one method with no timeout, because it
   * reads {@code /etc/hostname} rather than asking a daemon anything.
   */
  String selfContainerId();
}
