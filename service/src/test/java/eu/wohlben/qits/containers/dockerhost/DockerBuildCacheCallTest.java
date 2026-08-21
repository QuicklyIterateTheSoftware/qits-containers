package eu.wohlben.qits.containers.dockerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.control.ContainersDriver.CacheResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import eu.wohlben.qits.containers.docker.DockerArgv;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two host build-cache calls, made for real against a stand-in binary.
 *
 * <p><b>Both claims here were defects on the platform's first real collection run</b>, and neither
 * could be made against the faked driver: they are about what this process hands a child and how it
 * reads what comes back, which is precisely the part a fake replaces.
 *
 * <ul>
 *   <li>The prune answered {@code mkdir /work/config/buildx: permission denied}, because the buildx
 *       plugin keeps state under {@code $DOCKER_CONFIG} and this service's config volume is
 *       read-only. {@code BUILDX_CONFIG} has to reach the child.
 *   <li>CLI 29 prints {@code Flag --keep-storage has been deprecated} on stderr and then works.
 *       Only the exit status may decide, or every prune reports itself broken.
 * </ul>
 *
 * <p>The stand-in is a shell script rather than docker: what is under test is this class, and a
 * real daemon would make the test a statement about the host instead.
 */
class DockerBuildCacheCallTest {

  /** A docker that talks on stderr, works anyway, and reports what it was told about buildx. */
  private static final String DEPRECATING_DOCKER =
      """
      #!/bin/bash
      echo 'Flag --keep-storage has been deprecated, keep-storage flag has been changed to reserved-space' >&2
      echo "buildx-config=$BUILDX_CONFIG"
      printf 'Total:\\t1.2GB\\n'
      exit 0
      """;

  /** A docker that could not do it. */
  private static final String REFUSING_DOCKER =
      """
      #!/bin/bash
      echo 'ERROR: mkdir /work/config/buildx: permission denied' >&2
      exit 1
      """;

  private static DockerContainersDriver driverRunning(Path script, String body) throws IOException {
    Files.writeString(script, body);
    script.toFile().setExecutable(true);
    DockerContainersDriver driver = new DockerContainersDriver();
    driver.runtime = script.toString();
    return driver;
  }

  @Test
  void aDeprecationWarningOnStderrIsNotAFailureWhenTheExitStatusIsZero(@TempDir Path dir)
      throws IOException {
    DockerContainersDriver driver = driverRunning(dir.resolve("docker"), DEPRECATING_DOCKER);

    CacheResult result = driver.pruneBuildCache(20_000_000_000L, Duration.ofSeconds(30));

    assertTrue(result.ok(), "the exit status decides, and it was zero");
    assertEquals(1_200_000_000L, result.bytes());
  }

  @Test
  void bothHostCacheCallsCarryTheBuildxStateDirectoryIntoTheChild(@TempDir Path dir)
      throws IOException {
    // The stand-in writes down what it was handed, so this is the variable really arriving in a
    // child's environment rather than a map being asserted against itself.
    Path seen = dir.resolve("seen");
    DockerContainersDriver driver =
        driverRunning(
            dir.resolve("docker"),
            "#!/bin/bash\necho \"$BUILDX_CONFIG\" >> " + seen + "\nprintf 'Total:\\t1.2GB\\n'\nexit 0\n");

    assertTrue(driver.pruneBuildCache(20_000_000_000L, Duration.ofSeconds(30)).ok());
    assertTrue(driver.describeBuildCache(Duration.ofSeconds(30)).ok());

    assertEquals(
        List.of("/tmp/qits-buildx", "/tmp/qits-buildx"),
        Files.readAllLines(seen),
        "the prune and the du both carry it — the plugin needs it either way");
  }

  @Test
  void theExecIntoABuilderCarriesNoBuildxStateBecauseItIsNotTheHostsPlugin(@TempDir Path dir)
      throws IOException {
    Path seen = dir.resolve("seen");
    DockerContainersDriver driver =
        driverRunning(
            dir.resolve("docker"),
            "#!/bin/bash\necho \"[${BUILDX_CONFIG}]\" >> " + seen + "\nprintf 'Total:\\t0B\\n'\nexit 0\n");

    assertTrue(
        driver
            .describeBuilderCache("buildx_buildkit_qits-bootstrap-builder-v40", Duration.ofSeconds(30))
            .ok());

    assertFalse(
        Files.readString(seen).contains(DockerArgv.BUILDX_CONFIG_DIR),
        "buildctl runs inside the builder, where the host plugin's state is nothing at all");
  }

  @Test
  void aCallThatReallyFailedIsStillAFailure(@TempDir Path dir) throws IOException {
    DockerContainersDriver driver = driverRunning(dir.resolve("docker"), REFUSING_DOCKER);

    CacheResult result = driver.pruneBuildCache(20_000_000_000L, Duration.ofSeconds(30));

    assertFalse(result.ok());
    assertEquals(0, result.bytes());
    assertTrue(result.detail().contains("permission denied"), result.detail());
  }

  @Test
  void anOutputWithNoSummaryLineStillCarriesWhatDockerSaid(@TempDir Path dir) throws IOException {
    // The deprecation warning is the whole output when a prune had nothing to say: detail must not
    // come back empty, or the one line explaining a zero would be dropped.
    DockerContainersDriver driver =
        driverRunning(
            dir.resolve("docker"),
            """
            #!/bin/bash
            echo 'Flag --keep-storage has been deprecated' >&2
            exit 0
            """);

    CacheResult result = driver.pruneBuildCache(20_000_000_000L, Duration.ofSeconds(30));

    assertTrue(result.ok());
    assertTrue(result.detail().contains("deprecated"), result.detail());
  }
}
