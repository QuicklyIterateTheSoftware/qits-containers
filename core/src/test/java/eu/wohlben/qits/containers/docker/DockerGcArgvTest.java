package eu.wohlben.qits.containers.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The garbage collection's command lines, element for element.
 *
 * <p>Worth asserting literally for the reason the run argv is: these are the calls that REMOVE
 * things nothing else in this service is allowed to remove, so a lost {@code --no-trunc} is a
 * listing of short ids nothing matches, a lost {@code --force} is a prune that waits forever for a
 * prompt, and an {@code -f} that appeared on an {@code image rm} would be the one flag that takes
 * an image a container is holding.
 *
 * <p>Every format string here was measured against docker 29.7.2 — the notes are in
 * {@link DockerArgv}, beside each one.
 */
public class DockerGcArgvTest {

  @Test
  public void readsTheFourStoresInOneCall() {
    assertEquals(
        List.of(
            "docker",
            "system",
            "df",
            "--format",
            "{{.Type}}|{{.TotalCount}}|{{.Active}}|{{.Size}}|{{.Reclaimable}}"),
        DockerArgv.systemDf("docker"));
  }

  @Test
  public void listsImagesWithWholeIdsAndNoIntermediateLayers() {
    List<String> argv = DockerArgv.imageLs("docker");
    assertEquals(
        List.of(
            "docker",
            "image",
            "ls",
            "--no-trunc",
            "--format",
            "{{.ID}}|{{.Repository}}|{{.Tag}}|{{.CreatedAt}}|{{.Size}}"),
        argv);
    assertFalse(argv.contains("--all"), "intermediate layers are not images anybody may remove");
  }

  @Test
  public void asksWhatContainersWereCreatedFromRatherThanForAnImageIdField() {
    // Measured: docker 29.7.2 answers `can't evaluate field ImageID in type
    // *formatter.ContainerContext`. .Image is the field the ps formatter really has.
    assertEquals(
        List.of("docker", "ps", "-a", "--no-trunc", "--format", "{{.Image}}"),
        DockerArgv.psImageReferences("docker"));
  }

  @Test
  public void removesAnImageByIdAndNeverForced() {
    List<String> argv =
        DockerArgv.imageRm(
            "docker", "sha256:14beaf669b73a3a348fe5c82e7129f42394a933dd8b81e358e430964148397bb");
    assertEquals(
        List.of(
            "docker",
            "image",
            "rm",
            "sha256:14beaf669b73a3a348fe5c82e7129f42394a933dd8b81e358e430964148397bb"),
        argv);
    assertFalse(argv.contains("-f"), "a forced remove can take an image a container is holding");
    assertFalse(argv.contains("--force"), "same rule, spelled the other way");
    assertFalse(argv.contains("prune"), "the deciding is this service's, image by image");
  }

