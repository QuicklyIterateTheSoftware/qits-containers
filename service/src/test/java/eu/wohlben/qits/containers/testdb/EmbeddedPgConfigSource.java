package eu.wohlben.qits.containers.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the three
 * keys a deployment would supply — {@code jdbc.url}, {@code username}, {@code password} — for
 * <b>both</b> datasources this deployable boots.
 *
 * <p>Two of them, because the qits-eventstream jar arrives with its own outbox: its datasource, its
 * persistence unit and its Flyway lineage. Being dark in {@code %test} does not stop any of that —
 * {@code qits.eventstream.enabled=false} stops publishing, sweeping and dialling, while Quarkus
 * still opens the connection and migrates at boot. So the outbox gets a database here or the suite
 * does not start.
 *
 * <p>It is a config source rather than six lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time — the instance
 * takes a free one, so nothing can be written down ahead of the JVM that starts it.
 *
 * <p>The ordinal sits above application.properties (250) so this wins over the shipped defaults in
 * both jars (100) and anything the test properties file might carry, and it is registered through
 * {@code META-INF/services}, which is how a config source joins a Quarkus application without being
 * a bean.
 *
 * <p>What it supplies are the same keys the two shipped files resolve from {@code
 * QITS_RESOURCE_DB_*} and {@code QITS_RESOURCE_EVENTSTREAM_*}. The suite sets the VALUES rather than
 * the variables on purpose: the shipped expressions have no defaults, and a test run that also had
 * to export environment variables would be a test run that could not say what happens when they are
 * missing.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /** This service's own store: the container and volume registry. */
  private static final String CONTAINERS_DATABASE = "containers_svc";

  /**
   * The outbox's store. Named for this repository too, and deliberately NOT {@code
   * eventstream_test} — that is the qits-eventstream library's own suite's database, and a consumer
   * must not be able to mean it.
   */
  private static final String EVENTSTREAM_DATABASE = "eventstream_containers";

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
