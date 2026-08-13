package eu.wohlben.qits.containers.api;

import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.entity.VolumeState;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything this service accepts and answers on the wire, in one file.
 *
 * <p><b>They mirror {@code core}'s records rather than being them.</b> The domain records carry
 * their belts in their compact constructors, and a Jackson deserializer that ran those on the way
 * in would turn a hostile field into a 500 before any resource had said what was wrong with it. So
 * the wire types are plain carriers, the mapping into the domain is one method, and the belts fire
 * there — where an {@code IllegalArgumentException} is a 400 with the field named in it.
 *
 * <p><b>No entity is on this surface.</b> A caller reads a row through {@code
 * ContainerRegistry.Place}, which is already the row copied out as plain values; these records are
 * that shape with the names a caller uses.
 */
public final class ContainersWire {

  private ContainersWire() {}

  // --- what a caller asks for -------------------------------------------------------------------

  /** A named volume of the workload's own, and where it lands inside the container. */
  public record VolumeMountDto(String volumeName, String containerPath) {}

  /** One of the platform's shared volumes. A different type because ownership differs. */
  public record SharedMountDto(String sharedName, String containerPath) {}

  /** The sandbox. Every field optional: a null renders no flag, so "unset" is not "off". */
  public record SecurityDto(
      boolean capDropAll,
      boolean noNewPrivileges,
      String memory,
      String memorySwap,
      Long pidsLimit,
      String cpus) {}

  /** {@link ContainerSpec} on the wire. */
  public record SpecDto(
      String image,
      List<String> entrypoint,
      List<String> args,
      Map<String, String> env,
      Map<String, String> extraLabels,
      String network,
      List<String> aliases,
      List<String> addHosts,
      List<VolumeMountDto> volumeMounts,
      List<SharedMountDto> sharedMounts,
      boolean hostDockerSocket,
      SecurityDto security,
      ContainerSpec.PullPolicy pullPolicy,
      String explicitName,
      String user,
      Boolean init) {}

  /**
   * {@link LifecyclePolicy} on the wire. The two durations are seconds rather than an ISO string
   * because they are stored as seconds and a caller that reads a row back would otherwise get a
   * different spelling than it sent.
   */
  public record PolicyDto(LifecyclePolicy.Type type, Long idleAfterSeconds, Long maxAgeSeconds) {}

  /**
   * Whether a spec change may replace what is running.
   *
   * <p>Lower case, because these are the two words the contract spells and an enum constant is what
   * a caller sends. {@code never} is the default and the safe one: an owner that only wants the
   * place occupied gets what is there, told that it differs, rather than a restart it did not ask
   * for.
   */
  public enum Recreate {
    never,
    ifChanged
  }

  /** The body of the one write that starts something. */
  public record EnsureRequest(SpecDto spec, PolicyDto policy, Recreate recreate) {}

  // --- what this service answers with -----------------------------------------------------------

  /** What was asked for, and what the last look found. Never merged into one word. */
  public record StateDto(DesiredState desired, ObservedState observed) {}

  /**
   * Where a caller's own containers reach this workload.
   *
   * <p>{@code proxy} is always null today and is on the envelope anyway: the reverse tunnels two
   * modules carry by hand are meant to centralize here, and a caller that has already been reading
   * {@code endpoint} will find them without a second shape to learn.
   */
  public record EndpointDto(String containerName, String network, String alias, String proxy) {}

  /** One place, as every route that answers about one answers. */
  public record ContainerEnvelope(
      UUID id,
      String containerName,
      StateDto state,
      EndpointDto endpoint,
      String specHash,
      boolean created,
      String detail) {}

  public record ListResponse(List<ContainerEnvelope> containers) {}

  /** A bounded tail, and whether the front of it was dropped. */
  public record LogsResponse(String text, boolean truncated) {}

  /** {@code existed=false} is a success: the caller asked for nothing to be there, and nothing is. */
  public record DeleteResponse(
      UUID id, String containerName, boolean existed, String logTail, String detail) {}

