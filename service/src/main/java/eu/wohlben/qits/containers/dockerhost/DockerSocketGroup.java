package eu.wohlben.qits.containers.dockerhost;

import eu.wohlben.qits.containers.docker.DockerArgv;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Which group owns the host's docker socket — the one value {@link DockerArgv#run} renders as a
 * {@code --group-add}, and only ever beside the socket bind itself.
 *
 * <p><b>Why this exists at all.</b> The socket is {@code srw-rw----} owned by the host's docker
 * group, so a container that holds the bind and runs as anybody but root is still refused by the
 * kernel when it connects. Every workload that opted in until now ran as the image's own root
 * (qits-ci's steps), which is why the gap was invisible; a workspace container runs as the host uid,
 * so for it the bind alone is inert.
 *
 * <p><b>It is read off the socket, not asked of a caller.</b> The group is a fact about this host,
 * and this process is the one holding the socket — the same {@code unix:gid} the platform bootstrap
 * reads when it decides which group to start this service in. A caller-supplied group would be the
 * assembled privilege {@code ContainerSpec} is shaped to prevent, and a group nothing binds beside
 * would be a membership with no bind to justify it.
 *
 * <p><b>Absent is a supported configuration, and it answers blank.</b> A host with no socket at that
 * path (a suite, a {@code quarkus:dev}, a rootless daemon on another path) renders no flag at all,
 * which is exactly the argv this service shipped before — so nothing about a workload that does not
 * opt in can change here, whatever the host looks like.
 *
 * <p>{@code qits.containers.docker-socket-group} overrides the reading for the deployment whose
 * socket is reachable under a group its path does not report. Read once and cached: the socket's
 * ownership is a boot-time fact of the host, and re-stat'ing it per container start would put a
 * filesystem call on every launch to answer the same number.
 */
@ApplicationScoped
public class DockerSocketGroup {

  private static final Logger LOG = Logger.getLogger(DockerSocketGroup.class);

  /** The explicit answer, when a deployment has one. Blank/absent means "read the socket". */
  @ConfigProperty(name = "qits.containers.docker-socket-group")
  Optional<String> configured;

  private volatile String resolved;

  /** The group to join beside a socket bind, or blank when there is none to name. */
  public String value() {
    String known = resolved;
    if (known == null) {
      known = configured.filter(v -> !v.isBlank()).map(String::trim).orElseGet(this::read);
      resolved = known;
      LOG.infof(
          "The docker socket's group is %s",
          known.isEmpty() ? "unknown; a socket bind will join no group" : known);
    }
    return known;
  }

  /** The socket's own group, or blank when it cannot be read. */
  private String read() {
    return groupOf(Path.of(DockerArgv.DOCKER_SOCKET));
  }

  /**
   * The gid owning {@code path}, as a string, or blank when the path is absent or unreadable.
   *
   * <p>Package-private so the suite can point it at a file it made: a socket is not something a test
   * can create, and the claim worth holding is the one about the fallback — an absent path is an
   * absent group, never a guess and never a failure.
   */
  static String groupOf(Path path) {
    try {
      Object gid = Files.getAttribute(path, "unix:gid");
      return gid == null ? "" : gid.toString();
    } catch (IOException | RuntimeException e) {
      return "";
    }
  }
}
