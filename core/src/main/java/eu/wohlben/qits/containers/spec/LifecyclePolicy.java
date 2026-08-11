package eu.wohlben.qits.containers.spec;

import java.time.Duration;

/**
 * How long a workload is meant to live, and what may happen to it when it stops.
 *
 * <p>Three answers, because the platform's five container spawners have three shapes between them,
 * and hard-coding any one of them is what made each of them write its own orchestrator.
 *
 * <p><b>The restart flag is the difference that matters, and it is not a preference.</b>
 *
 * <ul>
 *   <li>{@link Type#EPHEMERAL} renders <b>no {@code --restart} at all</b>. Its containers carry a
 *       daemon that dials this platform once and exits when its work is done, so a restart policy
 *       would bring back a process whose peer is gone, to dial an address that no longer expects it.
 *       Exiting is the success path, not a fault to recover from. For the same reason it
 *       <b>refuses recreate</b> ({@link #recreatable()}): the work it was started for happened once
 *       and a second container would do it again, which is not what anybody asked for.
 *   <li>{@link Type#IDLE_STOP} and {@link Type#EXPLICIT} render {@code --restart unless-stopped}.
 *       Their containers outlive this service and they outlive a dockerd restart — that is the
 *       point of them — and {@code unless-stopped} rather than {@code always} because a container
 *       this service stopped on purpose must not race its own restart back up.
 * </ul>
 *
 * <p>{@code idleAfter} and {@code maxAge} are nullable and mean "no sweep of that kind". They are
 * read by the policy sweeps rather than by the argv: nothing about them reaches docker.
 */
public record LifecyclePolicy(Type type, Duration idleAfter, Duration maxAge) {

  public enum Type {
    /** Runs once and exits. No restart policy, no recreate, and {@code maxAge} collects the row. */
    EPHEMERAL,
    /** Long-lived but stoppable: idle past {@code idleAfter} and it is stopped, never removed. */
    IDLE_STOP,
    /** Lives until somebody says otherwise. Only a delete ends it. */
    EXPLICIT
  }

  public LifecyclePolicy {
    if (type == null) {
      throw new IllegalArgumentException("Invalid lifecycle policy: no type");
    }
  }

  public static LifecyclePolicy ephemeral(Duration maxAge) {
    return new LifecyclePolicy(Type.EPHEMERAL, null, maxAge);
  }

  public static LifecyclePolicy idleStop(Duration idleAfter) {
    return new LifecyclePolicy(Type.IDLE_STOP, idleAfter, null);
  }

  public static LifecyclePolicy explicitLifetime() {
    return new LifecyclePolicy(Type.EXPLICIT, null, null);
  }

  /** Whether the {@code docker run} carries {@code --restart unless-stopped}. See the class doc. */
  public boolean restartsUnlessStopped() {
    return type != Type.EPHEMERAL;
  }

  /** Whether a container that stopped may be started again in a fresh one. */
  public boolean recreatable() {
    return type != Type.EPHEMERAL;
  }
}
