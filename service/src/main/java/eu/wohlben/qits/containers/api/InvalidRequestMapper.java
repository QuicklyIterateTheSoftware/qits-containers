package eu.wohlben.qits.containers.api;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Every belt this service has throws {@link IllegalArgumentException}, and this is where that
 * becomes a 400.
 *
 * <p><b>The exception type is the contract rather than a wrapper class</b>, unlike qits-ci's
 * {@code CiException} and qits-platform-deployments' {@code PdException}, which carry their own
 * status codes. The reason is where the checks live: {@code ContainersIdentifiers} is called from
 * the API layer <em>and</em> from {@code DockerArgv}, so a refusal has to be a type {@code core}
 * can throw without knowing an HTTP status exists. There is exactly one status a refused value can
 * mean, so no code is carried.
 *
 * <p>The message is the belt's own — it names the field and echoes the offered value with its
 * control characters stripped and its length capped, which is what makes it safe to put in a body.
 */
@Provider
public class InvalidRequestMapper implements ExceptionMapper<IllegalArgumentException> {

  @Override
  public Response toResponse(IllegalArgumentException exception) {
    String message =
        exception.getMessage() == null || exception.getMessage().isBlank()
            ? "Invalid request"
            : exception.getMessage();
    return Response.status(Response.Status.BAD_REQUEST)
        .entity(new ContainersWire.ErrorBody(ContainersWire.INVALID, message))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
