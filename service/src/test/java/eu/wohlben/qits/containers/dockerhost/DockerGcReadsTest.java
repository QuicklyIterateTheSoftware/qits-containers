package eu.wohlben.qits.containers.dockerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.control.ContainersDriver.DiskUsage;
import eu.wohlben.qits.containers.control.ContainersDriver.ImageSummary;
import eu.wohlben.qits.containers.control.ContainersDriver.VolumeDetail;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The reading of what the collection's docker calls print.
 *
 * <p><b>Every sample here was taken off docker 29.7.2 on the platform's own host</b> rather than
 * written from the documentation — including the two that are surprises: {@code docker builder
 * prune} ends with {@code Total:} and a tab on 29 where it ended with {@code Total reclaimed space:}
 * on 28, and {@code docker system df} prints the reclaimable share with a percentage after it.
 *
 * <p>Plain JUnit: these are pure functions over strings, and they need no application, no database
 * and no docker to be asked about.
 */
class DockerGcReadsTest {

  @Test
  void readsHumanSizesAsDecimalBytes() {
    // Docker's own HumanSize is decimal: 1kB is a thousand, not 1024.
    assertEquals(0, DockerGcReads.bytes("0B"));
    assertEquals(262_100, DockerGcReads.bytes("262.1kB"));
    assertEquals(325_000_000, DockerGcReads.bytes("325MB"));
    assertEquals(308_300_000_000L, DockerGcReads.bytes("308.3GB"));
    assertEquals(33_490_000_000L, DockerGcReads.bytes("33.49GB"));
    assertEquals(286_800_000_000L, DockerGcReads.bytes("286.8GB (93%)"));
  }

  @Test
  void readsTheBinarySpellingsBuildkitSometimesPrints() {
    assertEquals(1024, DockerGcReads.bytes("1KiB"));
    assertEquals(1_073_741_824L, DockerGcReads.bytes("1GiB"));
  }

  @Test
  void aSizeThatWillNotReadIsZeroRatherThanAGuess() {
    assertEquals(0, DockerGcReads.bytes(null));
    assertEquals(0, DockerGcReads.bytes("<no value>"));
    assertEquals(0, DockerGcReads.bytes("N/A"));
  }

  @Test
  void readsTheFourStoresOfSystemDf() {
    DiskUsage usage =
        DockerGcReads.diskUsage(
            """
            Images|425|18|308.3GB|286.8GB (93%)
            Containers|37|18|29.9MB|262.1kB (0%)
            Local Volumes|23|7|33.49GB|1.492GB (4%)
            Build Cache|1877|0|302.5GB|103.5GB
            """);

    assertEquals(425, usage.images().count());
    assertEquals(18, usage.images().active());
    assertEquals(308_300_000_000L, usage.images().sizeBytes());
    assertEquals(286_800_000_000L, usage.images().reclaimableBytes());
    assertEquals(37, usage.containers().count());
    assertEquals(23, usage.volumes().count(), "docker calls them Local Volumes");
    assertEquals(1877, usage.buildCache().count());
    assertEquals(103_500_000_000L, usage.buildCache().reclaimableBytes());
  }

  @Test
  void aStoreDockerNamedNoLineForIsZeroAndNeverNull() {
    DiskUsage usage = DockerGcReads.diskUsage("Images|1|1|1GB|0B");

    assertEquals(0, usage.buildCache().count());
    assertEquals(0, usage.buildCache().sizeBytes());
  }

  @Test
  void foldsTheTagsOfOneImageOntoOneId() {
    List<ImageSummary> images =
        DockerGcReads.images(
            """
            sha256:14beaf669b73a3a348fe5c82e7129f42394a933dd8b81e358e430964148397bb|registry.dev.localhost:8080/qits/qits-ci|3e501b6684b14a4ffdc0e956ef3af58e1573730b|2026-08-18 04:46:43 +0200 CEST|325MB
            sha256:14beaf669b73a3a348fe5c82e7129f42394a933dd8b81e358e430964148397bb|registry.dev.localhost:8080/qits/qits-ci|latest|2026-08-18 04:46:43 +0200 CEST|325MB
            """);

    assertEquals(1, images.size());
    assertEquals(
        List.of(
            "registry.dev.localhost:8080/qits/qits-ci:3e501b6684b14a4ffdc0e956ef3af58e1573730b",
            "registry.dev.localhost:8080/qits/qits-ci:latest"),
        images.getFirst().tags());
    assertEquals(325_000_000L, images.getFirst().sizeBytes());
    assertEquals(Instant.parse("2026-08-18T02:46:43Z"), images.getFirst().createdAt());
  }

