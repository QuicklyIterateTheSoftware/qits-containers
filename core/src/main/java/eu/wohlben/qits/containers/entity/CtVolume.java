package eu.wohlben.qits.containers.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One named docker volume this service made for an owner, and whether it should still be there.
 *
 * <p><b>The row is what makes a volume removable at all.</b> A volume carrying this service's labels
 * but named by no row is somebody else's — a compose original, a bootstrap seed, a volume from
 * before this service existed — and unclaimed means untouchable, exactly as it does for containers.
 * {@code VolumeReconcile} reads that rule off this table and nothing else.
 *
 * <p>The volume's labels carry no row id and no instance ({@code ContainerLabels.forVolume}),
 * because a volume outlives the container that mounted it and the process that made it. So the
 * lookup from a listed volume back to its row is {@code (owner, name)}, which is V1's unique key.
 *
 * <p><b>{@link Uncaused} by decision, and the reason is what this row is.</b> It is not a record of
 * something that happened — it is a converging registry entry for a volume that exists, rewritten by
 * every later {@code ensure} of the workload that mounts it. The causation column is insert-only on
 * purpose, so it would pin this row forever to whichever ensure happened to be the first one, saying
 * nothing about the volume the row describes today. The same argument qits-platform-deployments'
 * {@code PdResource} makes, and it comes out the same way. The container row beside it
 * ({@link CtContainer}) is the one that records a cause, because that one <em>is</em> a record of an
 * occurrence.
 */
@Entity
@Table(name = "ct_volume")
@Uncaused
public class CtVolume extends PanacheEntityBase {

  @Id public UUID id;

  /** Who asked. The scope of every lookup: an owner can only reach its own volumes. */
  @Column(nullable = false, length = 64)
  public String owner;

  /** The docker volume name, unique on the host and therefore unique per owner here. */
  @Column(nullable = false, length = 190)
  public String name;

  /** The label set the volume was created with, as JSON. Diagnostic, never read as a decision. */
  @Column(name = "labels_json", columnDefinition = "text")
  public String labelsJson;

  @Enumerated(EnumType.STRING)
  @Column(name = "desired_state", nullable = false, length = 16)
  public VolumeState desiredState;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;
}
