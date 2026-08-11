package eu.wohlben.qits.containers.spec;

import java.util.Map;

/**
 * A named docker volume this service makes and labels.
 *
 * <p>A volume is not a container: it outlives the workload that mounted it and the process that
 * created it, which is why its labels carry no row and no instance ({@link
 * ContainerLabels#forVolume}). Removing one is always somebody asking for it explicitly — nothing
 * here sweeps a volume because a container went away.
 *
 * @param name the volume's own name, unique on the host
 * @param extraLabels the owner's own bookkeeping. Keys inside {@value ContainerLabels#NAMESPACE}
 *     are refused, for the reason {@link ContainersIdentifiers#requireExtraLabelKey} states.
 */
public record VolumeSpec(String name, Map<String, String> extraLabels) {

  public VolumeSpec {
    ContainersIdentifiers.requireVolumeName(name);
    extraLabels = ContainersIdentifiers.requireExtraLabels(extraLabels);
  }

  public VolumeSpec(String name) {
    this(name, Map.of());
  }
}
