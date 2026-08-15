package eu.wohlben.qits.containers.proxy;

/**
 * The reverse tunnel's wire contract: the two paths, the two frame names, the field names and the
 * handshake header. One class, so a rename is one edit and a drift is impossible.
 *
 * <h2>This file is APPEND-ONLY</h2>
 *
 * <p>Every value here is <b>baked into a container as an environment variable at creation</b>, and
 * only a recreate re-injects it. So a container started this morning is still dialling the string
 * this file held this morning, and changing what a name means — rather than adding a new one — is
 * a change that breaks every container already running, silently, with the first symptom being a
 * daemon that dials and is never admitted.
 *
 * <p>Three rules follow, and they are the same three qits-projects' and qits-workspaces'
 * {@code DaemonProtocol} carry (this is that discipline, adopted rather than reinvented):
 *
 * <ul>
 *   <li><b>Add, never repurpose.</b> A new frame is a new constant. A field that has to mean
 *       something else is a new field beside the old one.
 *   <li><b>A behaviour change bumps {@link #CAPABILITY_VERSION}</b>, and the host branches on the
 *       version a daemon announces rather than assuming.
 *   <li><b>Nothing here is derived.</b> The stream prefix is built on the control prefix so the two
 *       cannot drift apart, and that is the only derivation there is.
 * </ul>
 */
public final class TunnelProtocol {

  /**
   * The contract version a daemon announces in its {@code hello}, and the floor this host serves a
   * stream to. Starts at <b>1</b>: it is this repository's own protocol and continues nobody's
   * numbering — the workspace and project daemons share a shape with it and not a namespace.
   *
   * <p>1 is the whole of the skeleton: dial the control socket with a secret, say hello, receive
   * {@code openStream}, dial back. A daemon announcing less than this is not reachable through a
   * tunnel, and {@link ContainerTunnels#originFor} says so rather than inventing an address.
   */
  public static final int CAPABILITY_VERSION = 1;

  /**
   * Where a container's daemon dials home: {@code /containers/tunnel/{rowId}}, the row id this
   * service minted for its place.
   *
   * <p>A {@code @WebSocket} path is a literal that does <b>not</b> follow {@code quarkus.rest.path},
   * so it carries the {@code /containers} segment itself — the same rule every raw route and socket
   * in the fleet obeys.
   */
  public static final String CONTROL_SOCKET_PATH_PREFIX = "/containers/tunnel/";

  /**
   * Where the dial-back lands: {@code /containers/tunnel/stream/{nonce}}.
   *
   * <p>Built on the control prefix so the two cannot drift, and it does not collide with it:
   * {@code {rowId}} matches exactly one segment and a row id is a UUID, so no row can be named
   * {@code stream}. {@link TunnelStreamRoute} registers ahead of the framework's own route as well,
   * so the two are unambiguous by ordering and by segment count both.
   */
  public static final String STREAM_PATH_PREFIX = CONTROL_SOCKET_PATH_PREFIX + "stream/";

  /**
   * The per-tunnel secret, presented as a handshake header on the control socket.
   *
   * <p><b>This is where this contract deliberately departs from the two it is ported from.</b>
   * qits-projects' and qits-workspaces' control sockets require the same machine role and name their caller with a
   * <em>path parameter</em>, so anything on {@code qits-net} can claim to be any project's or any
   * workspace's daemon — a known weakness both repos carry and record. This is a fresh contract with
   * no running container to be compatible with, so it does not reproduce it: the row id in the path
   * says which place is being claimed, and this header is the evidence for the claim.
   *
   * <p>What the secret is, and what it is not, is in {@link ContainerTunnels#issueSecret}.
   */
  public static final String SECRET_HEADER = "X-Qits-Tunnel-Secret";

  private TunnelProtocol() {}

  /** The {@code "type"} discriminator values. */
  public static final class Type {

    /** daemon &rarr; host, once, first: {@code {type, capabilityVersion}}. */
    public static final String HELLO = "hello";

    /** host &rarr; daemon: {@code {type, nonce, path}} — come and collect a parked connection. */
    public static final String OPEN_STREAM = "openStream";

    private Type() {}
  }

  /** The JSON field names both ends read. */
  public static final class Field {
    public static final String TYPE = "type";
    public static final String CAPABILITY_VERSION = "capabilityVersion";
    public static final String NONCE = "nonce";
    public static final String PATH = "path";

    private Field() {}
  }

  /**
   * The close codes and reasons a refused dial carries. 1008 is policy violation, which is what all
   * three of these are.
   *
   * <p><b>The two reasons are deliberately unequal in what they reveal.</b> The gate names itself,
   * because a daemon dialling a service whose data plane is switched off has a configuration problem
   * and nothing is learned by hiding it. Everything else — an unparseable row id, a row no live
   * place names, a secret that does not match — is one indistinguishable refusal, so a caller that
   * guessed wrong learns that it was wrong and not which half it got right.
   */
  public static final class Close {

    public static final int POLICY_VIOLATION = 1008;

    /** The gate: {@code qits.containers.proxy.enabled} is off. */
    public static final String DISABLED = "container proxy disabled";

    /** Everything else. See the class javadoc on why there is only one of these. */
    public static final String REFUSED = "tunnel refused";

    private Close() {}
  }
}
