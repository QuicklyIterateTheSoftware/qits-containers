package eu.wohlben.qits.containers.proxy;

import eu.wohlben.qits.containers.control.FakeContainersDriver;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;
import java.util.Set;

/**
 * The data plane switched on: {@code qits.containers.proxy.enabled=true}, which no deployment sets
 * yet and which the shipped default is the other side of.
 *
 * <p>It is its own profile rather than a key in the test resources, and that is the same rule the
 * gate itself is under: the suite has to hold <b>both</b> postures, because "off means nothing is
 * bound" is a claim about the shipped configuration and "on means a request round-trips" is a claim
 * about the other one. A test-resources key would have deleted the first claim.
 *
 * <p>The driver is the scripted fake, for the reason every profile here has one: what is under test
 * is the byte path between a caller and a container, and staging a row for it should not start a
 * container.
 */
public class TunnelEnabledProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("qits.containers.proxy.enabled", "true");
  }

  @Override
  public Set<Class<?>> getEnabledAlternatives() {
    return Set.of(FakeContainersDriver.class);
  }
}
