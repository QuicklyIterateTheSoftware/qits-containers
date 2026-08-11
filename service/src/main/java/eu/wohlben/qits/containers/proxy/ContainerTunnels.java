package eu.wohlben.qits.containers.proxy;

import io.quarkus.websockets.next.WebSocketConnection;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The host end of the reverse tunnel: a loopback {@link NetServer} per registry row whose accepted
 * TCP connections are handed to that row's container, which dials back and pipes them to whatever it
 * serves on its own loopback. A port of qits-projects' {@code AgentTunnels} and qits-workspaces'
 * {@code WorkspaceTunnels} — near-identical twins — keyed on a row id rather than on a project or a
 * workspace, because a row id is what this service mints and the only name a container is given.
 *
 * <h2>A SKELETON, and what that means for what is here</h2>
 *
 * <p>Everything below is gated on {@code qits.containers.proxy.enabled}, which ships <b>false</b>.
 * Off, nothing binds, nothing is minted and {@link #originFor} is empty for every id. The surface is
 * designed and minimally proven; the adoption is qits-workspaces' and qits-projects' to make, and
 * until one of them migrates there is no consumer to shape it around. What is deferred, and what it
 * will take, is in the README under "The data plane".
 *
 * <h2>Why a loopback listener, of all things</h2>
 *
 * <p>Because the host has to speak HTTP over a connection it did not initiate, and Vert.x has no API
 * for "an {@code HttpClient} over a socket I supply" — {@code HttpProxy} offers {@code origin(…)},
 * {@code originSelector(…)} and {@code originRequestProvider(…)}, all of which want a real address.
 * A loopback {@code NetServer} <em>is</em> a real address, so a consumer's proxy route stays an
 * ordinary reverse proxy and the only thing that changes is which host:port it points at.
 *
 * <p>It also means the tunnel carries bytes rather than framed requests, which is what lets a
 * WebSocket upgrade traverse it unchanged. {@code vertx-http-proxy} already turns an upgraded
 * exchange into a raw byte pipe, so the two compose instead of fighting.
 *
 * <h2>One HttpClient per row, and why it is not an optimisation to share</h2>
 *
 * <p>An ephemeral port is reused. Row A's tunnel closes, the OS later hands the same port to row
 * B's, and a pool keyed on {@code (host, port)} may still hold a live connection wired through to
 * <em>A's</em> container — which it would then hand to a request for B. Here that is a
 * <b>cross-tenant</b> read: A and B need not even have the same owner, so it is one module reading
 * inside another module's container, arrived at without anything being misconfigured. So each tunnel
 * owns its client, created and closed with it, and every accepted socket is closed explicitly at
 * teardown ({@code NetServer.close()} closes the listening channel only; accepted sockets survive
 * it). That is why {@link ProxyOrigin} carries the client and not only the port, and why a caller
 * <b>must</b> use the one it was given.
 *
 * <h2>All of this state is in memory, and that is the restart story rather than a gap</h2>
 *
 * <p>The durable fact this service keeps is <b>which containers exist</b> — that is what the rows
 * are, and it is what makes a restart adopt rather than guess. A socket is not that kind of fact: it
 * dies with the process at both ends, so there is nothing about a live tunnel that could usefully
 * outlive one. A restart therefore rebuilds the whole of this lazily — daemons re-dial, the control
 * connections come back, and the first request through {@link #originFor} binds a listener again.
 * Nothing here has to be reconciled with anything, and nothing may be persisted "for" it.
 */
@ApplicationScoped
public class ContainerTunnels {

  private static final Logger LOG = Logger.getLogger(ContainerTunnels.class);

  /** How long a bind may take before it is reported as a tunnel that could not be opened. */
  private static final long BIND_TIMEOUT_SECONDS = 10;

  /**
   * An INSTANCE field, and it must stay one. A {@code static final SecureRandom} is initialized by
   * the class initializer, which native-image runs during the <em>build</em> — so the seeded
   * instance lands in the image heap and the build aborts outright with "Detected an instance of
   * Random/SplittableRandom class in the image heap". A CDI bean is constructed at runtime, so as a
   * field of the bean it never reaches the heap the builder writes. That is also why {@link #mint}
   * is not static.
   *
   * <p>GraalVM refusing this case is the one mercy here — a generator with a build-time seed would
   * be a credential identical in every deployment of the same image, and both things it mints (a
   * stream nonce and a tunnel secret) are bearer credentials.
   */
  private final SecureRandom random = new SecureRandom();

  @Inject Vertx vertx;

  /**
   * The gate, shipped false in {@code core}'s ordinal-100 defaults. Off is not a degraded mode: no
   * listener is bound, no secret is issued, the control socket refuses every dial and the dial-back
   * route 404s. See the class javadoc.
   */
  @ConfigProperty(name = "qits.containers.proxy.enabled")
  boolean enabled;

  /** How long a minted nonce stays claimable. See the key's own comment in {@code core}. */
  @ConfigProperty(name = "qits.containers.proxy.nonce-ttl-ms")
  long nonceTtlMs;

  private final ConcurrentHashMap<UUID, Tunnel> tunnels = new ConcurrentHashMap<>();

  /** Minted-but-unclaimed nonces, across every row. Single-use by construction. */
  private final ConcurrentHashMap<String, Parked> pending = new ConcurrentHashMap<>();

  /** The live control connection per row, or nothing. A row with none is not reachable. */
  private final ConcurrentHashMap<UUID, Connected> connections = new ConcurrentHashMap<>();

  /** The secret each row's container must present to claim its control socket. */
  private final ConcurrentHashMap<UUID, String> secrets = new ConcurrentHashMap<>();

  /**
   * One row's control connection: the socket, an identity for it, and the capability it announced.
   *
   * <p>{@code connectionId} is the framework's own per-connection id and it is what a tunnel is
   * pinned to. The ported sources use the connection's timestamp for this; the id says the same
   * thing and cannot collide, which a timestamp can — two reconnects inside one clock tick would
   * read as the same connection and the second would inherit the first's parked sockets.
   */
  private record Connected(WebSocketConnection connection, String connectionId, int capability) {}

  /** One row's tunnel: its listener, its client, and the sockets it has accepted. */
  private static final class Tunnel {
    private final NetServer server;
    private final HttpClient client;

    /**
     * The control connection this tunnel belongs to. A reconnect mints a new one, and a tunnel whose
     * connection has been replaced must be rebuilt rather than reused — its parked sockets would be
     * waiting on a socket that is gone.
     */
    private final String connectionId;

    private final Set<NetSocket> accepted = Collections.newSetFromMap(new ConcurrentHashMap<>());

    Tunnel(NetServer server, HttpClient client, String connectionId) {
      this.server = server;
      this.client = client;
      this.connectionId = connectionId;
    }

    void close() {
      // Explicitly, and before the server: NetServer.close() closes only the listening channel, so
      // an accepted socket would otherwise outlive its tunnel and keep a pooled connection alive
      // against a port the OS is free to hand to another row.
      accepted.forEach(NetSocket::close);
      accepted.clear();
      client.close();
      server.close();
    }
  }

  /**
   * A TCP connection waiting for its container to dial back, and whatever the caller has already
   * written to it.
   *
   * <p>The buffer is not an optimisation — it is the fix for a race that presents as the request
   * simply never being answered. A proxy writes its request bytes as soon as it connects, which can
   * be before the container has dialled back and before any handler exists to receive them. Pausing
   * the socket is not enough on its own, so an interim handler collects whatever arrives and
   * {@link TunnelStreamRoute} replays it before wiring the two ends together.
   */
  record Parked(UUID rowId, NetSocket socket, long timerId, Buffer early) {}

  /**
   * One row's tunnel entrance: the loopback port to target, and the client that <b>must</b> be used
   * to target it. See the class javadoc on why the two travel together.
   */
  public record ProxyOrigin(HttpClient client, int port) {}

  // ---------------------------------------------------------------------------------------------
  // the per-tunnel secret
  // ---------------------------------------------------------------------------------------------

  /**
   * Mint this row's tunnel secret, replacing any it already had, or empty when the gate is off.
   *
   * <p><b>Who calls this, and when, is deliberately not settled yet.</b> The intended caller is
   * {@code ensure}: with the proxy enabled, the envelope a place is created with carries the secret,
   * the container is started with it in its environment, and it dials home holding it. That is a
   * change to the wire the client and the service both restate, so it lands with the first consumer
   * rather than in a skeleton nothing calls.
   *
   * <p><b>The durable-secret question is deferred, and the deferral has a default.</b> Two shapes
   * are available and only one of them needs a migration:
   *
   * <ul>
   *   <li><b>A column on the row.</b> One secret per place, surviving a restart of this service, so
   *       a container started before the restart keeps dialling successfully with what it already
   *       holds. It costs a migration, and it costs storing a live credential in the table whose
   *       whole design decision is that it stores no credentials — {@code spec_json} drops env for
   *       exactly that reason.
   *   <li><b>Re-issue on adopt</b>, which is what this method is shaped for and the restart-safe
   *       default. A control socket dies with the process at both ends, so after a restart of this
   *       service every daemon has to re-dial anyway — and the boot sweep already adopts every row
   *       whose container is still running, which is precisely the moment a fresh secret can be
   *       handed to a container that is already up. The container needs a way to be <em>told</em> a
   *       new secret, which is the one piece of protocol round 2 has to add.
   * </ul>
   *
   * <p>Until then this is in memory, and a restart of this service means every container re-ensures
   * before it can dial. That is honest for a skeleton no consumer runs and would not be for a live
   * data plane.
   */
  public Optional<String> issueSecret(UUID rowId) {
    if (!enabled || rowId == null) {
      return Optional.empty();
    }
    String secret = mint();
    secrets.put(rowId, secret);
    return Optional.of(secret);
  }

  /**
   * Whether {@code presented} is the secret this row was issued.
   *
   * <p>Constant-time, through {@link MessageDigest#isEqual}: a byte-by-byte comparison against a
   * bearer credential leaks its prefix to anything that can dial repeatedly, and anything on the
   * platform's network can. A row that was never issued a secret admits nothing, so "the gate is on
   * but nothing minted for this row" fails closed.
   */
  boolean admits(UUID rowId, String presented) {
    if (!enabled || rowId == null || presented == null) {
      return false;
    }
    String issued = secrets.get(rowId);
    return issued != null
        && MessageDigest.isEqual(
            issued.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }

  /** Forget this row's secret — a place being deleted, and every teardown that follows one. */
  void forgetSecret(UUID rowId) {
    secrets.remove(rowId);
  }

  // ---------------------------------------------------------------------------------------------
  // the control connection
  // ---------------------------------------------------------------------------------------------

  /** Record an admitted control connection, replacing whatever this row had. */
  void register(UUID rowId, WebSocketConnection connection) {
    connections.put(rowId, new Connected(connection, connection.id(), 0));
    LOG.debugf("A container dialled home for row %s", rowId);
  }

  /**
   * A {@code hello}: this connection announces what it can do.
   *
   * <p>Recorded against the connection it arrived on, so a frame from a socket that has already been
   * replaced cannot raise the capability of the one that replaced it.
   */
  void onHello(UUID rowId, WebSocketConnection connection, int capability) {
    connections.computeIfPresent(
        rowId,
        (id, live) ->
            live.connection().id().equals(connection.id())
                ? new Connected(live.connection(), live.connectionId(), capability)
                : live);
  }

  /**
   * A control connection went away: drop it, and drop its <b>pending</b> nonces.
   *
   * <p>Live tunnels deliberately survive. Each stream is an independent TCP connection, so a control
   * socket bouncing through a reconnect leaves an open stream open — which is the whole reason these
   * calls do not ride the control socket in the first place, and would be quietly undone by tearing
   * tunnels down here. The next {@link #originFor} rebuilds the tunnel because the connection id
   * moved, which is the only place that decision belongs.
   */
  void unregister(UUID rowId, WebSocketConnection connection) {
    connections.computeIfPresent(
        rowId, (id, live) -> live.connection().id().equals(connection.id()) ? null : live);
    pending.forEach(
        (nonce, parked) -> {
          if (parked.rowId().equals(rowId) && pending.remove(nonce, parked)) {
            vertx.cancelTimer(parked.timerId());
            parked.socket().close();
          }
        });
  }

  // ---------------------------------------------------------------------------------------------
  // the tunnel
  // ---------------------------------------------------------------------------------------------

  /**
   * Where to reach this row's container through the tunnel, or empty when it cannot be reached this
   * way — the gate is off, no container has dialled home, or the one that has is too old to serve a
   * stream.
   *
   * <p><b>A live control connection is what proves the container is up</b>, which is why this asks
   * the connection table rather than docker: it is both stronger evidence and one less round trip
   * per request. A row saying {@code RUNNING} is this service's last observation; a socket is the
   * container answering now.
   *
   * <p>Blocking (it awaits a bind on first use), so call it off the event loop.
   */
  public Optional<ProxyOrigin> originFor(UUID rowId) {
    if (!enabled || rowId == null) {
      return Optional.empty();
    }
    Connected live = connections.get(rowId);
    if (live == null || live.capability() < TunnelProtocol.CAPABILITY_VERSION) {
      // No container, or one that has not said hello yet. Capability 1 is the whole contract, so
      // there is no older shape to fall back to and no direct address to try: a container's own API
      // binds loopback, which is what the tunnel exists for. This row is simply not reachable, and
      // the caller says so rather than inventing an address.
      closeTunnel(rowId);
      return Optional.empty();
    }
    Tunnel existing = tunnels.get(rowId);
    if (existing != null && existing.connectionId.equals(live.connectionId())) {
      return Optional.of(originOf(existing));
    }
    closeTunnel(rowId);
    try {
      return Optional.of(originOf(openTunnel(rowId, live.connectionId())));
    } catch (RuntimeException e) {
      LOG.warnf(e, "Could not open a container tunnel for row %s", rowId);
      return Optional.empty();
    }
  }

  private static ProxyOrigin originOf(Tunnel tunnel) {
    return new ProxyOrigin(tunnel.client, tunnel.server.actualPort());
  }

  private Tunnel openTunnel(UUID rowId, String connectionId) {
    // 127.0.0.1 is a literal and not a config key on purpose: a configurable bind address here would
    // be an SSRF footgun with no caller asking for it.
    NetServer server = vertx.createNetServer();
    server.connectHandler(socket -> onAccepted(rowId, socket));
    NetServer bound = await(server.listen(0, "127.0.0.1"));
    HttpClient client = vertx.createHttpClient(new HttpClientOptions().setKeepAlive(true));
    Tunnel tunnel = new Tunnel(bound, client, connectionId);
    tunnels.put(rowId, tunnel);
    LOG.debugf(
        "The tunnel for row %s is listening on 127.0.0.1:%s",
        rowId, Integer.valueOf(bound.actualPort()));
    return tunnel;
  }

  /**
   * One accepted connection: park it, ask its container to come and get it.
   *
   * <p>Runs on an event loop, so the {@code openStream} goes out without being awaited.
   *
   * <p>The nonce is registered <em>before</em> the message goes out. That ordering is the only one
   * that works: a dial-back can arrive before the send's own callback does.
   */
  private void onAccepted(UUID rowId, NetSocket socket) {
    Tunnel tunnel = tunnels.get(rowId);
    if (tunnel == null) {
      socket.close();
      return;
    }
    tunnel.accepted.add(socket);
    socket.closeHandler(v -> tunnel.accepted.remove(socket));
    // Collect whatever the caller writes before the far end exists; TunnelStreamRoute replays it.
    Buffer early = Buffer.buffer();
    socket.handler(early::appendBuffer);

    String nonce = mint();
    long timerId =
        vertx.setTimer(
            nonceTtlMs,
            id -> {
              Parked expired = pending.remove(nonce);
              if (expired != null) {
                // Nobody came. Closing is what turns this into a connection error at the caller
                // rather than a request that hangs until some other timeout notices.
                LOG.debugf("A tunnel stream for row %s expired unclaimed", rowId);
                expired.socket().close();
              }
            });
    pending.put(nonce, new Parked(rowId, socket, timerId, early));
    requestStream(rowId, nonce);
  }

  /** Push one {@code openStream} down the control socket, without awaiting it. */
  private void requestStream(UUID rowId, String nonce) {
    Connected live = connections.get(rowId);
    if (live == null) {
      return;
    }
    JsonObject frame =
        new JsonObject()
            .put(TunnelProtocol.Field.TYPE, TunnelProtocol.Type.OPEN_STREAM)
            .put(TunnelProtocol.Field.NONCE, nonce)
            .put(TunnelProtocol.Field.PATH, TunnelProtocol.STREAM_PATH_PREFIX + nonce);
    live.connection()
        .sendText(frame.encode())
        .subscribe()
        .with(
            ignored -> {},
            failure ->
                LOG.debugf(
                    "Could not ask row %s for a stream: %s", rowId, String.valueOf(failure)));
  }

  /**
   * Claim a nonce, once. The atomic {@code remove} is what makes single-use structural rather than a
   * rule someone has to remember — a replayed nonce finds nothing, and so does a nonce minted while
   * the gate was on and replayed after it went off.
   */
  Optional<Parked> claim(String nonce) {
    Parked parked = !enabled || nonce == null ? null : pending.remove(nonce);
    if (parked != null) {
      vertx.cancelTimer(parked.timerId());
    }
    return Optional.ofNullable(parked);
  }

  /** Tear a row's tunnel down — on a replaced control connection, and when its place is deleted. */
  public void closeTunnel(UUID rowId) {
    Tunnel gone = tunnels.remove(rowId);
    if (gone != null) {
      gone.close();
    }
  }

  /** Whether the data plane is on at all. Read by the socket and the route, which both gate on it. */
  boolean enabled() {
    return enabled;
  }

  /** How many loopback listeners are bound. Observational, and what the gate's test asserts on. */
  int openTunnels() {
    return tunnels.size();
  }

  /**
   * 32 bytes of {@link SecureRandom}, base64url. Not a {@code UUID}: both things this mints are
   * bearer credentials, and this codebase spells correlation ids as UUIDs — using one here would
   * make the wrong thing look right to the next reader.
   *
   * <p>Not static, and not by accident — see {@link #random}.
   */
  private String mint() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static <T> T await(io.vertx.core.Future<T> future) {
    try {
      return future.toCompletionStage().toCompletableFuture().get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted opening a container tunnel", e);
    } catch (Exception e) {
      throw new IllegalStateException("Could not open a container tunnel", e);
    }
  }

  @PreDestroy
  void closeAll() {
    tunnels.keySet().forEach(this::closeTunnel);
    pending.values().forEach(parked -> parked.socket().close());
    pending.clear();
    connections.clear();
    secrets.clear();
  }
}
