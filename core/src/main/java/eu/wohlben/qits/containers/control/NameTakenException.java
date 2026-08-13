package eu.wohlben.qits.containers.control;

/**
 * The container name this {@code ensure} would claim is already held by a <b>live</b> container of a
 * different place.
 *
 * <p>Docker's name space is flat, so two live containers cannot share a name and something has to
 * say so. Before V3 the database said it, for every case: the table-wide unique constraint refused a
 * deleted place's own name back to it and the refusal reached the caller as a raw 23505 — an
 * unmapped 500 with a null code, which no consumer can branch on. V3 freed the name with the place,
 * and this exception is what is left: the collision that is real.
 *
 * <p><b>Real means another place's.</b> An owner asking for its own place again gets it; an owner
 * whose explicit name — or whose derived one, for a ref that digests onto another's — names somebody
 * else's running container is asking for a thing that cannot exist, and no retry changes it. WP4's
 * REST layer maps this to a 409 with {@code NAME_TAKEN} on it, which is the word a consumer branches
 * on: qits-projects already pre-checks for exactly this squatter and turns it into its own 409.
 *
 * <p>Its own type rather than a {@link SpecConflictException}: that one is about a <em>policy</em>
 * that cannot answer a recreate, and this is about a <em>name</em> another workload is using. A
 * caller told SPEC_CONFLICT would delete and ask again under a new ref, which fixes nothing here.
 */
public class NameTakenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public NameTakenException(String message) {
    super(message);
  }
}
