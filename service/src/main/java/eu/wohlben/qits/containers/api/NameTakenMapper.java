package eu.wohlben.qits.containers.api;

import eu.wohlben.qits.containers.control.NameTakenException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A name a live container of another place already holds: 409, code {@code NAME_TAKEN}.
 *
 * <p>409 rather than 400 for {@link SpecConflictMapper}'s reason: nothing about the request is
 * malformed — the same body would be accepted the moment the other container is gone. What refuses
 * it is the state of the host.
 *
 * <p><b>A coded answer rather than a raw 23505.</b> Until V3 the database refused this and every
 * delete-then-ensure besides, and a violation DbRetry rethrows reaches a caller as a 500 with a null
 * code — nothing a consumer can branch on. qits-projects already pre-checks for this squatter and
 * maps it to its own 409; the code is what makes that robust rather than a race between two reads.
 */
@Provider
public class NameTakenMapper implements ExceptionMapper<NameTakenException> {

  @Override
  public Response toResponse(NameTakenException exception) {
    return Response.status(Response.Status.CONFLICT)
        .entity(new ContainersWire.ErrorBody(ContainersWire.NAME_TAKEN, exception.getMessage()))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
