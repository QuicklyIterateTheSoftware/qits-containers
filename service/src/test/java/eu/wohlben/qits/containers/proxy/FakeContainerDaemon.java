package eu.wohlben.qits.containers.proxy;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A container daemon that never leaves this JVM: a real Vert.x WebSocket client dialling the real
 * control socket with the real handshake header, framing the real {@link TunnelProtocol} messages,
 * dialling the real stream path back and answering a canned HTTP response over the pipe. The host
 * cannot tell it from a container, which is the point — the whole data plane is provable with no
 * docker and no image.
 *
 * <p>The shape is qits-ci's {@code FakeCiDaemon} and the reasoning is the same one: it is
 * <b>deliberately dumb</b>. It holds no state machine and volunteers nothing, so a test scripts the
 * frames it wants including the wrong ones — which is how a refused dial and an unclaimed nonce are
 * testable at all. {@link #answerWith} being nullable is that rule applied to the dial-back: a fake
 * that always came to collect could not stage a nonce nobody claims.
 */
public final class FakeContainerDaemon implements AutoCloseable {

  private final Vertx vertx;
  private final WebSocketClient client;
  private final WebSocket control;
  private final String host;
  private final int port;

  private final BlockingQueue<JsonObject> received = new ArrayBlockingQueue<>(64);
  private final BlockingQueue<String> requests = new ArrayBlockingQueue<>(16);
  private final CompletableFuture<Short> closeCode = new CompletableFuture<>();
  private final CompletableFuture<String> closeReason = new CompletableFuture<>();

  /** What to write back over a claimed stream, or null to receive the ask and never come. */
  private volatile Buffer canned;

  /**
   * Dial the control socket with this secret (null sends no header at all).
   *
   * <p>Returns once the HTTP upgrade completed. A refused dial is a 1008 <b>close</b> after a
   * successful upgrade rather than a failed handshake — websockets-next registers the endpoint at
   * augmentation and refuses in {@code @OnOpen} — so a caller asserts on {@link #awaitCloseCode}
   * rather than on this throwing.
   */
  public static FakeContainerDaemon dial(URI endpoint, String secret) throws Exception {
    Vertx vertx = Vertx.vertx();
    try {
      WebSocketClient client = vertx.createWebSocketClient();
      WebSocketConnectOptions options =
          new WebSocketConnectOptions()
              .setHost(endpoint.getHost())
              .setPort(endpoint.getPort())
              .setURI(endpoint.getPath());
      if (secret != null) {
        options.addHeader(TunnelProtocol.SECRET_HEADER, secret);
      }
      WebSocket socket =
          client.connect(options).toCompletionStage().toCompletableFuture().get(20, TimeUnit.SECONDS);
      return new FakeContainerDaemon(vertx, client, socket, endpoint.getHost(), endpoint.getPort());
    } catch (Exception failedToUpgrade) {
      vertx.close();
      throw failedToUpgrade;
    }
  }

  private FakeContainerDaemon(
      Vertx vertx, WebSocketClient client, WebSocket control, String host, int port) {
    this.vertx = vertx;
    this.client = client;
    this.control = control;
    this.host = host;
    this.port = port;
    control.textMessageHandler(this::onFrame);
    control.closeHandler(
        ignored -> {
          closeCode.complete(control.closeStatusCode());
          closeReason.complete(control.closeReason());
        });
  }

  /** What this daemon answers a piped request with. Null means "record the ask and never come". */
  public FakeContainerDaemon answerWith(String httpResponse) {
    this.canned = httpResponse == null ? null : Buffer.buffer(httpResponse, "UTF-8");
    return this;
  }

  /** The one frame this contract's daemon sends unprompted. */
  public void sayHello(int capabilityVersion) throws Exception {
    send(
        new JsonObject()
            .put(TunnelProtocol.Field.TYPE, TunnelProtocol.Type.HELLO)
            .put(TunnelProtocol.Field.CAPABILITY_VERSION, Integer.valueOf(capabilityVersion)));
  }

  public void send(JsonObject frame) throws Exception {
    control
        .writeTextMessage(frame.encode())
        .toCompletionStage()
        .toCompletableFuture()
        .get(10, TimeUnit.SECONDS);
  }

  /** The next frame the host sent, or null if none arrived in time. */
  public JsonObject nextFrame(Duration timeout) throws InterruptedException {
    return received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** The bytes of the next request that arrived over a pipe, or null if none did. */
  public String nextRequest(Duration timeout) throws InterruptedException {
    return requests.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  public Short awaitCloseCode(Duration timeout) {
    return await(closeCode, timeout);
  }

  public String awaitCloseReason(Duration timeout) {
    return await(closeReason, timeout);
  }

  public boolean isOpen() {
    return !control.isClosed();
  }

  private static <T> T await(CompletableFuture<T> future, Duration timeout) {
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (Exception notCompleted) {
      return null;
    }
  }

  private void onFrame(String text) {
    JsonObject frame = new JsonObject(text);
    received.offer(frame);
    if (TunnelProtocol.Type.OPEN_STREAM.equals(frame.getString(TunnelProtocol.Field.TYPE))
        && canned != null) {
      collect(frame.getString(TunnelProtocol.Field.PATH));
    }
  }

  /**
   * Dial the path the host named and answer whatever comes down it.
   *
   * <p>{@code binaryMessageHandler} here and {@code writeBinaryMessage} back: the aggregation a real
   * daemon must not have is fine on this side, where the payloads are one small request each, and
   * the write has to be a message rather than a frame for the reason the host's own pipe states.
   */
  private void collect(String path) {
    client
        .connect(new WebSocketConnectOptions().setHost(host).setPort(port).setURI(path))
        .onSuccess(
            stream ->
                stream.binaryMessageHandler(
                    request -> {
                      requests.offer(request.toString(StandardCharsets.UTF_8));
                      stream.writeBinaryMessage(canned);
                    }));
  }

  @Override
  public void close() {
    try {
      control.close();
    } catch (RuntimeException alreadyGone) {
      // nothing to do
    }
    client.close();
    vertx.close();
  }
}
