package eu.wohlben.qits.containers.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.entity.CtContainer;
import eu.wohlben.qits.containers.entity.ObservedState;
import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The five claims {@code ensure} exists to make: the row comes first, a refused run is adopted
 * rather than repeated, a changed spec is replaced only where the policy allows it, and the stored
 * spec carries no credential while the hash still sees one change.
 */
@QuarkusTest
public class CtEnsureTest extends CtTestSupport {

  private static final String OWNER = "qits-ci";
  private static final String WORKLOAD = "step";

  @Test
  public void theRowExistsAndSaysPendingBeforeDockerIsAskedToRunAnything() {
    // The whole repository rests on this ordering, so it is asserted at the instant it matters
    // rather than inferred from the order of two log lines afterwards.
    AtomicReference<String> stateWhenDockerWasCalled = new AtomicReference<>("no row at all");
    driver.duringRun(
        name ->
            stateWhenDockerWasCalled.set(
                QuarkusTransaction.requiringNew()
                    .call(
                        () -> {
                          CtContainer row = containers.findByContainerName(name);
                          return row == null ? "no row at all" : row.observedState.name();
                        })));

    ContainerRegistry.Ensured ensured =
        registry.ensure(
            OWNER, WORKLOAD, "run-first", spec("alpine:3"), LifecyclePolicy.ephemeral(null), false);

    assertEquals(
        "PENDING",
        stateWhenDockerWasCalled.get(),
        "the row has to be committed, and PENDING, before docker is asked for anything");
    assertTrue(ensured.created());
    assertEquals(ObservedState.RUNNING, ensured.observed());
    assertEquals(
        List.of("run:" + ensured.containerName(), "inspect:" + ensured.containerName()),
        driver.calls(),
        "one run and one confirming inspect, and nothing else");
  }

  @Test
  public void aRefusedRunAdoptsTheContainerTheRowAlreadyNames() {
    // The crash-retry case: a previous attempt started the container and died before recording it,
    // so docker refuses the name. The row named it first, so it is ours.
    String name = nameOf(OWNER, WORKLOAD, "run-dup");
    driver.scriptRun(
        new ContainersDriver.Started(
            false, "", "Conflict. The container name \"" + name + "\" is already in use"));
    driver.scriptContainer(name, "running", "none", java.time.Instant.EPOCH);

    ContainerRegistry.Ensured ensured =
        registry.ensure(
            OWNER, WORKLOAD, "run-dup", spec("alpine:3"), LifecyclePolicy.ephemeral(null), false);

    assertEquals(ObservedState.RUNNING, ensured.observed());
    assertTrue(row(ensured.rowId()).detail.contains("adopted after a refused run"));
    assertFalse(
        driver.calls().contains("remove:" + name),
        "adoption never removes: the container is the one this row wanted");
    assertFalse(driver.calls().contains("stop:" + name));
  }

  @Test
  public void aChangedSpecIsReplacedUnderTheSameNameWhenARecreateWasAskedFor() {
    ContainerRegistry.Ensured first =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-1",
            spec("alpine:3"),
            LifecyclePolicy.explicitLifetime(),
            false);
    String name = first.containerName();

