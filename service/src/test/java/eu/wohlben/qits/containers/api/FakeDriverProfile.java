package eu.wohlben.qits.containers.api;

import eu.wohlben.qits.containers.control.FakeContainersDriver;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Set;

/**
 * The profile that puts the scripted fake behind the docker seam instead of the real driver.
 *
 * <p><b>A profile rather than a global {@code @Mock}</b>, because this module has one test that
 * must talk to a real daemon: {@code ContainersRestartAdoptionIT} is the proof that a restart
 * adopts a running container, and it is worth nothing against a fake. An enabled-alternative set is
 * the narrowest way to say which of the two a suite means, and it makes the answer visible on the
 * test class rather than hidden in an annotation on the bean.
 */
public class FakeDriverProfile implements QuarkusTestProfile {

  @Override
  public Set<Class<?>> getEnabledAlternatives() {
    return Set.of(FakeContainersDriver.class);
  }
}
