/**
 * The consumer-facing client for qits-containers: the wire records, the four-outcome answer and the
 * {@code HttpClient} behind them.
 *
 * <p>It depends on {@code qits-containers-core} not at all. That is the module's whole reason to
 * exist: a caller wants a few records and a client, while the domain jar carries entities, a Flyway
 * lineage and datasource defaults that would arrive in every consumer with it.
 *
 * <p><b>The wire is the contract.</b> The records here mirror the JSON the service answers with —
 * they are not the service's own DTOs shared, and they are not the domain's records. Each side
 * names its own types over one agreed body, which is the only arrangement in which a consumer can
 * be built and released without this repository.
 *
 * <p>{@link eu.wohlben.qits.containers.client.ContainersClient} is a plain class: a consumer's own
 * CDI producer makes it a bean, and this jar brings no container along to do it. See the README's
 * client section for the one-line producer and for what a native consumer has to register.
 */
package eu.wohlben.qits.containers.client;