  @Test
  void anAllListingsNoneRowsAreTheDanglingImagesAndTheTaggedOnesAreUntouched() {
    // What `image ls --all` really looks like on the containerd store: the tagged images, then the
    // <none> rows a plain listing does not print at all. 55 of these survived two collection runs.
    List<ImageSummary> images =
        DockerGcReads.images(
            """
            sha256:1111111111111111111111111111111111111111111111111111111111111111|registry:8080/qits/qits-ci|cafe|2026-08-18 04:46:43 +0200 CEST|325MB
            sha256:2222222222222222222222222222222222222222222222222222222222222222|<none>|<none>|2026-08-18 04:00:52 +0200 CEST|603MB
            sha256:3333333333333333333333333333333333333333333333333333333333333333|<none>|<none>|2026-08-17 03:51:02 +0200 CEST|391MB
            """);

    assertEquals(3, images.size());
    assertEquals(1, images.stream().filter(image -> !image.tags().isEmpty()).count());
    assertEquals(
        2,
        images.stream().filter(image -> image.tags().isEmpty()).count(),
        "the <none> rows are the dangling images, and they are what --all is for");
  }

  @Test
  void anImageWithNoneInBothColumnsIsDangling() {
    List<ImageSummary> images =
        DockerGcReads.images(
            """
            sha256:3333333333333333333333333333333333333333333333333333333333333333|<none>|<none>|2026-08-11 17:03:01 +0200 CEST|90MB
            """);

    assertEquals(1, images.size());
    assertTrue(images.getFirst().tags().isEmpty(), "no tag names it — that is the whole definition");
  }

  @Test
  void readsTheCreatedAtDockerPrintsForPeople() {
    // 2026-08-18 04:46:43 +0200 is 02:46:43 UTC. The trailing zone NAME is deliberately ignored:
    // the offset in front of it already says the instant.
    assertEquals(
        Instant.parse("2026-08-18T02:46:43Z"),
        DockerGcReads.imageCreatedAt("2026-08-18 04:46:43 +0200 CEST"));
    assertNull(DockerGcReads.imageCreatedAt("<no value>"));
    assertNull(DockerGcReads.imageCreatedAt(""));
  }

  @Test
  void readsAVolumesTimeAndItsLabels() {
    VolumeDetail detail =
        DockerGcReads.volumeDetail(
            "1a398b20",
            """
            2026-08-11T17:03:01+02:00
            com.docker.volume.anonymous=
            qits.containers.managed=volume
            """);

    assertEquals(Instant.parse("2026-08-11T15:03:01Z"), detail.createdAt());
    assertEquals("volume", detail.labels().get("qits.containers.managed"));
    assertEquals("", detail.labels().get("com.docker.volume.anonymous"));
  }

  @Test
  void anUnlabelledVolumeIsOneLineAndNoLabels() {
    VolumeDetail detail = DockerGcReads.volumeDetail("qits_shared_m2", "2026-08-08T09:57:31+02:00");

    assertEquals(Instant.parse("2026-08-08T07:57:31Z"), detail.createdAt());
    assertTrue(detail.labels().isEmpty());
  }

  @Test
  void readsWhatAPruneReclaimedInBothSpellingsDockerHasUsed() {
    // Docker 29's buildx prune, measured.
    assertEquals(0, DockerGcReads.reclaimedBytes("Total:\t0B"));
    assertEquals(1_093_000_000L, DockerGcReads.reclaimedBytes("Total:\t1.093GB"));
    // Docker 28 and earlier.
    assertEquals(
        1_200_000_000L, DockerGcReads.reclaimedBytes("Total reclaimed space: 1.2GB"));
  }

  @Test
  void readsWhatADuSaysIsReclaimable() {
    String du =
        """
        tkbcthf8vndr0zh744zfh331p    true          2.981GB*   12 days ago
        Shared:\t199GB
        Private:\t103.5GB
        Reclaimable:\t302.5GB
        Total:\t302.5GB
        """;

    assertEquals(302_500_000_000L, DockerGcReads.reclaimableBytes(du));
    assertEquals(
        "Shared: 199GB; Private: 103.5GB; Reclaimable: 302.5GB; Total: 302.5GB",
        DockerGcReads.cacheSummary(du),
        "the per-record lines are dropped — there are thousands of them on a build host");
  }

  @Test
  void aDuWithNoReclaimableLineFallsBackToItsTotal() {
    assertEquals(27_110_000_000L, DockerGcReads.reclaimableBytes("Total:\t27.11GB"));
  }
}