    ContainerRegistry.Ensured second =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-1",
            spec("alpine:3.20"),
            LifecyclePolicy.explicitLifetime(),
            true);

    assertEquals(name, second.containerName(), "a replacement keeps the place's name");
    assertFalse(second.created());
    assertEquals(ObservedState.RUNNING, second.observed());
    assertEquals(
        List.of(
            "run:" + name,
            "inspect:" + name,
            "stop:" + name,
            "remove:" + name,
            "run:" + name,
            "inspect:" + name),
        driver.calls(),
        "stop and remove precede the replacement run, and the row was never removed");
    assertEquals("alpine:3.20", row(second.rowId()).image);
  }

  @Test
  public void aChangedSpecIsLeftAloneWhenNoRecreateWasAsked() {
    registry.ensure(
        OWNER, "workspace", "ws-keep", spec("alpine:3"), LifecyclePolicy.explicitLifetime(), false);
    driver.reset();

    ContainerRegistry.Ensured second =
        registry.ensure(
            OWNER,
            "workspace",
            "ws-keep",
            spec("alpine:3.20"),
            LifecyclePolicy.explicitLifetime(),
            false);

    assertEquals(List.of(), driver.calls(), "nothing docker-side happens without a recreate");
    assertEquals("alpine:3", row(second.rowId()).image, "the row still describes what is running");
  }

  @Test
  public void recreatingARunOnceWorkloadIsRefusedWithATypedConflict() {
    registry.ensure(
        OWNER, WORKLOAD, "run-eph", spec("alpine:3"), LifecyclePolicy.ephemeral(null), false);
    driver.reset();

    SpecConflictException refused =
        assertThrows(
            SpecConflictException.class,
            () ->
                registry.ensure(
                    OWNER,
                    WORKLOAD,
                    "run-eph",
                    spec("alpine:3.20"),
                    LifecyclePolicy.ephemeral(null),
                    true));

    assertTrue(refused.getMessage().contains("EPHEMERAL"));
    assertEquals(List.of(), driver.calls(), "the refusal happens before anything docker-side");
  }

  @Test
  public void theStoredSpecCarriesNoEnvironmentAndTheHashStillSeesOneChange() {
    ContainerSpec withOneSecret =
        ContainerSpec.builder("alpine:3").network("qits-net").env("QITS_TOKEN", "s3cr3t-one").build();
    ContainerSpec withAnother =
        ContainerSpec.builder("alpine:3").network("qits-net").env("QITS_TOKEN", "s3cr3t-two").build();

    ContainerRegistry.Ensured one =
        registry.ensure(
            OWNER, WORKLOAD, "run-env-a", withOneSecret, LifecyclePolicy.ephemeral(null), false);
    ContainerRegistry.Ensured two =
        registry.ensure(
            OWNER, WORKLOAD, "run-env-b", withAnother, LifecyclePolicy.ephemeral(null), false);

    CtContainer first = row(one.rowId());
    CtContainer second = row(two.rowId());

    assertFalse(first.specJson.contains("s3cr3t-one"), "no credential is ever written to this table");
    assertFalse(first.specJson.contains("QITS_TOKEN"), "not even the key it rode under");
    assertEquals(
        first.specJson,
        second.specJson,
        "two workloads differing only by a secret store the same thing — which is why the hash has"
            + " to be what tells them apart");
    assertNotEquals(
        first.specHash,
        second.specHash,
        "change detection has to see an env change, or a rotated credential never redeploys");
  }

  @Test
  public void flippingInitIsASpecChangeTheHashSees() {
    // A container's PID 1 is not a detail the hash may be blind to: a workspace re-asked for with
    // tini and matched against a row that says it is already there would keep running the process
    // it was started without, and the owner would have no way to tell.
    ContainerSpec without = spec("alpine:3");
    ContainerSpec with =
        ContainerSpec.builder("alpine:3").network("qits-net").init(true).build();

    ContainerRegistry.Ensured plain =
        registry.ensure(OWNER, WORKLOAD, "run-init-a", without, LifecyclePolicy.explicitLifetime(), false);
    ContainerRegistry.Ensured inited =
        registry.ensure(OWNER, WORKLOAD, "run-init-b", with, LifecyclePolicy.explicitLifetime(), false);

    CtContainer plainRow = row(plain.rowId());
    CtContainer initedRow = row(inited.rowId());

    assertNotEquals(plainRow.specHash, initedRow.specHash);
    // And it is on the stored spec too, so a restart compares against what was really asked for.
    assertTrue(initedRow.specJson.contains("\"init\":true"), initedRow.specJson);
    assertTrue(plainRow.specJson.contains("\"init\":false"), plainRow.specJson);
  }
}
