package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two caches, and the rule that keeps one from taking the other down.
 *
 * <p>A build host has one wedged builder container more often than it has none, so the claim worth
 * asserting is that a builder's failure lands in its own row and the run carries on — including
 * when the host's own prune is the one that failed.
 */
@QuarkusTest
public class CtBuildCacheGcTest extends CtTestSupport {

  private static final String BUILDER = "buildx_buildkit_qits-bootstrap-builder-v40";

  private static final long KEEP = 20_000_000_000L;

  /** What a bootstrap builder may keep: far less, because it is only useful during a bootstrap. */
  private static final long BUILDER_KEEP = 1_000_000_000L;

  @Inject BuildCacheGc gc;

  @Test
  public void prunesTheHostCacheAndEveryBuilderContainer() {
    driver.scriptBuilders(List.of(BUILDER));
    driver.scriptHostCache(new ContainersDriver.CacheResult(true, 103_500_000_000L, "Total: 103.5GB"));
    driver.scriptBuilderCache(
        BUILDER, new ContainersDriver.CacheResult(true, 27_110_000_000L, "Total: 27.11GB"));

    BuildCacheGc.Result result = gc.sweep(false, KEEP, KEEP);

    assertEquals(103_500_000_000L, result.host().reclaimedBytes());
    assertNull(result.host().error());
    assertEquals(1, result.builders().size());
    assertEquals(BUILDER, result.builders().getFirst().container());
    assertEquals(27_110_000_000L, result.builders().getFirst().reclaimedBytes());
    assertEquals(
        List.of(
            "pruneBuildCache:" + KEEP,
            "listBuildxBuilders",
            "pruneBuilderCache:" + BUILDER + ":" + KEEP),
        driver.calls());
  }

  @Test
  public void aDryRunReadsBothCachesAndPrunesNeither() {
    driver.scriptBuilders(List.of(BUILDER));
    driver.scriptHostCache(
        new ContainersDriver.CacheResult(true, 302_500_000_000L, "Reclaimable: 302.5GB; Total: 302.5GB"));

    BuildCacheGc.Result result = gc.sweep(true, KEEP, KEEP);

    assertTrue(result.dryRun());
    assertEquals(
        0,
        result.host().reclaimedBytes(),
        "a du cannot say what a keep-storage prune would free, so nothing is claimed");
    assertNotNull(result.host().detail(), "and what it did see is the answer");
    assertEquals(
        List.of("describeBuildCache", "listBuildxBuilders", "describeBuilderCache:" + BUILDER),
        driver.calls());
    assertFalse(driver.calls().stream().anyMatch(call -> call.startsWith("prune")));
  }

  @Test
  public void aBuilderThatFailedCostsItselfAndNotTheRun() {
    driver.scriptBuilders(List.of(BUILDER, "buildx_buildkit_other"));
    driver.scriptBuilderCache(
        BUILDER, new ContainersDriver.CacheResult(false, 0, "Error response from daemon: is not running"));
    driver.scriptBuilderCache(
        "buildx_buildkit_other", new ContainersDriver.CacheResult(true, 5_000_000_000L, "Total: 5GB"));

    BuildCacheGc.Result result = gc.sweep(false, KEEP, KEEP);

    assertEquals(2, result.builders().size());
    assertNotNull(result.builders().getFirst().error());
    assertEquals(0, result.builders().getFirst().reclaimedBytes());
    assertNull(result.builders().get(1).error());
    assertEquals(5_000_000_000L, result.builders().get(1).reclaimedBytes());
  }

  @Test
  public void aHostPruneThatFailedStillLetsTheBuildersBePruned() {
    driver.scriptBuilders(List.of(BUILDER));
    driver.scriptHostCache(new ContainersDriver.CacheResult(false, 0, "cannot connect to the daemon"));

    BuildCacheGc.Result result = gc.sweep(false, KEEP, KEEP);

    assertNotNull(result.host().error());
    assertEquals(0, result.host().reclaimedBytes());
    assertEquals(1, result.builders().size(), "the builders are a separate question");
  }

  @Test
  public void aBuilderKeepsWhatTheCallerSaidRatherThanWhatTheHostKeeps() {
    // The two caches are two questions. The host's is the platform's build cache; a builder
    // container is a bootstrap builder, and the orchestrator keeps it small.
    driver.scriptBuilders(List.of(BUILDER));

    gc.sweep(false, KEEP, BUILDER_KEEP);

    assertEquals(
        List.of(
            "pruneBuildCache:" + KEEP,
            "listBuildxBuilders",
            "pruneBuilderCache:" + BUILDER + ":" + BUILDER_KEEP),
        driver.calls());
  }

  @Test
  public void aHostWithNoBuilderContainersIsJustTheHostCache() {
    BuildCacheGc.Result result = gc.sweep(false, KEEP, KEEP);

    assertTrue(result.builders().isEmpty());
    assertEquals(List.of("pruneBuildCache:" + KEEP, "listBuildxBuilders"), driver.calls());
  }
}
