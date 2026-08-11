package eu.wohlben.qits.containers.docker;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * This service's own tiny process shell-out — the {@code CiProcess} / {@code PdProcess} shape,
 * deliberately its own third copy rather than a shared jar so this repository stays clone-alone.
 * Combined stdout+stderr, drained on a virtual thread; on timeout the process is {@link
 * Process#destroyForcibly() force-killed} and whatever output was captured so far is returned with
 * {@code timedOut=true}.
 *
 * <p><b>What it is used for is the docker CLI and nothing else.</b> The vocabulary is container
 * lifecycle — {@code run}, {@code inspect}, {@code logs}, {@code stop}, {@code rm}, {@code ps},
 * {@code pull}, {@code volume}, {@code network inspect} — and {@code exec} is not in it. Nothing an
 * owner sends becomes a host command line: an argv is assembled element by element by {@link
 * DockerArgv} and handed to {@link ProcessBuilder}, which never re-splits.
 *
 * <p><b>There is one entry point and it takes both a timeout and a bound.</b> That is the whole
 * design, and it is the repository's second invariant (AGENTS.md): a docker call with no deadline is
 * a worker held forever by a daemon that stopped answering, and a capture with no bound is a heap
 * whose size the caller chose. Container output is attacker-influenced — an owner picks the image
 * and a workload prints what it likes — so an overload without either would be an unbounded read
 * that compiles. Making the bound unavoidable in the API is the security property; a convenience
 * overload is the regression.
 *
 * <p>Output is <b>bounded while reading</b>: the buffer keeps only the trailing {@code maxChars} (a
 * failure's tail is where the diagnosis is) and reports {@code truncated}.
 */
public final class ContainerProcess {

  /** Rolling buffer slack: trim back to {@code maxChars} once it grows past this multiple. */
  private static final int TRIM_FACTOR = 2;

  /**
   * Exit code, bounded combined output, whether the hard timeout expired ({@code exitCode} is -1
   * then), and whether output was dropped from the front.
   */
  public record Result(int exitCode, String output, boolean timedOut, boolean truncated) {}

  private ContainerProcess() {}

  /**
   * Run the argv, capturing the trailing {@code maxChars} of its merged output.
   *
   * @param cwd working directory, or null for this process's own
   * @param argv the command, already assembled element by element — never a shell string
   * @param timeout the hard deadline; past it the process is force-killed
   * @param maxChars how much of the output tail is kept
   */
  public static Result run(Path cwd, List<String> argv, Duration timeout, int maxChars) {
    try {
      ProcessBuilder pb = new ProcessBuilder(argv);
      if (cwd != null) {
        pb.directory(cwd.toFile());
      }
      pb.redirectErrorStream(true);
      Process process = pb.start();
      Tail tail = new Tail(maxChars);
      Thread reader =
          Thread.startVirtualThread(
              () -> {
                try (InputStream stream = process.getInputStream()) {
                  byte[] buffer = new byte[8192];
                  int n;
                  while ((n = stream.read(buffer)) >= 0) {
                    tail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                  }
                } catch (Exception ignored) {
                  // stream closes when the process dies — nothing to report beyond the exit code
                }
              });
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor();
      }
      reader.join(TimeUnit.SECONDS.toMillis(5));
      return new Result(
          finished ? process.exitValue() : -1, tail.text(), !finished, tail.truncated());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(-1, "interrupted", false, false);
    } catch (Exception e) {
      return new Result(-1, String.valueOf(e.getMessage()), false, false);
    }
  }

  /** A synchronized rolling tail — appends are trimmed so memory stays O(maxChars). */
  private static final class Tail {

    private final StringBuilder buffer = new StringBuilder();
    private final int maxChars;
    private boolean truncated;

    Tail(int maxChars) {
      this.maxChars = Math.max(1, maxChars);
    }

    synchronized void append(String chunk) {
      buffer.append(chunk);
      if (buffer.length() > (long) maxChars * TRIM_FACTOR) {
        buffer.delete(0, buffer.length() - maxChars);
        truncated = true;
      }
    }

    synchronized String text() {
      if (buffer.length() > maxChars) {
        buffer.delete(0, buffer.length() - maxChars);
        truncated = true;
      }
      return buffer.toString();
    }

    synchronized boolean truncated() {
      return truncated;
    }
  }
}
