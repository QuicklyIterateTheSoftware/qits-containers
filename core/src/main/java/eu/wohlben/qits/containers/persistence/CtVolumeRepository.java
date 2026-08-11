package eu.wohlben.qits.containers.persistence;

import eu.wohlben.qits.containers.entity.CtVolume;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.UUID;

/**
 * The volume table's queries. Two, because a volume has two questions: is there a row for this one,
 * and what does this owner have.
 *
 * <p>The lookup key is {@code (owner, name)} rather than a row id, because a listed docker volume
 * carries neither — {@code ContainerLabels.forVolume} writes no row id and no instance on purpose,
 * since a volume outlives both. V1 declares that pair unique, which is what makes the lookup exact.
 */
@ApplicationScoped
public class CtVolumeRepository implements PanacheRepositoryBase<CtVolume, UUID> {

  public CtVolume findByOwnerAndName(String owner, String name) {
    return find("owner = ?1 and name = ?2", owner, name).firstResult();
  }

  public List<CtVolume> listByOwner(String owner) {
    return find("owner = ?1 order by createdAt", owner).list();
  }
}
