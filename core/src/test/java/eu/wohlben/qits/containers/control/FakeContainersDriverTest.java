package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.spec.ContainerLabels;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The fake is a test fixture, so what is asserted here is only the two properties every suite after
 * this one will lean on: the call log is append-only and in arrival order, and an unscripted
 * container is <b>absent</b> rather than healthy.
 */
public class FakeContainersDriverTest {

  private static final Duration T = Duration.ofSeconds(30);

  private static ContainerSpec spec() {
    return ContainerSpec.builder("maven:3.9").network("qits-net").build();
  }

  @Test
  public void everyCallLandsInOrder() {
    FakeContainersDriver driver = new FakeContainersDriver();
    driver.run(
        spec(),
        "c-1",
        ContainerLabels.forContainer("qits-ci", "step", "run-1", "row-1", "instance-1"),
        LifecyclePolicy.ephemeral(null),
        T);
    driver.logsTail("c-1", 200, T, 4096);
    driver.remove("c-1", T);

    // Logs BEFORE the removal — the order the whole logs-as-reap contract is about, and a claim
    // return values cannot make.
    assertEquals(List.of("run:c-1", "logs:c-1", "remove:c-1"), driver.calls());
    assertEquals(List.of(spec()), driver.ranSpecs());
  }

  @Test
  public void anUnscriptedContainerIsAbsentRatherThanHealthy() {
    // This fake performs nothing, so the only containers docker could have are the ones a test said
    // exist. Absent and unhealthy are different statements and must never merge.
    FakeContainersDriver driver = new FakeContainersDriver();
    assertTrue(driver.inspect("never-started", T).isEmpty());

    driver.scriptContainer("c-1", "running", "healthy", Instant.EPOCH);
    ContainersDriver.Observed observed = driver.inspect("c-1", T).orElseThrow();
    assertEquals("running", observed.status());
    assertEquals("healthy", observed.health());

    driver.remove("c-1", T);
    assertTrue(driver.inspect("c-1", T).isEmpty(), "a removed container is gone, not unhealthy");
  }

  @Test
  public void aBoundedLogTailKeepsTheTailAndSaysSo() {
    FakeContainersDriver driver = new FakeContainersDriver();
    driver.scriptLogs("c-1", "0123456789THE-END");
    ContainersDriver.LogTail tail = driver.logsTail("c-1", 200, T, 7);
    assertEquals("THE-END", tail.text());
    assertTrue(tail.truncated());
  }

  @Test
  public void aListingAnswersOnlyWhatWasScriptedForThoseFilters() {
    FakeContainersDriver driver = new FakeContainersDriver();
    Map<String, String> mine = Map.of(ContainerLabels.OWNER, "qits-ci");
    driver.scriptLabelListing(mine, List.of("id-1", "id-2"));
    assertEquals(List.of("id-1", "id-2"), driver.listByLabels(mine, T));
    assertEquals(List.of(), driver.listByLabels(Map.of(ContainerLabels.OWNER, "somebody-else"), T));
  }

  @Test
  public void resetLeavesNothingBehind() {
    FakeContainersDriver driver = new FakeContainersDriver();
    driver.scriptContainer("c-1", "running", "none", Instant.EPOCH);
    driver.stop("c-1", T);
    driver.reset();
    assertEquals(List.of(), driver.calls());
    assertFalse(driver.inspect("c-1", T).isPresent());
  }
}
