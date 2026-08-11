package eu.wohlben.qits.containers.control;

import eu.wohlben.qits.containers.spec.ContainerSpec;
import eu.wohlben.qits.containers.spec.LifecyclePolicy;
import eu.wohlben.qits.containers.spec.VolumeSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The suite's stand-in for the docker seam — a scripted fake, not an honest one: it performs
 * nothing, records every call in arrival order, and answers what a test told it to. That is what
 * keeps a clone's {@code ./mvnw verify} docker-free, which matters more in this repository than
 * anywhere: docker is its subject, and a suite that needed a daemon to say what the orchestrator
 * does could never say it.
 *
 * <p><b>It is DUPLICATED per module rather than shared, and that is the house pattern rather than an
 * oversight.</b> Maven has no {@code testFixtures} scope, and the alternatives are a test-jar
 * dependency between modules that otherwise have none. qits-workspaces carries two copies of its
 * {@code FakeContainerRuntime} for exactly this reason, and qits-ci two of {@code FakeCiStepRunner}.
 * This copy is {@code core}'s; a module that needs one copies it, and the copies are free to diverge
 * to what each suite actually scripts.
 *
 * <p><b>The call log is the point.</b> Half of what this service has to get right is ORDER — a row
 * before a run, logs before a removal, an adopt before anything else at boot — and order is not
 * visible in return values. Every method appends one {@code kind:target} line and {@link #calls()}
 * is what a test asserts against.
 *
 * <p><b>This copy is an {@code @Alternative} with no priority, and that is the one line that
 * differs from {@code core}'s.</b> This module ships {@code DockerContainersDriver} — an ordinary
 * bean — so an ordinary bean here would be an ambiguous resolution rather than an override, and a
 * globally enabled alternative would take the real driver away from the one test that needs it:
 * {@code ContainersRestartAdoptionIT} proves the adoption against a real daemon. An alternative
 * with no priority is disabled until a {@code QuarkusTestProfile} names it in {@code
 * getEnabledAlternatives()}, so each suite says which driver it is talking to. Same arrangement as
 * qits-workspaces' {@code FakeWorkspaceServiceDriver}.
 *
 * <p>Read its state through its METHODS in a {@code @QuarkusTest}: the injected reference is a CDI
 * client proxy, and a field read on a proxy sees the proxy's fields rather than the bean's.
 *
 * <p><b>Two hooks exist for claims that cannot be made any other way.</b> {@link #duringRun} runs
 * something at the instant {@code run} is entered, which is how "the row already existed, and said
 * PENDING, before docker was asked for anything" becomes an assertion rather than an inference from
 * ordering. {@link #scriptDown} makes every container-touching call throw, which is a docker daemon
 * that is not there — the state a boot sweep has to survive without failing a boot.
 */
@jakarta.enterprise.inject.Alternative
@jakarta.enterprise.context.ApplicationScoped
public class FakeContainersDriver implements ContainersDriver {

  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
  private final List<ContainerSpec> ranSpecs = Collections.synchronizedList(new ArrayList<>());
  private final List<VolumeSpec> ensuredVolumes = Collections.synchronizedList(new ArrayList<>());

  private final Map<String, Observed> containers = new ConcurrentHashMap<>();
  private final Map<String, String> logs = new ConcurrentHashMap<>();
  private final Map<String, List<String>> labelListings = new ConcurrentHashMap<>();
  private final Map<String, List<String>> volumeListings = new ConcurrentHashMap<>();

  private volatile Started nextRun = new Started(true, "fake-id", null);
  private volatile OpResult nextOp = new OpResult(true, null);
  private volatile OpResult nextPull = new OpResult(true, null);
  private volatile boolean networkPresent = true;
  private volatile String selfId = "";
  private volatile String down;
  private volatile java.util.function.Consumer<String> duringRun;

  public void reset() {
    calls.clear();
    ranSpecs.clear();
    ensuredVolumes.clear();
    containers.clear();
    logs.clear();
    labelListings.clear();
    volumeListings.clear();
    nextRun = new Started(true, "fake-id", null);
    nextOp = new OpResult(true, null);
    nextPull = new OpResult(true, null);
    networkPresent = true;
    selfId = "";
    down = null;
    duringRun = null;
  }

  /** Every driver call in arrival order, tagged {@code kind:target}. */
  public List<String> calls() {
    return List.copyOf(calls);
  }

  public List<ContainerSpec> ranSpecs() {
    return List.copyOf(ranSpecs);
  }

  public List<VolumeSpec> ensuredVolumes() {
    return List.copyOf(ensuredVolumes);
  }

  public void scriptRun(Started result) {
    nextRun = result;
  }

  public void scriptOp(OpResult result) {
    nextOp = result;
  }

  public void scriptPull(OpResult result) {
    nextPull = result;
  }

  /**
   * What {@link #inspect} answers for this name. A name nothing scripted is <b>absent</b>: this fake
   * performs nothing, so the only containers docker could have are the ones a test said exist.
   */
  public void scriptContainer(String name, String status, String health, Instant startedAt) {
    containers.put(name, new Observed(name + "-id", status, health, startedAt));
  }

  public void scriptGone(String name) {
    containers.remove(name);
  }

  public void scriptLogs(String name, String text) {
    logs.put(name, text);
  }

  public void scriptLabelListing(Map<String, String> filters, List<String> ids) {
    labelListings.put(key(filters), List.copyOf(ids));
  }

  public void scriptVolumeListing(Map<String, String> filters, List<String> names) {
    volumeListings.put(key(filters), List.copyOf(names));
  }

  public void scriptNetworkPresent(boolean present) {
    networkPresent = present;
  }

  public void scriptSelfId(String id) {
    selfId = id;
  }

  /**
   * Every container-touching call throws from now on — a docker daemon that is not there, which is
   * the ordinary state of a host that has just rebooted. {@code null} puts it back.
   */
  public void scriptDown(String message) {
    down = message;
  }

  /**
   * Run something at the instant {@code run} is entered, before anything is recorded. It is how a
   * test says "the registry row was already committed, and said PENDING, when docker was asked" —
   * a claim about ORDER that no return value carries.
   */
  public void duringRun(java.util.function.Consumer<String> hook) {
    duringRun = hook;
  }

  /** The daemon's refusal to answer, if a test scripted one. */
  private void refuseIfDown(String what) {
    String message = down;
    if (message != null) {
      throw new IllegalStateException("docker is not answering (" + what + "): " + message);
    }
  }

  @Override
  public Started run(
      ContainerSpec spec,
      String name,
      Map<String, String> labels,
      LifecyclePolicy policy,
      Duration timeout) {
    java.util.function.Consumer<String> hook = duringRun;
    if (hook != null) {
      hook.accept(name);
    }
    refuseIfDown("run " + name);
    calls.add("run:" + name);
    ranSpecs.add(spec);
    if (nextRun.started()) {
      containers.put(name, new Observed(nextRun.containerId(), "running", "none", Instant.EPOCH));
    }
    return nextRun;
  }

  @Override
  public Optional<Observed> inspect(String name, Duration timeout) {
    refuseIfDown("inspect " + name);
    calls.add("inspect:" + name);
    return Optional.ofNullable(containers.get(name));
  }

  @Override
  public OpResult stop(String name, Duration timeout) {
    refuseIfDown("stop " + name);
    calls.add("stop:" + name);
    return nextOp;
  }

  @Override
  public OpResult remove(String name, Duration timeout) {
    refuseIfDown("remove " + name);
    calls.add("remove:" + name);
    // A remove that reports failure did not remove: the container is still there afterwards, which
    // is what lets a test say what the registry does when docker cannot perform one.
    if (nextOp.ok()) {
      containers.remove(name);
    }
    return nextOp;
  }

  @Override
  public LogTail logsTail(String name, int lines, Duration timeout, int maxChars) {
    refuseIfDown("logs " + name);
    calls.add("logs:" + name);
    String text = logs.getOrDefault(name, "");
    if (text.length() <= maxChars) {
      return new LogTail(text, false);
    }
    return new LogTail(text.substring(text.length() - maxChars), true);
  }

  @Override
  public List<String> listByLabels(Map<String, String> filters, Duration timeout) {
    calls.add("listByLabels:" + key(filters));
    return labelListings.getOrDefault(key(filters), List.of());
  }

  @Override
  public OpResult ensureVolume(VolumeSpec spec, Map<String, String> labels, Duration timeout) {
    calls.add("ensureVolume:" + spec.name());
    ensuredVolumes.add(spec);
    return nextOp;
  }

  @Override
  public OpResult removeVolume(String name, Duration timeout) {
    calls.add("removeVolume:" + name);
    return nextOp;
  }

  @Override
  public List<String> listVolumesByLabels(Map<String, String> filters, Duration timeout) {
    calls.add("listVolumesByLabels:" + key(filters));
    return volumeListings.getOrDefault(key(filters), List.of());
  }

  @Override
  public OpResult pull(String imageRef, Duration timeout, int maxChars) {
    calls.add("pull:" + imageRef);
    return nextPull;
  }

  @Override
  public boolean networkPresent(String network, Duration timeout) {
    calls.add("networkPresent:" + network);
    return networkPresent;
  }

  @Override
  public String selfContainerId() {
    calls.add("selfContainerId");
    return selfId;
  }

  /** Filters as one comparable string, sorted, so scripting and lookup cannot disagree on order. */
  private static String key(Map<String, String> filters) {
    return new java.util.TreeMap<>(filters)
        .entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .reduce((a, b) -> a + "," + b)
            .orElse("");
  }
}
