package eu.wohlben.qits.containers;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The service boots, and the probe qits-deployments gates a cutover on answers where the deployment
 * spec will name it.
 *
 * <p><b>The path is the first claim.</b> {@code quarkus.http.non-application-root-path=/containers/q}
 * moves health under this service's gateway segment, so readiness is {@code
 * /containers/q/health/ready} and the bare {@code /q/health/ready} does not exist. A deployer's
 * derived default would poll the second one.
 *
 * <p><b>The database being IN the readiness answer is the second claim.</b> quarkus-agroal
 * contributes a readiness check per datasource on its own, with no code here; what this pins is
 * that it really does, and that the check names both stores this process opens. An orchestrator
 * whose registry is unreachable must never be promoted: the rows are the only record of which
 * containers may exist, and one that cannot read them sees every running container as unnamed.
 *
 * <p>The check COUNT is deliberately not pinned — a Quarkus upgrade adding a check is not a
 * regression, whereas a datasource check disappearing is.
 */
@QuarkusTest
class ContainersHealthSurfaceTest {

  @Test
  void readinessAnswersUnderTheServiceSegmentAndCoversBothStores() {
    given()
        .when()
        .get("/containers/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", is("UP"))
        .body("checks.name", hasItem("Database connections health check"))
        .body("checks.find { it.name == 'Database connections health check' }.status", is("UP"))
        .body("checks.find { it.name == 'Database connections health check' }.data.containers",
            is("UP"))
        .body("checks.find { it.name == 'Database connections health check' }.data.eventstream",
            is("UP"));
  }

  @Test
  void livenessAnswersThereTooAndIsNotWhatTheGateShouldPoll() {
    given().when().get("/containers/q/health/live").then().statusCode(200).body("status", is("UP"));
  }

  @Test
  void theUnprefixedPathDoesNotExist() {
    given().when().get("/q/health/ready").then().statusCode(404);
  }
}
