package eu.wohlben.qits.containers.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bounded-output and timeout behaviour of this service's process shell-out — {@code
 * CiProcessTest}'s cases, ported with the twin.
 */
public class ContainerProcessTest {

  @Test
  public void capturesOutputAndExitCode() {
    ContainerProcess.Result result =
        ContainerProcess.run(
            null, List.of("bash", "-c", "echo hello; exit 3"), Duration.ofSeconds(30), 1024);
    assertEquals(3, result.exitCode());
    assertTrue(result.output().contains("hello"));
    assertFalse(result.truncated());
    assertFalse(result.timedOut());
  }

  @Test
  public void stderrIsMergedIntoTheOneCapture() {
    // The docker CLI says why it refused on stderr and what it did on stdout, and a caller that had
    // to read them apart would be a caller with two bounds to get right.
    ContainerProcess.Result result =
        ContainerProcess.run(
            null,
            List.of("bash", "-c", "echo out; echo err >&2; exit 1"),
            Duration.ofSeconds(30),
            1024);
    assertEquals(1, result.exitCode());
    assertTrue(result.output().contains("out"), result.output());
    assertTrue(result.output().contains("err"), result.output());
  }

  @Test
  public void anEnvironmentIsADDEDToThisProcessOwnAndNeverAReplacementForIt() {
    // The buildx state directory arrives this way, and PATH has to survive it: a child handed only
    // the variables this service names would be a docker CLI that cannot find its own credentials
    // — DOCKER_CONFIG is set by the deployment and is not ours to drop.
    ContainerProcess.Result result =
        ContainerProcess.run(
            null,
            List.of("bash", "-c", "echo \"cfg=$BUILDX_CONFIG path=${PATH:+set}\""),
            java.util.Map.of("BUILDX_CONFIG", "/tmp/qits-buildx"),
            Duration.ofSeconds(30),
            1024);

    assertEquals(0, result.exitCode());
    assertTrue(result.output().contains("cfg=/tmp/qits-buildx"), result.output());
    assertTrue(result.output().contains("path=set"), result.output());
  }

  @Test
  public void outputIsBoundedWhileReadingAndKeepsTheTail() {
    // A container's output is attacker-influenced and unbounded; the buffer must stay O(maxChars)
    // rather than materializing the whole stream on this service's heap.
    int max = 2048;
    ContainerProcess.Result result =
        ContainerProcess.run(
            null,
            List.of(
                "bash",
                "-c",
                "for i in $(seq 1 20000); do echo padding-line-$i; done; echo THE-END"),
            Duration.ofSeconds(60),
            max);
    assertEquals(0, result.exitCode());
    assertTrue(result.truncated(), "large output must report truncation");
    assertTrue(
        result.output().length() <= max, "buffer kept " + result.output().length() + " chars");
    assertTrue(result.output().contains("THE-END"), "the tail is what matters for a failure");
  }

  @Test
  public void timeoutKillsTheProcessAndReportsIt() {
    ContainerProcess.Result result =
        ContainerProcess.run(null, List.of("bash", "-c", "sleep 30"), Duration.ofMillis(300), 1024);
    assertTrue(result.timedOut());
    assertEquals(-1, result.exitCode());
  }

  @Test
  public void aTimedOutCallStillReturnsWhatItHadRead() {
    // The whole reason the tail is captured on its own thread: a docker call that hung after saying
    // something useful must not take that sentence with it.
    ContainerProcess.Result result =
        ContainerProcess.run(
            null,
            List.of("bash", "-c", "echo SAID-THIS-FIRST; sleep 30"),
            Duration.ofMillis(500),
            1024);
    assertTrue(result.timedOut());
    assertTrue(result.output().contains("SAID-THIS-FIRST"), result.output());
  }
}
