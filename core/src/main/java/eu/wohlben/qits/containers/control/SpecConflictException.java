package eu.wohlben.qits.containers.control;

/**
 * An owner asked for a spec change that its lifecycle policy cannot answer.
 *
 * <p>Today there is exactly one such ask, and it is {@code EPHEMERAL}. A recreate replaces a running
 * container with a fresh one under the same name — which for a workload that runs once and exits
 * means doing the work a second time. That is not a policy preference, it is what
 * {@code LifecyclePolicy.recreatable()} says out loud: "the work it was started for happened once
 * and a second container would do it again, which is not what anybody asked for".
 *
 * <p><b>A typed exception rather than a silent no-op</b>, because the two possible wrong answers are
 * both worse. Running a second container is the thing the policy forbids; quietly leaving the old
 * spec in place would tell a caller its change landed when it did not. WP4's REST layer maps this to
 * a 409.
 */
public class SpecConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SpecConflictException(String message) {
    super(message);
  }
}
