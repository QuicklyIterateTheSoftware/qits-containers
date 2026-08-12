package eu.wohlben.qits.containers.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything one container is started with — the whole of what an owner may ask for, and nothing
 * else is reachable.
 *
 * <p><b>The shape is the security boundary.</b> There is no free-form argv field and no host-path
 * mount: an owner names volumes and where they land, and the one bind this service will ever make is
 * {@link #hostDockerSocket}, which is a boolean rather than a path precisely so that no caller can
 * choose what gets mounted. Anything a spec cannot express is a change to this record, reviewed as
 * one — which is the difference between a privilege that was granted and a privilege that was
 * assembled.
 *
 * <p><b>A plain record, jackson-friendly.</b> The spec is stored as JSON on the registry row so a
 * restart can compare what is running against what was asked for — except {@link #env}, which
 * carries credentials and is never persisted. Nulls normalize to empty on the way in, so a caller
 * that omits a field and a caller that sends {@code []} mean the same thing, and every collection
 * here comes back immutable.
 *
 * <p>Use {@link #builder(String)} rather than the canonical constructor: fifteen positional
 * arguments is a call nobody can read, and a mis-ordered pair of them would compile.
 *
 * @param image the reference to run. Loose by design — see {@link
 *     ContainersIdentifiers#requireImage}.
 * @param entrypoint overrides the image's own, or empty to keep it. Docker's {@code --entrypoint}
 *     takes ONE word, so a longer list renders its first element as the flag and the rest as leading
 *     arguments — which is the CLI's own convention ({@code --entrypoint /bin/sh img -c '…'}).
 * @param args what follows the image.
 * @param env the container's environment. <b>Never persisted</b>: it is where a secret rides.
 * @param extraLabels the owner's own bookkeeping. Keys inside {@value ContainerLabels#NAMESPACE} are
 *     refused — see {@link ContainersIdentifiers#requireExtraLabelKey}.
 * @param network the one network {@code docker run} takes. Further memberships are joins later.
 * @param aliases the addresses this container answers to on that network.
 * @param addHosts {@code name:target} entries, the {@code host.docker.internal:host-gateway} shape.
 * @param volumeMounts named volumes belonging to this workload.
 * @param sharedMounts the platform's shared volumes — the maven repository, the pnpm store, the
 *     coding agent's home. Rendered exactly like a {@link VolumeMount}; kept apart because a shared
 *     volume is <b>not this workload's</b> and must never be removed with it.
 * @param hostDockerSocket the one bind. A container holding it is root-equivalent on the host, so it
 *     is a declaration a reviewer can see rather than a path a caller supplies.
 * @param security the sandbox. Absent fields render nothing, so "unset" and "off" stay distinct.
 * @param pullPolicy whether the driver pulls before running. <b>It renders no argv element</b> —
 *     {@code docker run --pull} exists, but a separate {@code docker pull} is what makes "the
 *     registry has no such image" its own recorded outcome rather than a run failure.
 * @param explicitName the container name, when the owner keeps its own (qits-ci does). Empty means
 *     this service derives one.
 * @param user who the first process runs as — {@code docker run --user}. Empty keeps the image's own
 *     default, which is root for every base image the platform builds on. It exists because a
 *     sandboxed container cannot change user from the inside: {@code --cap-drop=ALL} takes CAP_SETUID
 *     and CAP_SETGID away, so {@code su} inside the script fails whatever the script does, and the
 *     only moment a user can be chosen is the {@code run}. Measured 2026-08-12, on qits-containers'
 *     own post-receive step. See {@link ContainersIdentifiers#requireUser}.
 */
public record ContainerSpec(
    String image,
    List<String> entrypoint,
    List<String> args,
    Map<String, String> env,
    Map<String, String> extraLabels,
    String network,
    List<String> aliases,
    List<String> addHosts,
    List<VolumeMount> volumeMounts,
    List<SharedMount> sharedMounts,
    boolean hostDockerSocket,
    SecurityPosture security,
    PullPolicy pullPolicy,
    String explicitName,
    String user) {

  /** A named volume of this workload's own, and where it lands inside the container. */
  public record VolumeMount(String volumeName, String containerPath) {
    public VolumeMount {
      ContainersIdentifiers.requireVolumeName(volumeName);
      ContainersIdentifiers.requireContainerPath(containerPath);
    }
  }

  /**
   * One of the platform's shared volumes. Same rendering as a {@link VolumeMount} and a different
   * type on purpose: what differs is ownership, and ownership is what a delete reads.
   */
  public record SharedMount(String sharedName, String containerPath) {
    public SharedMount {
      ContainersIdentifiers.requireVolumeName(sharedName);
      ContainersIdentifiers.requireContainerPath(containerPath);
    }
  }

  /**
   * The sandbox, as flags. <b>Every field is optional and a null renders nothing</b>, so a spec that
   * says nothing about memory does not silently acquire a limit — and a caller that wants no
   * sandbox has to be seen asking for none rather than seen omitting a field.
   */
  public record SecurityPosture(
      boolean capDropAll,
      boolean noNewPrivileges,
      String memory,
      String memorySwap,
      Long pidsLimit,
      String cpus) {

    /** No flags at all — for a workload the platform itself wrote and runs unsandboxed. */
    public static SecurityPosture none() {
      return new SecurityPosture(false, false, null, null, null, null);
    }
  }

  /** Whether the driver pulls the image before running it. */
  public enum PullPolicy {
    /** Pull only when the host does not already have the reference. */
    MISSING,
    /** Always pull — a moving tag the caller wants the newest of. */
    ALWAYS,
    /** Never pull. The image is local or the run fails, which is sometimes the point. */
    NEVER
  }

  public ContainerSpec {
    ContainersIdentifiers.requireImage(image);
    ContainersIdentifiers.requireNetwork(network);
    entrypoint = copy(entrypoint);
    args = copy(args);
    env = checkedEnv(env);
    extraLabels = ContainersIdentifiers.requireExtraLabels(extraLabels);
    aliases = copy(aliases);
    aliases.forEach(ContainersIdentifiers::requireAlias);
    addHosts = copy(addHosts);
    addHosts.forEach(ContainersIdentifiers::requireAddHost);
    volumeMounts = copy(volumeMounts);
    sharedMounts = copy(sharedMounts);
    security = security == null ? SecurityPosture.none() : security;
    pullPolicy = pullPolicy == null ? PullPolicy.MISSING : pullPolicy;
    explicitName = explicitName == null || explicitName.isBlank() ? "" : explicitName;
    if (!explicitName.isEmpty()) {
      ContainersIdentifiers.requireContainerName(explicitName);
    }
    user = user == null || user.isBlank() ? "" : user;
    if (!user.isEmpty()) {
      ContainersIdentifiers.requireUser(user);
    }
  }

  private static <T> List<T> copy(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  /** Keys are POSIX-shaped; the values beside them are the owner's business and stay untouched. */
  private static Map<String, String> checkedEnv(Map<String, String> env) {
    if (env == null || env.isEmpty()) {
      return Map.of();
    }
    Map<String, String> checked = new LinkedHashMap<>();
    env.forEach((k, v) -> checked.put(ContainersIdentifiers.requireEnvKey(k), v == null ? "" : v));
    return Collections.unmodifiableMap(checked);
  }

  public static Builder builder(String image) {
    return new Builder(image);
  }

  /** Reads like the {@code docker run} it becomes. Every belt still runs at {@code build()}. */
  public static final class Builder {

    private final String image;
    private final List<String> entrypoint = new ArrayList<>();
    private final List<String> args = new ArrayList<>();
    private final Map<String, String> env = new LinkedHashMap<>();
    private final Map<String, String> extraLabels = new LinkedHashMap<>();
    private String network = "bridge";
    private final List<String> aliases = new ArrayList<>();
    private final List<String> addHosts = new ArrayList<>();
    private final List<VolumeMount> volumeMounts = new ArrayList<>();
    private final List<SharedMount> sharedMounts = new ArrayList<>();
    private boolean hostDockerSocket;
    private SecurityPosture security = SecurityPosture.none();
    private PullPolicy pullPolicy = PullPolicy.MISSING;
    private String explicitName = "";
    private String user = "";

    private Builder(String image) {
      this.image = image;
    }

    public Builder entrypoint(String... words) {
      entrypoint.addAll(List.of(words));
      return this;
    }

    public Builder args(String... values) {
      args.addAll(List.of(values));
      return this;
    }

    public Builder env(String key, String value) {
      env.put(key, value);
      return this;
    }

    public Builder label(String key, String value) {
      extraLabels.put(key, value);
      return this;
    }

    public Builder network(String value) {
      network = value;
      return this;
    }

    public Builder alias(String value) {
      aliases.add(value);
      return this;
    }

    public Builder addHost(String entry) {
      addHosts.add(entry);
      return this;
    }

    public Builder mount(String volumeName, String containerPath) {
      volumeMounts.add(new VolumeMount(volumeName, containerPath));
      return this;
    }

    public Builder shared(String sharedName, String containerPath) {
      sharedMounts.add(new SharedMount(sharedName, containerPath));
      return this;
    }

    public Builder hostDockerSocket(boolean value) {
      hostDockerSocket = value;
      return this;
    }

    public Builder security(SecurityPosture value) {
      security = value;
      return this;
    }

    public Builder pullPolicy(PullPolicy value) {
      pullPolicy = value;
      return this;
    }

    public Builder name(String value) {
      explicitName = value;
      return this;
    }

    public Builder user(String value) {
      user = value;
      return this;
    }

    public ContainerSpec build() {
      return new ContainerSpec(
          image,
          entrypoint,
          args,
          env,
          extraLabels,
          network,
          aliases,
          addHosts,
          volumeMounts,
          sharedMounts,
          hostDockerSocket,
          security,
          pullPolicy,
          explicitName,
          user);
    }
  }
}
