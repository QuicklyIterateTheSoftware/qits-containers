package eu.wohlben.qits.containers.docker;

import eu.wohlben.qits.containers.spec.ContainerLabels;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.ContainersIdentifiers;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.spec.VolumeSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every docker command line this service will ever run, as pure functions.
 *
 * <p>No I/O, no {@link ProcessBuilder}, no config, no clock — a spec goes in and a {@code List
 * <String>} comes out. That is what lets the argvs be asserted <b>element for element</b> in a
 * docker-free suite, and it is why they are assembled here rather than inside the driver: the argv
 * <b>is</b> the sandbox, and a flag lost in a refactor is invisible everywhere else until it is
 * invisible in production.
 *
 * <p><b>The belts run here too.</b> Validation at the API layer is the first checkpoint and this is
 * the second, unconditionally, on every value that reaches an element — because the API layer is one
 * loosened check away from being no checkpoint at all, and the point of two is that neither has to
 * be trusted alone.
 *
 * <p><b>{@code --rm} appears in nothing here, ever.</b> A self-removing container races the {@code
 * docker logs} capture that is the only diagnosis a container which died on its first breath can
 * offer, and it would delete a container whose registry row still names it — which is the one state
 * the adoption rule exists to make impossible. Every teardown is an explicit {@link #rm}.
 */
public final class DockerArgv {

  /**
   * The host docker socket, on both sides of the bind. It is a constant rather than a parameter for
   * the reason {@link ContainerSpec#hostDockerSocket()} is a boolean: what gets mounted must not be
   * something a caller chooses.
   */
  public static final String DOCKER_SOCKET = "/var/run/docker.sock";

  /**
   * The container state, as one line: {@code <status>/<health>}. Copied from
   * {@code DockerDeploymentDriver.observe} unchanged, including the {@code if}/{@code else} —
   * a bare {@code {{.State.Health.Status}}} prints Go's {@code <no value>} for an image that
   * declares no healthcheck, and {@code <no value>} would read back as a health state no container
   * has. The {@code else none} arm answers the absence as an absence.
   */
  public static final String STATE_FORMAT =
      "{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}";

  /** When the current run of the container began — what an adoption records as its start. */
  public static final String STARTED_AT_FORMAT = "{{.State.StartedAt}}";

  /**
   * A volume's labels, one {@code k=v} per line. <b>Ranged rather than indexed</b>, the same
   * measured reason the state format carries its {@code if}: on docker 29.5.3 an {@code index} of an
   * empty label map prints {@code <no value>}, which reads back as a label nothing set. A range over
   * an empty map prints nothing, which is what an unlabelled volume means.
   */
  public static final String VOLUME_LABELS_FORMAT =
      "{{range $k, $v := .Labels}}{{$k}}={{$v}}{{\"\\n\"}}{{end}}";

  private DockerArgv() {}

  /**
   * The whole {@code docker run}. Detached, never self-removing, and in a fixed order so the list is
   * assertable.
   *
   * <p>{@code labels} is this service's own namespace ({@link ContainerLabels#forContainer}); the
   * spec's {@code extraLabels} are merged in and the union is rendered sorted. The two cannot
   * collide — an owner may not write inside {@value ContainerLabels#NAMESPACE} — so the merge needs
   * no precedence rule and has none.
   */
  public static List<String> run(
      String runtimeBinary,
      String name,
      ContainerSpec spec,
      Map<String, String> labels,
      LifecyclePolicy policy) {
    ContainersIdentifiers.requireContainerName(name);
    ContainersIdentifiers.requireImage(spec.image());
    ContainersIdentifiers.requireNetwork(spec.network());

    List<String> argv = new ArrayList<>();
    argv.add(runtimeBinary);
    argv.add("run");
    argv.add("-d");
    argv.add("--name");
    argv.add(name);
    argv.add("--network");
    argv.add(spec.network());
    for (String alias : spec.aliases()) {
      argv.add("--network-alias");
      argv.add(ContainersIdentifiers.requireAlias(alias));
    }
    for (String addHost : spec.addHosts()) {
      argv.add("--add-host=" + ContainersIdentifiers.requireAddHost(addHost));
    }
    // Sorted, because the whole argv is asserted literally and a map's iteration order is not a
    // thing to assert against.
    for (Map.Entry<String, String> label : merged(labels, spec.extraLabels()).entrySet()) {
      argv.add("--label");
      argv.add(label.getKey() + "=" + label.getValue());
    }
    // The sandbox. Each flag is rendered only when the spec asked for it, so "unset" stays a
    // different statement from "off" — a spec that says nothing must not silently acquire a fence,
    // and a spec that dropped one must be readable as having dropped it.
    ContainerSpec.SecurityPosture security = spec.security();
    if (security.noNewPrivileges()) {
      argv.add("--security-opt=no-new-privileges");
    }
    if (security.capDropAll()) {
      argv.add("--cap-drop=ALL");
    }
    if (security.memory() != null) {
      argv.add("--memory");
      argv.add(security.memory());
    }
    if (security.memorySwap() != null) {
      argv.add("--memory-swap");
      argv.add(security.memorySwap());
    }
    if (security.pidsLimit() != null) {
      argv.add("--pids-limit");
      argv.add(String.valueOf(security.pidsLimit()));
    }
    if (security.cpus() != null) {
      argv.add("--cpus");
      argv.add(security.cpus());
    }
    // EPHEMERAL renders nothing here — see LifecyclePolicy, where the reason is argued.
    if (policy.restartsUnlessStopped()) {
      argv.add("--restart");
      argv.add("unless-stopped");
    }
    for (ContainerSpec.VolumeMount mount : spec.volumeMounts()) {
      argv.add("-v");
      argv.add(mount.volumeName() + ":" + mount.containerPath());
    }
    for (ContainerSpec.SharedMount mount : spec.sharedMounts()) {
      argv.add("-v");
      argv.add(mount.sharedName() + ":" + mount.containerPath());
    }
    // The one bind, and the only privilege escalation this service can perform. A container holding
    // it is root-equivalent on the host, so it is here because the spec DECLARED it — nothing else
    // in this method can add a mount, and no path anywhere comes from a caller.
    if (spec.hostDockerSocket()) {
      argv.add("-v");
      argv.add(DOCKER_SOCKET + ":" + DOCKER_SOCKET);
    }
    for (Map.Entry<String, String> variable : new TreeMap<>(spec.env()).entrySet()) {
      argv.add("-e");
      argv.add(ContainersIdentifiers.requireEnvKey(variable.getKey()) + "=" + variable.getValue());
    }
    // Docker's --entrypoint takes one word; a longer list spends its tail as leading arguments after
    // the image, which is the CLI's own convention (`--entrypoint /bin/sh img -c '…'`).
    List<String> entrypoint = spec.entrypoint();
    if (!entrypoint.isEmpty()) {
      argv.add("--entrypoint");
      argv.add(entrypoint.getFirst());
    }
    argv.add(spec.image());
    if (entrypoint.size() > 1) {
      argv.addAll(entrypoint.subList(1, entrypoint.size()));
    }
    argv.addAll(spec.args());
    return List.copyOf(argv);
  }

  /** One observation of the container's state — see {@link #STATE_FORMAT}. */
  public static List<String> inspectState(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        STATE_FORMAT,
        ContainersIdentifiers.requireContainerName(name));
  }

  /** When the container's current run started. */
  public static List<String> inspectStartedAt(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        STARTED_AT_FORMAT,
        ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * The whole of one observation, in one line: {@code <id>|<status>/<health>|<startedAt>}.
   *
   * <p>It is one call rather than three because the observation pass costs one {@code docker
   * inspect} <b>per row, per pass, forever</b>, and {@code ContainersDriver.Observed} carries all
   * three fields.
   *
   * <p><b>It is not {@code "{{.Id}}|" + STATE_FORMAT + "|" + STARTED_AT_FORMAT}, and the reason is
   * measured rather than stylistic.</b> On docker 29.7.2 that composition fails on any container
   * without a healthcheck, with {@code map has no entry for key "Health"} — while
   * {@link #STATE_FORMAT} on its own answers {@code running/none} perfectly. The difference is
   * {@code .Id}: the CLI renders a template against the typed inspect object first and falls back
   * to the <b>raw JSON map</b> when that fails, and the Go field is named {@code ID} while the JSON
   * key is {@code Id}. So asking for the id at all moves the whole template onto the map path,
   * where {@code {{if .State.Health}}} is an error rather than a false — a missing map key is not a
   * zero value in Go's templates, which is the same lesson the volume label format learned from
   * {@code index}.
   *
   * <p>{@code index} is what a map path has instead: {@code index .State "Health"} answers the zero
   * value for a container that declares no check, so the {@code else} arm renders and the health
   * reads {@code none}. Measured both ways on docker 29.7.2 — {@code running/none} for a container
   * without a check, {@code running/healthy} for one with.
   *
   * <p>The two single-field spellings beside it stay: they are the typed-path forms, they are what
   * a caller that needs one field should use, and this one is deliberately not built out of them.
   */
  public static final String OBSERVATION_FORMAT =
      "{{.Id}}|{{.State.Status}}/"
          + "{{if index .State \"Health\"}}{{(index .State \"Health\").Status}}{{else}}none{{end}}"
          + "|{{.State.StartedAt}}";

  /** One inspect answering everything an observation records — see {@link #OBSERVATION_FORMAT}. */
  public static List<String> inspectObservation(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "inspect",
        "--format",
        OBSERVATION_FORMAT,
        ContainersIdentifiers.requireContainerName(name));
  }

  /** Stop it, leaving it restartable. */
  public static List<String> stop(String runtimeBinary, String name) {
    return List.of(runtimeBinary, "stop", ContainersIdentifiers.requireContainerName(name));
  }

  /** Remove it, running or not. Every teardown ends here, and never at a {@code --rm}. */
  public static List<String> rm(String runtimeBinary, String name) {
    return List.of(runtimeBinary, "rm", "-f", ContainersIdentifiers.requireContainerName(name));
  }

  /** A bounded tail of the container's own output — the diagnosis, captured before any removal. */
  public static List<String> logsTail(String runtimeBinary, String name, int lines) {
    if (lines <= 0) {
      throw new IllegalArgumentException("Invalid log tail: " + lines + " lines");
    }
    return List.of(
        runtimeBinary,
        "logs",
        "--tail",
        String.valueOf(lines),
        ContainersIdentifiers.requireContainerName(name));
  }

  /**
   * Container ids matching every one of the label filters.
   *
   * <p>This narrows a listing; it never decides a removal. Which containers may be touched is a
   * question the registry rows answer, and a host-wide label sweep is the regression this repository
   * exists to remove.
   */
  public static List<String> psByLabels(String runtimeBinary, Map<String, String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "ps", "-aq"));
    for (Map.Entry<String, String> filter : new TreeMap<>(filters).entrySet()) {
      argv.add("--filter");
      argv.add("label=" + filter.getKey() + "=" + filter.getValue());
    }
    return List.copyOf(argv);
  }

  /** Fetch the image, so "the registry has no such image" is its own outcome rather than a run. */
  public static List<String> pull(String runtimeBinary, String imageRef) {
    return List.of(runtimeBinary, "pull", ContainersIdentifiers.requireImage(imageRef));
  }

  /** Create the named volume, labelled. Creating one that exists is docker's own no-op. */
  public static List<String> volumeCreate(
      String runtimeBinary, VolumeSpec spec, Map<String, String> labels) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "volume", "create"));
    for (Map.Entry<String, String> label : merged(labels, spec.extraLabels()).entrySet()) {
      argv.add("--label");
      argv.add(label.getKey() + "=" + label.getValue());
    }
    argv.add(spec.name());
    return List.copyOf(argv);
  }

  /** Remove the named volume. Only ever an explicit ask — nothing sweeps a volume. */
  public static List<String> volumeRm(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary, "volume", "rm", ContainersIdentifiers.requireVolumeName(name));
  }

  /** Volume names matching every one of the label filters. */
  public static List<String> volumeLs(String runtimeBinary, Map<String, String> filters) {
    List<String> argv = new ArrayList<>(List.of(runtimeBinary, "volume", "ls", "-q"));
    for (Map.Entry<String, String> filter : new TreeMap<>(filters).entrySet()) {
      argv.add("--filter");
      argv.add("label=" + filter.getKey() + "=" + filter.getValue());
    }
    return List.copyOf(argv);
  }

  /** A volume's labels, one per line — see {@link #VOLUME_LABELS_FORMAT}. */
  public static List<String> volumeInspectLabels(String runtimeBinary, String name) {
    return List.of(
        runtimeBinary,
        "volume",
        "inspect",
        "--format",
        VOLUME_LABELS_FORMAT,
        ContainersIdentifiers.requireVolumeName(name));
  }

  /**
   * Ask whether the network exists. <b>There is no {@code network create} here.</b> Creating a
   * bridge is refused on a swarm-initialized host, and a network this service invented would be one
   * no other module's containers are on — so a missing network is something a deployment answers,
   * not something an orchestrator papers over.
   */
  public static List<String> networkInspect(String runtimeBinary, String network) {
    return List.of(
        runtimeBinary, "network", "inspect", ContainersIdentifiers.requireNetwork(network));
  }

  /**
   * This service's labels and the owner's, in one sorted map. The owner's keys are re-checked here
   * rather than trusted from the spec — the second checkpoint, on the value that would forge a
   * namespace label.
   */
  private static Map<String, String> merged(
      Map<String, String> labels, Map<String, String> extraLabels) {
    Map<String, String> all = new TreeMap<>(labels == null ? Map.of() : labels);
    all.putAll(ContainersIdentifiers.requireExtraLabels(extraLabels));
    return all;
  }
}
