package eu.wohlben.qits.containers.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in <b>this module</b>, as the
 * three keys a deployment would supply — {@code jdbc.url}, {@code username}, {@code password} — for
 * both datasources this module's tests boot.
 *
 * <p>Two of them, because {@code core} takes the qits-eventstream jar for the causation persistence
 * trio and that jar arrives with its own outbox: a datasource, a persistence unit and a Flyway
 * lineage. Being dark in the suite does not stop any of it — {@code qits.eventstream.enabled=false}
 * stops publishing, sweeping and dialling, while Quarkus still opens the connection and migrates at
 * boot. So the outbox gets a database here or the suite does not start.
 *
 * <p><b>A COPY of the pair in {@code service}, naming DIFFERENT databases</b>, which is the point of
 * the copy. Maven has no {@code testFixtures} scope and a test-jar dependency between two modules
 * that otherwise have none is the higher price — the same stance the two {@code
 * FakeContainersDriver}s take. What must never be shared is the database NAME: two surefire JVMs
 * running on one host would otherwise clean each other's schema mid-suite, and the failure reads as
 * a flaky test rather than as one instance wiping another.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /** This module's own store — {@code service} names {@code containers_svc}. */
  private static final String CONTAINERS_DATABASE = "containers_core";

  /**
   * The outbox's store for this module. Named for the module as well as the repository, and
   * deliberately neither {@code eventstream_test} (the qits-eventstream library's own suite's) nor
   * {@code eventstream_containers} ({@code service}'s).
   */
  private static final String EVENTSTREAM_DATABASE = "eventstream_containers_core";

  private final Map<String, String> values =
      Map.of(
          "quarkus.datasource.containers.jdbc.url", EmbeddedPg.url(CONTAINERS_DATABASE),
          "quarkus.datasource.containers.username", EmbeddedPg.USER,
          "quarkus.datasource.containers.password", EmbeddedPg.PASSWORD,
          "quarkus.datasource.eventstream.jdbc.url", EmbeddedPg.url(EVENTSTREAM_DATABASE),
          "quarkus.datasource.eventstream.username", EmbeddedPg.USER,
          "quarkus.datasource.eventstream.password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
