package eu.wohlben.qits.containers.proxy;

import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Where a container's dial-back lands: {@code /containers/tunnel/stream/{nonce}}. The nonce names a
 * TCP connection {@link ContainerTunnels} parked when a caller opened one, and this route marries
 * the two into a byte pipe. Ported from qits-projects' and qits-workspaces' {@code
 * DaemonStreamRoute}, which are the same file twice.
 *
 * <h2>A raw Vert.x route, not websockets-next</h2>
 *
 * <p>{@code io.quarkus.websockets.next.Connection} exposes {@code sendBinary(Buffer) → Uni} and
 * nothing else — no {@code writeQueueFull}, no {@code drainHandler}. A byte tunnel without a
 * backpressure signal is an unbounded heap buffer, so the framework's own socket abstraction is the
 * one thing this cannot be built on, even though the extension is in this module for the control
 * plane next door. {@code request.toWebSocket()} yields a {@link ServerWebSocket}, which is a proper
 * {@code WriteStream} and makes both ends of the pipe the same types.
 *
 * <p>It shares a prefix with {@link TunnelControlSocket}'s {@code /containers/tunnel/{rowId}} and
 * does not collide: {@code {rowId}} matches exactly one segment, so no row can be named {@code
 * stream}. This route is registered ahead of the framework's own so the two never race.
 *
 * <h2>The nonce is the whole authentication of the dial-back</h2>
 *
 * <p>It is host-minted, single-use (the claim is an atomic map removal), short-lived, and bound to
 * the row it was sent to. It names nothing and carries no identity — deliberately, and it stays that
 * way even though this contract's control socket <em>does</em> authenticate: a dial-back that
 * repeated the credential would be a second place for it to leak, and one that named its own row
 * would be a second place to impersonate. An unknown or already-claimed nonce gets a bare 404 that
 * says nothing about which of the two it was.
 *
 * <p>With {@code qits.containers.proxy.enabled} off, every nonce is unknown by construction — none
 * is ever minted — and this route 404s before it even asks.
 */
@ApplicationScoped
public class TunnelStreamRoute {

  private static final Logger LOG = Logger.getLogger(TunnelStreamRoute.class);

  /**
   * Ahead of websockets-next' own registration, so the prefix is unambiguous by ordering as well as
   * by segment count.
   */
  private static final int ROUTE_ORDER = 100;

  @Inject ContainerTunnels tunnels;

  void init(@Observes Router router) {
    router.route(TunnelProtocol.STREAM_PATH_PREFIX + "*").order(ROUTE_ORDER).handler(this::handle);
  }

  private void handle(RoutingContext rc) {
    if (!tunnels.enabled()) {
      rc.response().setStatusCode(404).end();
      return;
    }
    // `route(PREFIX + "*")` also matches the bare prefix with no trailing slash, one character short
    // of the prefix itself — where the substring below would overflow. No segment means no nonce,
    // and it gets the same bare 404 an unknown nonce does.
    String path = rc.request().path();
    if (path.length() < TunnelProtocol.STREAM_PATH_PREFIX.length()) {
      rc.response().setStatusCode(404).end();
      return;
    }
    String nonce = path.substring(TunnelProtocol.STREAM_PATH_PREFIX.length());
    ContainerTunnels.Parked parked = tunnels.claim(nonce).orElse(null);
    if (parked == null) {
      rc.response().setStatusCode(404).end();
      return;
    }
    rc.request()
        .toWebSocket()
        .onFailure(
            t -> {
              LOG.debugf("A container stream upgrade failed: %s", String.valueOf(t));
              parked.socket().close();
            })
        .onSuccess(socket -> pipe(socket, parked));
  }

  /**
   * Pump bytes both ways. The mirror image of the container's own pump, and it has to be — the same
   * three constraints apply from this end.
   *
   * <p><b>{@code writeBinaryMessage}, never {@code write} or {@code pipeTo}.</b> A WebSocket's
   * {@code write(Buffer)} emits one frame of whatever length it is handed, and a {@code NetSocket}
   * read chunk sits at exactly Netty's 65536 default maximum frame size — so a large response would
   * trip the peer's limit and drop the socket. And {@code handler}, never {@code
   * binaryMessageHandler}: the latter aggregates and enforces a maximum message size, which a byte
   * stream has no business having.
   *
   * <p>The bytes the caller wrote before the container dialled back are replayed first, in order.
   * They are collected by an interim handler at accept time rather than left to a paused socket,
   * because the request line can genuinely arrive before there is anywhere to put it — and losing it
   * presents as a request that is never answered rather than as anything that looks like a bug.
   */
  private static void pipe(ServerWebSocket remote, ContainerTunnels.Parked parked) {
    NetSocket local = parked.socket();
    if (parked.early().length() > 0) {
      remote.writeBinaryMessage(parked.early());
    }
    remote.handler(
        buffer -> {
          local.write(buffer);
          if (local.writeQueueFull()) {
            remote.pause();
            local.drainHandler(v -> remote.resume());
          }
        });
    local.handler(
        buffer -> {
          remote.writeBinaryMessage(buffer);
          if (remote.writeQueueFull()) {
            local.pause();
            remote.drainHandler(v -> local.resume());
          }
        });
    remote.endHandler(v -> local.close());
    local.endHandler(v -> remote.close());
    remote.exceptionHandler(t -> local.close());
    local.exceptionHandler(t -> remote.close());
    remote.closeHandler(v -> local.close());
    local.closeHandler(v -> remote.close());
  }
}
