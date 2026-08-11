package eu.wohlben.qits.containers.client;

/**
 * What came of one call. <b>Four answers, and collapsing any pair loses something.</b>
 *
 * <p>This is {@code EventsPublisher.Delivery}'s discipline applied to a request/response client, and
 * the one distinction it exists to protect is the last two:
 *
 * <ul>
 *   <li>{@link Created} — 201. The place was new, or the volume was made. Only {@code ensure} and
 *       {@code ensureVolume} can answer it, and only the first time.
 *   <li>{@link Ready} — 200 or 204. The service did the thing, or found it already done. An
 *       {@code ensure} whose container did not start is <em>here</em>, not in {@link Refused}: the
 *       row exists, it says {@code MISSING}, and it carries what docker said. That is a true answer
 *       the observer keeps working on rather than a request that failed.
 *   <li>{@link Refused} — a response arrived and said no. A 409 {@code SPEC_CONFLICT}, a 409
 *       {@code IMAGE_MISSING}, a 400 {@code INVALID}, a 403 from the owner guard, a 502 from a
 *       proxy, a 5xx from a service whose database is down. <b>Something is answering</b>, it
 *       understood the request enough to reject it, and the status and code say what to do.
 *   <li>{@link Unreachable} — no response at all. A refused connection, a name that does not
 *       resolve, a deadline that passed with nothing on the socket. <b>Nothing was learned about
 *       the workload, only about the network.</b>
 * </ul>
 *
 * <p><b>The last two used to be one, and merging them cost real events.</b> Measured on the
 * 2026-08-10 bootstraps, in the platform's other HTTP client: a publisher was pointed at an alias
 * that did not resolve, every attempt raised {@code ConnectException}, and five attempts later
 * every row was {@code FAILED} — a hole no bookkeeping recovers. A refusal is evidence about the
 * request; an unreachable service is evidence about nothing at all, and a bounded budget is only
 * meaningful against evidence. It matters more here than there: a caller that read an unreachable
 * orchestrator as a refusal would conclude its workload was never started, and start a second one.
 *
 * <p><b>There is deliberately no {@code retryable()}.</b> Two of these four warrant another attempt
 * and they warrant differently-shaped ones — one against a budget, one against a clock — so a
 * single predicate saying "there will be another attempt" is the exact shape in which the
 * distinction gets collapsed back by someone reading only the method name. Each answer has its own
 * question instead, and a {@code switch} over the four is the call shape this type is for.
 *
 * <p>The client <b>never throws</b> for any of these. A throw would be a fifth answer with no place
 * in the four, and it would arrive on the caller's own thread — for qits-ci, the single-threaded run
 * worker — where an unhandled one costs the pipeline rather than the request.
 *
 * @param <T> what a successful call carries back: an {@link ContainersWire.Envelope}, a list of
 *     them, a {@link ContainersWire.LogTail}, or {@link Void} for a route with no body
 */
public sealed interface ContainersAnswer<T> {

  /** 201: the place was new. Carries the envelope the service answered with. */
  record Created<T>(T value) implements ContainersAnswer<T> {}

  /** 200 or 204: the service did the thing, or found it already done. */
  record Ready<T>(T value) implements ContainersAnswer<T> {}

  /**
   * A response arrived and said no.
   *
   * @param status the HTTP status, so a caller can tell a 403 from a 502 without parsing text
   * @param code the service's own word — {@link ContainersWire#SPEC_CONFLICT},
   *     {@link ContainersWire#IMAGE_MISSING}, {@link ContainersWire#INVALID} — or
   *     {@link ContainersWire#UNREADABLE} when this client could not read the body at all. Never
   *     null: a refusal with no readable code carries the status as its word.
   * @param message the sentence a person reads, bounded and stripped of anything that could forge a
   *     second log line
   */
  record Refused<T>(int status, String code, String message) implements ContainersAnswer<T> {}

  /**
   * Nothing answered.
   *
   * @param cause what the JDK client said, for a log line. Not a status and not a code, because
   *     there was neither.
   */
  record Unreachable<T>(String cause) implements ContainersAnswer<T> {}

  /** 2xx: {@link Created} or {@link Ready}. What a caller checks before reading {@link #value()}. */
  default boolean succeeded() {
    return this instanceof Created<T> || this instanceof Ready<T>;
  }

  /** What the service answered with, or null when it did not answer successfully. */
  default T value() {
    return switch (this) {
      case Created<T> created -> created.value();
      case Ready<T> ready -> ready.value();
      default -> null;
    };
  }

  /** 201, and nothing else. The place was new — the first {@code ensure} of a run, of a workspace. */
  default boolean created() {
    return this instanceof Created<T>;
  }

  /** A response arrived saying no. This is the class a bounded retry budget is for. */
  default boolean refused() {
    return this instanceof Refused<T>;
  }

  /** Nothing answered. Retried against a clock rather than a budget, and never given up on. */
  default boolean unreachable() {
    return this instanceof Unreachable<T>;
  }

  /**
   * The refusal a run-once workload gives a recreate: the spec changed and the policy cannot answer
   * it. A caller's own choice — take what is there, or delete and ask again under a new ref.
   */
  default boolean specConflict() {
    return this instanceof Refused<T> refused
        && ContainersWire.SPEC_CONFLICT.equals(refused.code());
  }

  /**
   * The refusal that says nothing published this image. The one {@code ensure} failure a caller can
   * act on: no retry helps until something pushes the image.
   */
  default boolean imageMissing() {
    return this instanceof Refused<T> refused
        && ContainersWire.IMAGE_MISSING.equals(refused.code());
  }

  /** The refusal that says a value would not go into an argv. A bug in the caller, not a state. */
  default boolean invalid() {
    return this instanceof Refused<T> refused && ContainersWire.INVALID.equals(refused.code());
  }

  /** One line naming which of the four this is, for a caller's own log. Never null. */
  default String detail() {
    return switch (this) {
      case Created<T> ignored -> "created";
      case Ready<T> ignored -> "ready";
      case Refused<T> refused -> "refused " + refused.status() + " " + refused.code() + ": "
          + refused.message();
      case Unreachable<T> unreachable -> "unreachable: " + unreachable.cause();
    };
  }
}
