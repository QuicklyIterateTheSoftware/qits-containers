package eu.wohlben.qits.containers.entity;

/**
 * What the last look at the host found. The other half of {@link DesiredState}.
 *
 * <p><b>{@link #MISSING} and {@link #GONE} are two different statements and must never be merged.</b>
 * {@code MISSING} is "the container this row names is not there and nobody asked for that" — a
 * failure to record and, for a row still desired {@code RUNNING}, something to recover from.
 * {@code GONE} is "it is not there because we removed it", which is the settled end of a delete. A
 * sweep replays a delete for every {@code ABSENT} row that is not yet {@code GONE}, so collapsing
 * the two would make it replay every failure as well.
 */
public enum ObservedState {

  /** A row written, no {@code docker run} attempted yet. The state a crash can leave behind. */
  PENDING,

  /** The run was accepted and the container has not been confirmed running yet. */
  STARTING,

  /** Docker says it is running. */
  RUNNING,

  /** Docker says it stopped. For an {@code EPHEMERAL} workload this is the success path. */
  EXITED,

  /** Docker has no such container and nobody asked for that. See the class javadoc. */
  MISSING,

  /** Removed, on purpose. The settled end of a delete. */
  GONE;

  /** Whether a boot sweep has to decide about this row: a run that was interrupted mid-flight. */
  public boolean inFlight() {
    return this == PENDING || this == STARTING;
  }

  /** Whether nothing is running under this row any more, for whatever reason. */
  public boolean terminal() {
    return this == EXITED || this == MISSING || this == GONE;
  }
}
