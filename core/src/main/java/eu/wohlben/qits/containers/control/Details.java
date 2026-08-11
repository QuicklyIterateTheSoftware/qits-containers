package eu.wohlben.qits.containers.control;

/**
 * The {@code detail} column's one writer.
 *
 * <p><b>Detail is appended, never overwritten.</b> The text on a row is the diagnosis of what went
 * wrong — the reason a container never started, what docker said when a remove failed — and the
 * moment a recovery erases it, the one artefact that made a bug findable is gone. qits-deployments
 * learned that on row eaa34fbc, where the original failure text was the whole reason anybody found
 * the cause; this is the same rule with the same argument.
 *
 * <p>Newest first, so a row read in a listing shows what happened last without scrolling.
 */
final class Details {

  /** How much of a detail column is kept. A row is a diagnosis, not a log file. */
  private static final int MAX_CHARS = 8_000;

  /** How much of one docker message is echoed into a stamp. */
  private static final int BRIEF_CHARS = 400;

  private Details() {}

  /** {@code stamp} on top of whatever the row already said, bounded. */
  static String append(String existing, String stamp) {
    if (stamp == null || stamp.isBlank()) {
      return existing;
    }
    String joined = existing == null || existing.isBlank() ? stamp : stamp + "\n" + existing;
    return joined.length() <= MAX_CHARS ? joined : joined.substring(0, MAX_CHARS);
  }

  /**
   * One docker message, rendered for a stamp: control characters replaced and the length capped.
   * Docker's output is not this service's, so it is echoed the way a refused identifier is — a value
   * that could carry a newline could forge a second line of a detail nobody wrote.
   */
  static String brief(String message) {
    if (message == null || message.isBlank()) {
      return "no detail";
    }
    StringBuilder out = new StringBuilder(Math.min(message.length(), BRIEF_CHARS));
    message
        .strip()
        .codePoints()
        .limit(BRIEF_CHARS)
        .forEach(cp -> out.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp));
    return out.toString();
  }
}
