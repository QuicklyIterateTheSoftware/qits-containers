package eu.wohlben.qits.containers.persistence;

import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.DesiredState;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/**
 * The registry table's queries, one per question a sweep actually asks.
 *
 * <p><b>Every listing is ordered by {@code seq}</b>, V1's identity column, and never by
 * {@code created_at} or by the id: two rows written in the same tick would then be ordered at
 * random, and a sweep whose order changes between passes is a sweep whose log cannot be read.
 *
 * <p><b>Nothing here lists by label, by image or by container name pattern.</b> The rows are the
 * registry; a sweep that could ask "which containers look like mine" would be the reap this
 * repository exists to remove. {@link #findByContainerName} is the one lookup by name, and it exists
 * for the opposite direction — turning a name a row already gave us back into that row.
 */
@ApplicationScoped
public class CtContainerRepository implements PanacheRepositoryBase<CtContainer, java.util.UUID> {

  /**
   * The one live row of a place, or null. "Live" is {@code desired_state <> 'ABSENT'}, which is
   * exactly what V1's partial unique index makes at-most-one.
   */
  public CtContainer findLive(String owner, String workload, String ownerRef) {
    return find(
            "owner = ?1 and workload = ?2 and ownerRef = ?3 and desiredState <> ?4",
            owner,
            workload,
            ownerRef,
            DesiredState.ABSENT)
        .firstResult();
  }

  /** The row that named this container, or null. Used to turn an observation back into a row. */
  public CtContainer findByContainerName(String containerName) {
    return find("containerName", containerName).firstResult();
  }

  /**
   * Rows a {@code docker run} was in the middle of when the process stopped — the boot sweep's first
   * question. {@code PENDING} is a row written and never run; {@code STARTING} is a run accepted and
   * never confirmed.
   */
  public List<CtContainer> listInFlight() {
    return find(
            "observedState in ?1 order by seq",
            List.of(ObservedState.PENDING, ObservedState.STARTING))
        .list();
  }

  /**
   * Deletes that were asked for and never settled. The boot sweep replays each one, which is what
   * makes {@code delete} survive a crash between the row write and the {@code docker rm}.
   */
  public List<CtContainer> listUnsettledDeletes() {
    return find(
            "desiredState = ?1 and observedState <> ?2 order by seq",
            DesiredState.ABSENT,
            ObservedState.GONE)
        .list();
  }

  /** Every row the observer has anything to say about: one the owner has not deleted. */
  public List<CtContainer> listLive() {
    return find("desiredState <> ?1 order by seq", DesiredState.ABSENT).list();
  }

  /** The live rows of one owner's workload — what a destroy-all iterates. Never a label sweep. */
  public List<CtContainer> listLive(String owner, String workload) {
    return find(
            "owner = ?1 and workload = ?2 and desiredState <> ?3 order by seq",
            owner,
            workload,
            DesiredState.ABSENT)
        .list();
  }

  /**
   * {@code IDLE_STOP} rows that are running and are meant to be. A row with no {@code idleAfterS} is
   * excluded here rather than skipped in the sweep: "no sweep of that kind" is what a null means.
   */
  public List<CtContainer> listIdleCandidates() {
    return find(
            "policy = ?1 and desiredState = ?2 and observedState = ?3 and idleAfterS is not null"
                + " order by seq",
            LifecyclePolicy.Type.IDLE_STOP,
            DesiredState.RUNNING,
            ObservedState.RUNNING)
        .list();
  }

  /** Live {@code EPHEMERAL} rows carrying a max age — the only rows the age sweep may collect. */
  public List<CtContainer> listMaxAgeCandidates() {
    return find(
            "policy = ?1 and desiredState <> ?2 and maxAgeS is not null order by seq",
            LifecyclePolicy.Type.EPHEMERAL,
            DesiredState.ABSENT)
        .list();
  }

  /**
   * Settled history older than a horizon: deleted, removed, and last touched before {@code cut}.
   * Only {@code ABSENT}/{@code GONE} rows qualify, so pruning can never drop a row that still names
   * a container.
   */
  public long deleteSettledBefore(Instant cut) {
    return delete(
        "desiredState = ?1 and observedState = ?2 and updatedAt < ?3",
        DesiredState.ABSENT,
        ObservedState.GONE,
        cut);
  }
}
