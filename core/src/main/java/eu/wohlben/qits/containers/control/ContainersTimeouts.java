package eu.wohlben.qits.containers.control;

import java.time.Duration;

/**
 * Every deadline and every output bound this service spends on a docker call, in one place.
 *
 * <p><b>Both are security properties rather than tuning</b>, which is AGENTS.md's second invariant
 * and is why {@code ContainersDriver} takes them as parameters on every method. Spelling them here
 * rather than at each call site is what keeps a second copy from drifting: the registry, the boot
 * sweep, the observer and the policy sweeps all call the same driver, and a deadline that differed
 * between them would be a deadline nobody could reason about.
 *
 * <p>They are constants and not config keys on purpose. A deployment that could shorten the run
 * timeout to zero, or lift the log bound to unbounded, would be a deployment that could switch off a
 * guard; if one of these ever has to move, it moves as a change to this file with a reason beside
 * it.
 */
public final class ContainersTimeouts {

  /**
   * A {@code docker run}. Generous, because it covers docker's implicit pull of an image the host
   * does not have — the pull policies other than {@code ALWAYS} rely on exactly that.
   */
  public static final Duration RUN = Duration.ofMinutes(3);

  /**
   * A {@code docker start} of a container that is already there. Far below {@link #RUN}, because no
   * image can be fetched on this path — the container exists, so its image is already resolved into
   * it — but not an inspect's ten seconds either: the daemon answers once it has created the task,
   * and a host under load takes longer over that than over reading a state.
   */
  public static final Duration START = Duration.ofSeconds(60);

  /** An inspect. Short: it reads local state, and a daemon that cannot answer it is not answering. */
  public static final Duration INSPECT = Duration.ofSeconds(10);

  /** A stop. Docker's own default grace is 10s, so this has to outlast it and then some. */
  public static final Duration STOP = Duration.ofSeconds(45);

  /** A remove. Forced, so it does not wait on a graceful stop. */
  public static final Duration REMOVE = Duration.ofSeconds(30);

  /** A log tail. Bounded in time as well as in bytes — a daemon can stall mid-stream. */
  public static final Duration LOGS = Duration.ofSeconds(20);

  /** An explicit pull. The one call that legitimately takes minutes. */
  public static final Duration PULL = Duration.ofMinutes(10);

  /** Volume create, remove and list. Local metadata operations. */
  public static final Duration VOLUME = Duration.ofSeconds(20);

  /**
   * How much of a container's output a caller may receive. A {@code docker logs} with no bound is a
   * heap the container chose the size of.
   */
  public static final int LOGS_MAX_CHARS = 64_000;

  /** How many lines a caller gets when it asks for no particular number. */
  public static final int LOGS_DEFAULT_LINES = 200;

  /** How much of a pull's progress output is captured. Enough to name a failure, never a stream. */
  public static final int PULL_MAX_CHARS = 8_000;

  /**
   * How much of a {@code docker run}'s output is kept. A run prints one container id when it works
   * and a refusal when it does not, and the refusal is the whole diagnosis of a workload that never
   * started — so it is the log bound rather than the short one.
   */
  public static final int RUN_MAX_CHARS = 64_000;

  /**
   * Every other call: an inspect, a stop, a remove, a listing, a volume operation. They answer in
   * one short line and their failures are one short sentence, so anything past this is a daemon
   * saying something nobody asked for.
   */
  public static final int SHORT_MAX_CHARS = 8_000;

  private ContainersTimeouts() {}
}
