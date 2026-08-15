package eu.wohlben.qits.containers.proxy;

import eu.wohlben.qits.containers.control.ContainerRegistry;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.PathParam;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.common.annotation.RunOnVirtualThread;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The endpoint a container's daemon dials on boot: {@code /containers/tunnel/{rowId}}. It owns only
 * the WebSocket lifecycle and the two frames of {@link TunnelProtocol}; {@link ContainerTunnels}
 * owns the state.
 *
 * <h2>The gate is a refusal at open, and it has to be</h2>
 *
 * <p>A websockets-next endpoint is registered at <b>augmentation</b> and there is no conditional
 * registration: the class is on the classpath, so the route exists whatever
 * {@code qits.containers.proxy.enabled} says. Pretending otherwise would mean a socket that upgrades
 * and then does nothing, which is the worst of the three available behaviours. So the honest gate is
 * this: the upgrade completes, and the connection is closed at once with a reason that names the
 * switch. {@code TunnelGateTest} asserts exactly that, because a gate nobody measures is a gate that
 * quietly stops being one.
 *
 * <h2>Identity is a claim in the path and evidence in a header</h2>
 *
 * <p>The row id in the path says which place is being claimed. It is not the authentication, and
 * this is the one place this port deliberately departs from what it was ported from: qits-projects'
 * {@code AgentControlSocket} and qits-workspaces' {@code DaemonControlSocket} require the same
 * machine role. There is no container running against <em>this</em> contract yet, so there is
 * nothing to be compatible with and no reason to weaken it:
 * {@link TunnelProtocol#SECRET_HEADER} carries a per-tunnel secret and it is checked first.
 *
 * <p><b>The order of the three checks is deliberate.</b> The gate, then the secret, then the row. A
 * dial with no valid secret therefore costs no database query at all, which is what keeps an
 * unauthenticated caller from turning a dial loop into load on the registry.
 *
 * <p>The row read is the last check because it is the expensive one and because it is the one that
 * can be <em>wrong</em> in a way the others cannot: {@link ContainerRegistry#place} answers empty
 * only for a row that is really absent and throws when it could not ask, so a database blip refuses
 * loudly rather than disconnecting every healthy container. It is deliberately <em>not</em> the
 * cheaper direct repository read: an empty answer here disconnects a daemon, which is exactly the
 * confusion between "gone" and "could not find out" this repository refuses everywhere else.
 */
@WebSocket(path = TunnelProtocol.CONTROL_SOCKET_PATH_PREFIX + "{rowId}")
@jakarta.annotation.security.RolesAllowed("qits:system")
public class TunnelControlSocket {

  private static final Logger LOG = Logger.getLogger(TunnelControlSocket.class);

  /**
   * A courtesy gets a deadline. The peer being refused here is by definition one this host has no
   * reason to trust, and a close it could hang would pin a virtual thread per dial.
   */
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

  /**
   * The row this connection was admitted under, stashed on the connection at open. Every later
   * callback reads it from here rather than re-parsing the path: a parse that succeeded once is not
   * a fact worth establishing twice, and a frame arriving on a connection that carries no key is a
   * frame from a dial that was refused.
   */
  private static final UserData.TypedKey<String> ROW_ID = UserData.TypedKey.forString("rowId");

  @Inject ContainerTunnels tunnels;

  @Inject ContainerRegistry registry;

  @OnOpen
  @RunOnVirtualThread
  public void onOpen(@PathParam("rowId") String rowId, WebSocketConnection connection) {
    if (!tunnels.enabled()) {
      close(connection, TunnelProtocol.Close.DISABLED);
      return;
    }
    UUID id = parse(rowId);
    if (id == null) {
      refuse(connection, rowId, "the row id is not a UUID");
      return;
    }
    if (!tunnels.admits(id, connection.handshakeRequest().header(TunnelProtocol.SECRET_HEADER))) {
      refuse(connection, rowId, "the tunnel secret does not match");
      return;
    }
    if (registry.place(id).isEmpty()) {
      refuse(connection, rowId, "no live row names it");
      return;
    }
    connection.userData().put(ROW_ID, id.toString());
    tunnels.register(id, connection);
  }

  @OnTextMessage
  @RunOnVirtualThread
  public void onMessage(String message, WebSocketConnection connection) {
    String rowId = connection.userData().get(ROW_ID);
    if (rowId == null) {
      // Not admitted — the close from @OnOpen may still be in flight. Read nothing from it.
      return;
    }
    JsonObject frame;
    try {
      frame = new JsonObject(message);
    } catch (RuntimeException e) {
      // One bad frame costs the frame and never the connection: a container running somebody else's
      // code is expected to be able to send nonsense.
      LOG.debugf("Dropped an unreadable tunnel frame for row %s: %s", rowId, e.getMessage());
      return;
    }
    if (TunnelProtocol.Type.HELLO.equals(frame.getString(TunnelProtocol.Field.TYPE))) {
      tunnels.onHello(
          UUID.fromString(rowId),
          connection,
          frame.getInteger(TunnelProtocol.Field.CAPABILITY_VERSION, Integer.valueOf(0)).intValue());
    }
    // Anything else is a frame this version does not know, which is ordinary traffic for an
    // append-only contract: a newer daemon may say more than this host reads.
  }

  @OnClose
  public void onClose(WebSocketConnection connection) {
    String rowId = connection.userData().get(ROW_ID);
    if (rowId != null) {
      tunnels.unregister(UUID.fromString(rowId), connection);
    }
  }

  private static UUID parse(String rowId) {
    try {
      return UUID.fromString(rowId);
    } catch (RuntimeException notAUuid) {
      return null;
    }
  }

  /**
   * Refuse a dial. One close reason for all three refusals, so a caller that guessed wrong learns
   * that it was wrong and not which half it got right; the real reason goes to the log, where the
   * operator of this service can read it and the caller cannot.
   */
  private void refuse(WebSocketConnection connection, String rowId, String why) {
    LOG.warnf(
        "Refused a container tunnel dial from %s for row '%s': %s",
        connection.handshakeRequest().remoteAddress(), rowId, why);
    close(connection, TunnelProtocol.Close.REFUSED);
  }

  private void close(WebSocketConnection connection, String reason) {
    try {
      connection
          .close(new CloseReason(TunnelProtocol.Close.POLICY_VIOLATION, reason))
          .await()
          .atMost(CLOSE_TIMEOUT);
    } catch (RuntimeException e) {
      // Includes the deadline expiring. The socket is abandoned either way; the peer's opinion of
      // the handshake stops being this host's problem here.
      LOG.debugf("Closing a refused tunnel dial did not complete: %s", e.getMessage());
    }
  }
}
