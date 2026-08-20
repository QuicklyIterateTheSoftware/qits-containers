package eu.wohlben.qits.containers.dockerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The reading of the socket's group — the fallback, really, because that is the half a host can get
 * wrong. A test cannot make a docker socket, so the claim held here is the one that does not need
 * one: an existing path answers with its own gid, and a path that is not there answers <b>blank</b>
 * rather than a guess or a failure.
 *
 * <p>Blank is what makes the whole change inert on a host with no socket at that path: {@code
 * DockerArgv} renders no {@code --group-add} for it, so the argv is the one this service shipped
 * before the group existed.
 */
class DockerSocketGroupTest {

  @Test
  void aPathThatIsNotThereNamesNoGroup(@TempDir Path dir) {
    assertEquals("", DockerSocketGroup.groupOf(dir.resolve("no-such-socket")));
  }

  @Test
  void anExistingPathAnswersItsOwnGroup(@TempDir Path dir) throws IOException {
    Path file = Files.createFile(dir.resolve("stand-in"));
    assertEquals(
        Files.getAttribute(file, "unix:gid").toString(), DockerSocketGroup.groupOf(file));
  }
}
