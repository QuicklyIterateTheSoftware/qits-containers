package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.persistence.CtContainerRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Collects {@code EPHEMERAL} workloads that have outlived their declared maximum age.
 *
 * <p>An {@code EPHEMERAL} container runs once and exits, and its exit is the success path — so
 * nothing about the workload itself ever asks for it to be removed. {@code maxAge} is the owner
 * saying how long the container may sit around after that, for its logs to be read, before it is
 * collected. A workload whose policy names no max age is never collected here.
 *
 * <p><b>It deletes through {@link ContainerRegistry#delete}</b> rather than calling docker itself,
 * which is what keeps the delete path one path: the row goes {@code ABSENT} before the remove, the
 * settle is the same settle, and an interrupted collection is replayed by the boot sweep like any
 * other interrupted delete. Logs are skipped — nobody is waiting to read them, and this is precisely
 * the moment the owner has been given to stop caring.
 *
 * <p>{@code maxAge} is measured from {@code created_at}, which is when the row was written and
 * therefore when the workload was asked for. Not from the exit: an exit time is on no column, and
 * adding one would make the age of a container that never started unanswerable.
 *
 * <p>It runs on {@code ct-worker}, enqueued by {@link ContainerObserver}.
 */
@ApplicationScoped
public class MaxAgeGc {

  private static final Logger LOG = Logger.getLogger(MaxAgeGc.class);

  @Inject CtContainerRepository containers;
  @Inject ContainerRegistry registry;
  @Inject Clock clock;

  /** One pass, as of now. */
  void sweepOnce() {
    sweepOnce(clock.instant());
  }

  /**
   * One pass as of a given instant, so a suite can travel past a max age.
   *
   * @return how many workloads it collected
   */
  int sweepOnce(Instant now) {
    List<Overdue> overdue =
        registry.read(
            "The max-age sweep's candidate read",
            () -> {
              List<Overdue> out = new ArrayList<>();
              for (CtContainer row : containers.listMaxAgeCandidates()) {
                if (row.createdAt.plusSeconds(row.maxAgeS).isAfter(now)) {
                  continue;
                }
                out.add(new Overdue(row.owner, row.workload, row.ownerRef, row.containerName));
              }
              return List.copyOf(out);
            });
    for (Overdue row : overdue) {
      // Volumes stay: taking one is an explicit ask about one workload, never a sweep's decision.
      registry.delete(row.owner(), row.workload(), row.ownerRef(), false, false);
      LOG.infof("Collected %s: it outlived its declared max age", row.containerName());
    }
    return overdue.size();
  }

  private record Overdue(String owner, String workload, String ownerRef, String containerName) {}
}
