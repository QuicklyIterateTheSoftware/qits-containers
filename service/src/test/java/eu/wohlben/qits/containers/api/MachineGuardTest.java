package eu.wohlben.qits.containers.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

/**
 * Every route with the gate ON — the posture a deployment reaches by setting
 * {@code QITS_AUTH_MACHINE_REQUIRED=true} once qits-idp grants the {@code qits-containers}
 * audience.
 *
 * <p>Tokens are real: signed RS256, verified by quarkus-oidc against the public key in
 * {@link MachineGuardProfile}. So these cases fail if the OIDC configuration in
 * application.properties is wrong, not only if a guard is missing.
 *
 * <p><b>The claim under test is one no sibling makes: the owner in the path is the caller.</b>
 * Every other service in the fleet asks whether a token was granted a {@code project} or a
 * {@code workspace}; this one asks whether the token <em>is</em> the owner whose rows the request
 * addresses. The subject carries its environment — {@code dev-qits-ci} and {@code prod-qits-ci} are
 * two owners — and that prefix is exactly what keeps two environments sharing one docker daemon
 * from reaching each other's containers.
 *
 * <p><b>Two doors, and this suite pins which one shuts first.</b> A token minted for another
 * service is refused by {@code quarkus.oidc.token.audience} before {@code MachineAuth} ever sees the
 * identity, so the answer is a 401 challenge rather than the guard's own 403. {@code MachineAuth}'s
 * audience check is the second belt, reachable only if that validation is ever loosened, and it is
 * asserted nowhere here because it cannot be produced without loosening it. The 403 this suite does
 * assert is the owner guard's, which is this service's own decision and the one that could be
 * dropped by a new route forgetting to call it.
 */
@QuarkusTest
@TestProfile(MachineGuardProfile.class)
class MachineGuardTest {

  /** The caller, and therefore the owner of every row it may reach. */
  private static final String OWNER = "dev-qits-ci";

  /** Another platform module, with a perfectly good token of its own. */
  private static final String OTHER_OWNER = "dev-qits-workspaces";

  /** This service's id — the config default, injected per environment in a deployment. */
  private static final String OWN_AUDIENCE = "qits-containers";

  /** A valid platform audience that is not ours. */
  private static final String FOREIGN_AUDIENCE = "prod-qits-deployments";

  private static final String OWN_PLACE = "/containers/api/containers/dev-qits-ci/step/guarded";

  private static final String OTHER_PLACE =
      "/containers/api/containers/dev-qits-workspaces/step/guarded";

  private static final String SPEC =
      """
      {"spec":{"image":"alpine:3","network":"qits-net"},"policy":{"type":"EXPLICIT"}}""";

  @Test
  void aCallWithNoMachineTokenIsFourOhOne() {
    // 401, not 403: nothing was presented, so the answer is "present something". The forward-auth
    // dev user is blanked in this profile, and a user is not a machine in any case.
    given().contentType(ContentType.JSON).body(SPEC).when().put(OWN_PLACE).then().statusCode(401);
  }

  @Test
  void aReadWithNoMachineTokenIsFourOhOneToo() {
    // The half of the rule this service does not share with the fleet: nothing here is open,
    // because nothing here is read by a person.
    given().when().get("/containers/api/containers/dev-qits-ci").then().statusCode(401);
  }

  @Test
  void aTokenMintedForAnotherServiceIsRefused() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.token(OWNER, FOREIGN_AUDIENCE))
        .body(SPEC)
        .when()
        .put(OWN_PLACE)
        .then()
        .statusCode(401);
  }

  @Test
  void aTokenThatIsNotThisOwnerIsForbiddenFromItsRows() {
    // The token is impeccable — right issuer, right signature, right audience — and it is somebody
    // else's. Writes and reads alike: an inventory is as much a module's own as its containers are.
    machine()
        .contentType(ContentType.JSON)
        .body(SPEC)
        .when()
        .put(OTHER_PLACE)
        .then()
        .statusCode(403);

    machine().when().get("/containers/api/containers/dev-qits-workspaces").then().statusCode(403);
    machine().when().get("/containers/api/volumes/dev-qits-workspaces/whatever").then().statusCode(403);
  }

  @Test
  void aTokenThatIsThisOwnerReachesItsOwnRows() {
    machine()
        .contentType(ContentType.JSON)
        .body(SPEC)
        .when()
        .put(OWN_PLACE)
        .then()
        .statusCode(201)
        .body("state.observed", is("RUNNING"));

    machine().when().get(OWN_PLACE).then().statusCode(200);
    machine().when().get("/containers/api/containers/dev-qits-ci").then().statusCode(200);
    machine().when().delete(OWN_PLACE).then().statusCode(200).body("existed", is(true));
  }

  /** A caller holding a fresh token of its own, addressed to this service. */
  private static RequestSpecification machine() {
    return given()
        .header("Authorization", "Bearer " + MachineTokens.token(OWNER, OWN_AUDIENCE));
  }
}
