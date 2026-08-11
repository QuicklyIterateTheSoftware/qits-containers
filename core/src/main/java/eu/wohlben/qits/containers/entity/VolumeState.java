package eu.wohlben.qits.containers.entity;

/**
 * What a {@link CtVolume} row asks for. Two values, because a volume has no lifecycle beyond being
 * there or not — it does not run and it cannot exit.
 *
 * <p>It is a separate enum rather than a reuse of {@link DesiredState} because {@code RUNNING} on a
 * volume row would read as a state a volume can be in, and a reader would then ask what
 * {@code STOPPED} means for one.
 */
public enum VolumeState {

  /** The volume should exist. */
  PRESENT,

  /**
   * The volume should be gone. It is what a delete writes before it calls docker, and it is the only
   * thing that makes a volume removable by {@code VolumeReconcile}: a volume no row names is
   * somebody else's and is never removed.
   */
  ABSENT
}
