package eu.wohlben.qits.containers.dockerhost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.containers.control.ContainersDriver;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The reading of what docker says — the only part of the driver that is not argv or process, and
 * therefore the only part with anywhere to hide a bug.
 *
 * <p>Plain JUnit: this is a pure function over the line the format produces, and the line itself is
 * the one measured against docker 29.7.2 (see {@code DockerArgv.OBSERVATION_FORMAT}). What the
 * daemon actually does with that format is the extended IT's claim; what this makes is the smaller
 * one that the answer is read back the way it was written.
 */
class DockerContainersDriverTest {

  @Test
  void aRunningContainerWithNoHealthcheckReadsAsRunningAndNone() {
    ContainersDriver.Observed observed =
        DockerContainersDriver.parseObservation(
            "c", "418e175b0feb|running/none|2026-08-11T18:57:48.380731023Z");
    assertEquals("418e175b0feb", observed.id());
    assertEquals("running", observed.status());
    assertEquals("none", observed.health());
    assertEquals(Instant.parse("2026-08-11T18:57:48.380731023Z"), observed.startedAt());
  }

  @Test
  void aHealthyContainerKeepsTheTwoStatesApart() {
    ContainersDriver.Observed observed =
        DockerContainersDriver.parseObservation(
            "c", "cbb0806b7bf6|running/healthy|2026-08-11T18:57:48.917211731Z");
    assertEquals("running", observed.status());
    assertEquals("healthy", observed.health());
  }

  @Test
  void goesNoValueIsAnAbsenceInEveryField() {
    // The belt qits-platform-deployments learned by measurement: Go prints `<no value>` for a field
    // the object does not carry, and read back it would be a health state no container has.
    ContainersDriver.Observed observed =
        DockerContainersDriver.parseObservation("c", "<no value>|exited/<no value>|<no value>");
    assertEquals("", observed.id());
    assertEquals("exited", observed.status());
    assertEquals("none", observed.health());
    assertNull(observed.startedAt());
  }

  @Test
  void aContainerThatWasNeverStartedHasNoStartTimeRatherThanTheYearOne() {
    // Docker prints 0001-01-01T00:00:00Z for a created-and-never-started container. It parses
    // perfectly and means nothing, so it is answered as an absence like any other.
    ContainersDriver.Observed observed =
        DockerContainersDriver.parseObservation("c", "abc|created/none|0001-01-01T00:00:00Z");
    assertEquals("created", observed.status());
    assertNull(observed.startedAt());
  }

  @Test
  void aTruncatedLineNeverThrows() {
    // The output is a daemon's, not this service's. A short line is missing information, never a
    // reason to fail an observation pass.
    ContainersDriver.Observed observed = DockerContainersDriver.parseObservation("c", "abc");
    assertEquals("abc", observed.id());
    assertEquals("", observed.status());
    assertEquals("none", observed.health());
    assertNull(observed.startedAt());
  }
}
