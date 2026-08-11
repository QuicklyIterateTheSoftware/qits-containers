package eu.wohlben.qits.containers.entity;

/**
 * What the owner asked for. One half of the restart story; {@link ObservedState} is the other.
 *
 * <p><b>The two are never merged into one column</b>, and that separation is the whole mechanism: a
 * boot sweep compares what was asked for against what the host has, and a single "state" column
 * would have to choose one of the two to record and lose the other. A row that says {@code RUNNING}
 * beside {@code MISSING} is not a contradiction — it is the exact statement a restart has to act on.
 *
 * <p>Stored as a string ({@code @Enumerated(STRING)}) with no check constraint on the column, for
 * the reason V1's header gives: the catalogue grows, and a check constraint turns each addition into
 * a migration that must ship before the code writing the value.
 */
public enum DesiredState {

  /** The owner wants a container here, running. */
  RUNNING,

  /** The owner wants it stopped but kept — an idle stop, or an explicit one. */
  STOPPED,

  /**
   * The owner is done with it. The row stays as history and the place is free again the moment this
   * is set — V1's partial unique index over (owner, workload, owner_ref) excludes it.
   */
  ABSENT
}
