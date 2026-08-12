package eu.wohlben.qits.containers.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This jar's own JSON, and its own {@link ObjectMapper}.
 *
 * <p><b>Its own, rather than the consumer's CDI one.</b> Same stance as {@code CanonicalJson} in
 * qits-eventstream and for the same reason: an application's {@code ObjectMapperCustomizer}s can
 * reach the CDI mapper, and a customizer added for an unrelated feature would then change what this
 * client puts on a wire another service parses. It also keeps the client a plain class — injecting
 * a mapper would mean this jar needed a container to be constructed at all.
 *
 * <p><b>Two flags, and both are forward compatibility rather than leniency.</b>
 *
 * <ul>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES} off: the service adds a field to an envelope — the
 *       {@code endpoint.proxy} the data plane arrives behind is exactly that shape — and a client
 *       that refused the body would break every consumer on the day the service was deployed, in
 *       the direction the platform deploys in.
 *   <li>{@code READ_UNKNOWN_ENUM_VALUES_AS_NULL} on: the same for a word. {@code ObservedState} is
 *       a catalogue the service grows deliberately (its own column carries no check constraint for
 *       that reason), so a new state has to read as "a state this client does not know" and not as
 *       a broken response. It costs a possible null in {@link ContainersWire.State}, which is the
 *       cheaper of the two failures: a caller that switches over it falls through to its default
 *       branch, where a throw would have taken the request.
 * </ul>
 *
 * <p><b>Nulls are omitted on the way out.</b> A {@link ContainersWire.Spec} has fifteen fields and
 * a caller sets three of them; the service treats an absent field and an explicit null the same
 * way, so sending the nulls buys nothing and makes every request body unreadable in a log.
 *
 * <p><b>The mapper is static and that is fine in a native image</b> — it is the {@code HttpClient}
 * that may not be, because that one holds a selector and its threads. {@code CanonicalJson} has
 * held a static mapper through every native build of this platform. What a native consumer <em>does</em>
 * owe is a reflection registration for the records this mapper binds: see the README's client
 * section, and {@code EventWireReflection} for the worked example of the same debt.
 */
final class ContainersJson {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  private ContainersJson() {}

  /** A request body. Throws only for a value this jar itself cannot serialize, which is a bug. */
  static String write(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot serialize " + value.getClass().getName(), e);
    }
  }

  /**
   * A response body, or null when it will not bind. <b>Null rather than a throw</b>: the caller is
   * {@link ContainersClient}, which owes its own caller one of four answers and never an exception,
   * and a body it cannot read is a {@link ContainersAnswer.Refused} carrying
   * {@link ContainersWire#UNREADABLE}.
   */
  static <T> T read(String body, Class<T> type) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return MAPPER.readValue(body, type);
    } catch (Exception e) {
      return null;
    }
  }
}
