package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import eu.wohlben.qits.db.DbRetry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Deletes registry rows whose story is over.
 *
 * <p><b>Only settled rows qualify</b> — {@code desired=ABSENT} and {@code observed=GONE}, last
 * written before the horizon. That pair means the owner asked for the container to go and it did,
 * so the row names nothing on the host any more and deleting it can never orphan a container. Every
 * other combination is left alone forever, including the unsettled deletes the boot sweep replays:
 * a row that still names something is a row that still has a job.
 *
 * <p>The horizon is {@code qits.containers.row-prune-horizon} (P7D). It is generous on purpose —
 * this table is the record a person reads after an incident, and a week is what makes "what happened
 * to that workload last Tuesday" answerable.
 *
 * <p>One statement, not a read-then-delete loop: a bulk delete over a predicate the database can
 * evaluate is both cheaper and immune to a row changing between the read and the delete.
 */
@ApplicationScoped
public class RowPrune {

  private static final Logger LOG = Logger.getLogger(RowPrune.class);

  @Inject CtContainerRepository containers;
  @Inject Clock clock;

  @ConfigProperty(name = "qits.containers.row-prune-horizon")
  Duration horizon;

  /** One pass, as of now. */
  void pruneOnce() {
    pruneOnce(clock.instant());
  }

  /**
   * One pass as of a given instant.
   *
   * @return how many rows it deleted
   */
  long pruneOnce(Instant now) {
    if (horizon == null || horizon.isZero() || horizon.isNegative()) {
      return 0;
    }
    long pruned =
        DbRetry.inNewTx(
            "The registry row prune",
            () -> {
              long count = containers.deleteSettledBefore(now.minus(horizon));
              containers.flush();
              return count;
            },
            ContainerRegistry.CUTOVER_BUDGET);
    if (pruned > 0) {
      LOG.infof("Pruned %d settled container row(s) older than %s", pruned, horizon);
    }
    return pruned;
  }
}