  /** One place's outcome inside a destroy-all. */
  public record DestroyedDto(String ref, String containerName, boolean removed, String detail) {}

  public record DestroyAllResponse(List<DestroyedDto> destroyed) {}

  /** A volume an owner asked for by name. */
  public record VolumeEnvelope(
      UUID id, String owner, String name, VolumeState desired, boolean existed, String detail) {}

  /**
   * The one error shape. {@code code} is what a caller branches on — {@code SPEC_CONFLICT},
   * {@code NAME_TAKEN}, {@code IMAGE_MISSING}, {@code INVALID} — and {@code message} is the sentence
   * a person reads.
   */
  public record ErrorBody(String code, String message) {}

  /** A value this service will not put in an argv. 400. */
  public static final String INVALID = "INVALID";

  /** A spec change the workload's lifecycle policy cannot answer. 409. */
  public static final String SPEC_CONFLICT = "SPEC_CONFLICT";

  /**
   * The container name this place would claim is held by a live container of a different place. 409.
   *
   * <p>Its own code and not {@code SPEC_CONFLICT}, because the two ask for different answers: that
   * one says the policy cannot replace what is here, and a caller reading it deletes and asks again
   * under a new ref. This one says the name belongs to somebody else's running workload, which no
   * ref of the caller's changes.
   */
  public static final String NAME_TAKEN = "NAME_TAKEN";

  /** The registry has no such image, so the run had nothing to start. 409. */
  public static final String IMAGE_MISSING = "IMAGE_MISSING";

  // --- the wire to the domain, and the belts fire here -------------------------------------------

  /** The spec a caller sent, as the domain record. Every belt in the compact constructor runs. */
  public static ContainerSpec toSpec(SpecDto dto) {
    if (dto == null) {
      throw new IllegalArgumentException("Invalid request: no spec");
    }
    return new ContainerSpec(
        dto.image(),
        dto.entrypoint(),
        dto.args(),
        dto.env(),
        dto.extraLabels(),
        dto.network(),
        dto.aliases(),
        dto.addHosts(),
        dto.volumeMounts() == null
            ? List.of()
            : dto.volumeMounts().stream()
                .map(m -> new ContainerSpec.VolumeMount(m.volumeName(), m.containerPath()))
                .toList(),
        dto.sharedMounts() == null
            ? List.of()
            : dto.sharedMounts().stream()
                .map(m -> new ContainerSpec.SharedMount(m.sharedName(), m.containerPath()))
                .toList(),
        dto.hostDockerSocket(),
        dto.security() == null
            ? ContainerSpec.SecurityPosture.none()
            : new ContainerSpec.SecurityPosture(
                dto.security().capDropAll(),
                dto.security().noNewPrivileges(),
                dto.security().memory(),
                dto.security().memorySwap(),
                dto.security().pidsLimit(),
                dto.security().cpus()),
        dto.pullPolicy(),
        dto.explicitName(),
        dto.user(),
        // Nullable on the wire and a plain false in the domain: a caller written before the field
        // existed sends no `init` at all, and an absent one has to mean the behaviour it had then.
        dto.init() != null && dto.init());
  }

  /** The policy a caller sent. A body with no policy is refused rather than defaulted. */
  public static LifecyclePolicy toPolicy(PolicyDto dto) {
    if (dto == null || dto.type() == null) {
      throw new IllegalArgumentException(
          "Invalid lifecycle policy: name one of EPHEMERAL, IDLE_STOP or EXPLICIT");
    }
    return new LifecyclePolicy(dto.type(), seconds(dto.idleAfterSeconds()), seconds(dto.maxAgeSeconds()));
  }

  private static Duration seconds(Long value) {
    if (value == null) {
      return null;
    }
    if (value <= 0) {
      throw new IllegalArgumentException("Invalid duration: " + value + " seconds");
    }
    return Duration.ofSeconds(value);
  }
}
