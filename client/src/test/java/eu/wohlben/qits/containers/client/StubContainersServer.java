package eu.wohlben.qits.containers.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A qits-containers that never leaves this JVM: the JDK's own {@link HttpServer} on an ephemeral
 * port, answering whatever a test scripts and recording exactly what arrived.
 *
 * <p><b>The JDK server rather than Vert.x</b>, which is what qits-eventstream's {@code
 * StubEventsServer} uses. The shape is the same — script the answers, assert on the requests — and
 * the difference is this module's dependency list: a client jar that pulled a web server into its
 * test scope to prove it can send a header would be a heavier thing to consume than it needs to be.
 * {@code jdk.httpserver} is in the JDK.
 *
 * <p><b>Deliberately dumb.</b> It routes nothing, validates nothing and holds no registry: a test
 * says which status and which body it wants back, and the client's job is to turn those into one of
 * four answers. What the real routes actually answer is proved against the real service in {@code
 * ContainersClientWireTest} — this stub exists for the cases a real service will not produce on
 * demand, and for reading the request headers off the wire.
 */
final class StubContainersServer implements AutoCloseable {

  /**
   * One request that arrived, as the stub saw it.
   *
   * <p><b>{@code path} and {@code query} are the RAW forms</b>, percent-escapes and all. Measured:
   * {@code URI.getPath()} decodes, so a stub reading it reports {@code run/../../volumes/other} for
   * a request that carried {@code run%2F..%2F..%2Fvolumes%2Fother} — the client's encoding made
   * invisible by the assertion meant to check it. What is on the wire is what the service routes on.
   */
  record Received(
      String method, String path, String query, Map<String, String> headers, String body) {

    /** A header by name, case-insensitively, or null. */
    String header(String name) {
      return headers.get(name);
    }
  }

  /** One answer a test has queued up. */
  record Scripted(int status, String body) {}

  private final HttpServer server;

  private final List<Received> received = Collections.synchronizedList(new ArrayList<>());

  private final Deque<Scripted> scripted = new ArrayDeque<>();

  /** What is answered once the script runs out: an empty JSON object, which binds to most records. */
  private Scripted fallback = new Scripted(200, "{}");

  StubContainersServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  /** Where this stub answers: scheme + host + port, no path — what the client's base URL is. */
  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  /** Queue one answer. They are handed out in order, then the fallback repeats. */
  StubContainersServer script(int status, String body) {
    synchronized (scripted) {
      scripted.add(new Scripted(status, body));
    }
    return this;
  }

  /** What every unscripted request gets. */
  StubContainersServer fallback(int status, String body) {
    fallback = new Scripted(status, body);
    return this;
  }

  List<Received> received() {
    return List.copyOf(received);
  }

  /** The last request, which is what a single-call test asserts on. */
  Received last() {
    List<Received> all = received();
    if (all.isEmpty()) {
      throw new AssertionError("nothing reached the stub");
    }
    return all.getLast();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      exchange
          .getRequestHeaders()
          .forEach((name, values) -> headers.put(name, values.isEmpty() ? "" : values.getFirst()));
      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      received.add(
          new Received(
              exchange.getRequestMethod(),
              exchange.getRequestURI().getRawPath(),
              exchange.getRequestURI().getRawQuery(),
              headers,
              body));

      Scripted answer;
      synchronized (scripted) {
        answer = scripted.isEmpty() ? fallback : scripted.poll();
      }
      byte[] out =
          answer.body() == null ? new byte[0] : answer.body().getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      // -1 is the JDK server's "no body at all", which is what a 204 has to be: a content-length of
      // zero on a 204 is a header the client would read as a body it should try to bind.
      exchange.sendResponseHeaders(answer.status(), out.length == 0 ? -1 : out.length);
      if (out.length > 0) {
        exchange.getResponseBody().write(out);
      }
    }
  }
}
