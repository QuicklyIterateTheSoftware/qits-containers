package eu.wohlben.qits.containers.api;

import eu.wohlben.qits.containers.control.SpecConflictException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * A recreate the workload's lifecycle policy cannot answer: 409, code {@code SPEC_CONFLICT}.
 *
 * <p>409 rather than 400, because nothing about the request is malformed — the same body would be
 * accepted for a workload under any other policy. What refuses it is the state of the place: an
 * {@code EPHEMERAL} workload ran once, and a replacement would do the work a second time.
 */
@Provider
public class SpecConflictMapper implements ExceptionMapper<SpecConflictException> {

  @Override
  public Response toResponse(SpecConflictException exception) {
    return Response.status(Response.Status.CONFLICT)
        .entity(
            new ContainersWire.ErrorBody(ContainersWire.SPEC_CONFLICT, exception.getMessage()))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