  @Test
  public void refusesAnImageReferenceWhereAnIdBelongs() {
    // The belt that keeps a tag an owner chose from ever reaching an `image rm`.
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.imageRm("docker", "alpine:3"));
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.imageRm("docker", "sha256:zz"));
  }

  @Test
  public void removesATaggedImageByEveryReferenceThatNamesIt() {
    // Measured live: `image rm <id>` is refused with `must be forced` for an image more than one
    // reference names, and two tags of ONE repository are two references. Untagging every one of
    // them in a single call removes the image on the last, with no -f anywhere.
    List<String> argv =
        DockerArgv.imageRmRefs(
            "docker",
            List.of(
                "registry:8080/qits/projects-daemon:2026.820.154053",
                "registry:8080/qits/projects-daemon:863933e"));

    assertEquals(
        List.of(
            "docker",
            "image",
            "rm",
            "registry:8080/qits/projects-daemon:2026.820.154053",
            "registry:8080/qits/projects-daemon:863933e"),
        argv);
    assertFalse(argv.contains("-f"));
    assertFalse(argv.contains("--force"));
  }

  @Test
  public void refusesAReferenceListThatIsEmptyOrCarriesSomethingAnArgvWillNotTake() {
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.imageRmRefs("docker", List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.imageRmRefs("docker", List.of("--force")));
    assertThrows(
        IllegalArgumentException.class,
        () -> DockerArgv.imageRmRefs("docker", List.of("alpine:3", "a tag with spaces")));
  }

  @Test
  public void theHostCacheCallsCarryTheirOwnBuildxState() {
    // The plugin writes under $DOCKER_CONFIG, which this deployment mounts read-only — measured as
    // `mkdir /work/config/buildx: permission denied` on the first real collection run.
    assertEquals(
        java.util.Map.of("BUILDX_CONFIG", "/tmp/qits-buildx"), DockerArgv.buildxEnvironment());
  }

  @Test
  public void listsOnlyDanglingVolumes() {
    assertEquals(
        List.of("docker", "volume", "ls", "-q", "--filter", "dangling=true"),
        DockerArgv.volumeLsDangling("docker"));
  }

  @Test
  public void readsAVolumesTimeAndLabelsInOneCall() {
    assertEquals(
        List.of(
            "docker",
            "volume",
            "inspect",
            "--format",
            "{{.CreatedAt}}{{\"\\n\"}}{{range $k, $v := .Labels}}{{$k}}={{$v}}{{\"\\n\"}}{{end}}",
            "buildx_buildkit_qits-bootstrap-builder-v40_state"),
        DockerArgv.volumeInspectDetail("docker", "buildx_buildkit_qits-bootstrap-builder-v40_state"));
  }

  @Test
  public void asksWhichContainersHoldAVolume() {
    assertEquals(
        List.of(
            "docker", "ps", "-a", "--filter", "volume=agent-home", "--format", "{{.Names}}"),
        DockerArgv.psByVolume("docker", "agent-home"));
  }

  @Test
  public void listsTheBuilderContainersByTheirOwnPrefix() {
    assertEquals(
        List.of(
            "docker",
            "ps",
            "-a",
            "--filter",
            "name=buildx_buildkit_",
            "--format",
            "{{.Names}}"),
        DockerArgv.psBuildxBuilders("docker"));
  }

  @Test
  public void prunesTheHostCacheInBytesAndWithoutAPrompt() {
    // --keep-storage is docker 29's deprecated alias for --reserved-space and still takes BYTES.
    assertEquals(
        List.of("docker", "builder", "prune", "--force", "--keep-storage", "20000000000"),
        DockerArgv.builderPrune("docker", 20_000_000_000L));
  }

  @Test
  public void readsTheHostCacheWithoutPruningIt() {
    assertEquals(List.of("docker", "buildx", "du"), DockerArgv.buildxDu("docker"));
  }

  @Test
  public void prunesABuilderInMegabytesBecauseThatIsWhatBuildctlTakes() {
    // Measured inside a live builder: `--keep-storage float  Keep data below this limit (in MB)`.
    // Handing it the byte count would ask for a million times what was meant, which prunes nothing.
    assertEquals(
        List.of(
            "docker",
            "exec",
            "buildx_buildkit_qits-bootstrap-builder-v40",
            "buildctl",
            "prune",
            "--keep-storage",
            "20000"),
        DockerArgv.buildctlPrune("docker", "buildx_buildkit_qits-bootstrap-builder-v40", 20_000_000_000L));
  }

  @Test
  public void roundsTheMegabytesUpSoARoundingErrorKeepsCache() {
    assertEquals(1, DockerArgv.keepStorageMegabytes(1));
    assertEquals(1, DockerArgv.keepStorageMegabytes(1_000_000));
    assertEquals(2, DockerArgv.keepStorageMegabytes(1_000_001));
    assertEquals(0, DockerArgv.keepStorageMegabytes(0));
  }

  @Test
  public void readsABuilderCacheWithoutPruningIt() {
    assertEquals(
        List.of("docker", "exec", "buildx_buildkit_qits-bootstrap-builder-v40", "buildctl", "du"),
        DockerArgv.buildctlDu("docker", "buildx_buildkit_qits-bootstrap-builder-v40"));
  }

  @Test
  public void execRunsInsideABuilderAndNowhereElse() {
    // The one exec in this service. The belt is the prefix: a container name of any other shape is
    // refused before an argv exists, so exec cannot become a general capability by a later caller.
    assertThrows(
        IllegalArgumentException.class,
        () -> DockerArgv.buildctlDu("docker", "dev-qits-ci.1.iu93lb6d12ycijfw7wo50jnw9"));
    assertThrows(
        IllegalArgumentException.class, () -> DockerArgv.buildctlPrune("docker", "postgres", 1));
  }

  @Test
  public void refusesAKeepStorageNoPruneCouldMean() {
    assertThrows(IllegalArgumentException.class, () -> DockerArgv.builderPrune("docker", -1));
    assertThrows(
        IllegalArgumentException.class,
        () -> DockerArgv.builderPrune("docker", Long.MAX_VALUE));
  }
}
