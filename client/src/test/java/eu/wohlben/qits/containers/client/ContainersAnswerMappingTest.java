package eu.wohlben.qits.containers.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.containers.client.ContainersAnswer.Created;
import eu.wohlben.qits.containers.client.ContainersAnswer.Ready;
import eu.wohlben.qits.containers.client.ContainersAnswer.Refused;
import eu.wohlben.qits.containers.client.ContainersAnswer.Unreachable;
import eu.wohlben.qits.containers.client.ContainersWire.EnsureRequest;
import eu.wohlben.qits.containers.client.ContainersWire.Envelope;
import eu.wohlben.qits.containers.client.ContainersWire.Observed;
import eu.wohlben.qits.containers.client.ContainersWire.Policy;
import eu.wohlben.qits.containers.client.ContainersWire.Spec;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A canned response in, one of four answers out — including the ones a real service will not
 * produce on demand.
 *
 * <p><b>The claim this file exists for is that REFUSED and UNREACHABLE never meet.</b> Merging them
 * cost real events on 2026-08-10 in the platform's other HTTP client, and it would cost more here:
 * a caller that read "nothing answered" as "the service said no" would conclude its workload was
 * never started and start a second one. So every case below says which of the two it is, and the
 * type system says a third reading is not available.
 */
class ContainersAnswerMappingTest {

  private static final EnsureRequest ENSURE =
      EnsureRequest.of(Spec.of("alpine:3", "qits-net"), Policy.explicitLifetime());

  private static final String ENVELOPE =
      """
      {"id":"5f0c1e34-0000-4000-8000-000000000001","containerName":"qits-ci-step-run-1",
       "state":{"desired":"RUNNING","observed":"RUNNING"},
       "endpoint":{"containerName":"qits-ci-step-run-1","network":"qits-net",
                   "alias":"qits-ci-step-run-1","proxy":null},
       "specHash":"abc","created":true,"detail":null}""";

  private StubContainersServer stub;

  private ContainersClient client;

  @BeforeEach
  void start() throws IOException {
    stub = new StubContainersServer();
    client = client(stub.url(), null);
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  // --- the two success answers ---------------------------------------------------------------------

  @Test
  void aTwoOhOneIsCreatedAndATwoHundredIsReady() {
    stub.script(201, ENVELOPE);
    ContainersAnswer<Envelope> first = client.ensure("qits-ci", "step", "run-1", ENSURE);

    assertInstanceOf(Created.class, first);
    assertTrue(first.succeeded());
    assertTrue(first.created());
    assertFalse(first.refused());
    assertFalse(first.unreachable());
    assertEquals("qits-ci-step-run-1", first.value().containerName());
    assertEquals(Observed.RUNNING, first.value().state().observed());

    stub.script(200, ENVELOPE);
    ContainersAnswer<Envelope> again = client.ensure("qits-ci", "step", "run-1", ENSURE);
    assertInstanceOf(Ready.class, again);
    assertTrue(again.succeeded());
    assertFalse(again.created());
  }

  @Test
  void aTwoOhFourIsReadyWithNoValue() {
    stub.script(204, null);
    ContainersAnswer<Void> touched = client.touch("qits-ci", "step", "run-1");

    assertInstanceOf(Ready.class, touched);
    assertTrue(touched.succeeded());
    assertNull(touched.value());
  }

  @Test
  void anEnsureThatCouldNotStartTheContainerIsStillReady() {
    // The row exists, it says MISSING and it carries what docker said. A true answer the observer
    // keeps working on — NOT a refusal, which would tell a caller its request was rejected.
    stub.script(
        200,
        """
        {"id":"5f0c1e34-0000-4000-8000-000000000001","containerName":"qits-ci-step-run-1",
         "state":{"desired":"RUNNING","observed":"MISSING"},"created":false,
         "detail":"docker: no such host"}""");

    ContainersAnswer<Envelope> answer = client.ensure("qits-ci", "step", "run-1", ENSURE);

    assertTrue(answer.succeeded());
    assertEquals(Observed.MISSING, answer.value().state().observed());
    assertEquals("docker: no such host", answer.value().detail());
  }

  // --- refusals -------------------------------------------------------------------------------------

  @Test
  void theTwoConflictCodesArriveAsTypedRefusals() {
    stub.script(409, """
        {"code":"SPEC_CONFLICT","message":"this workload runs once"}""");
    ContainersAnswer<Envelope> conflict = client.ensure("qits-ci", "step", "run-1", ENSURE);

    assertInstanceOf(Refused.class, conflict);
    assertTrue(conflict.specConflict());
    assertFalse(conflict.imageMissing());
    assertFalse(conflict.unreachable());
    assertEquals(409, ((Refused<Envelope>) conflict).status());
    assertEquals("this workload runs once", ((Refused<Envelope>) conflict).message());

    stub.script(409, """
        {"code":"IMAGE_MISSING","message":"manifest unknown"}""");
    ContainersAnswer<Envelope> missing = client.ensure("qits-ci", "step", "run-1", ENSURE);
    assertTrue(missing.imageMissing());
    assertFalse(missing.specConflict());
  }

  @Test
  void aFourHundredIsInvalidAndAFourOhFourIsARefusalWithItsStatus() {
    stub.script(400, """
        {"code":"INVALID","message":"Invalid network alias: 'Not A Label'"}""");
    assertTrue(client.ensure("qits-ci", "step", "bad", ENSURE).invalid());

    // 404 has no code of its own: it is the service's "no row names this place", and the status is
    // the whole of what it says.
    stub.script(404, "");
    ContainersAnswer<Envelope> absent = client.status("qits-ci", "step", "nothing");
    assertTrue(absent.refused());
    assertEquals(404, ((Refused<Envelope>) absent).status());
    assertEquals("404", ((Refused<Envelope>) absent).code());
  }

  @Test
  void aRefusalFromSomethingThatIsNotThisServiceStillHasACode() {
    // A proxy's 502 with an HTML body. There is no ErrorBody to read, so the status is the word —
    // never a null a caller would have to check for before switching on it.
    stub.script(502, "<html><body>Bad Gateway</body></html>");
    ContainersAnswer<Envelope> answer = client.status("qits-ci", "step", "run-1");

    assertTrue(answer.refused());
    assertEquals("502", ((Refused<Envelope>) answer).code());
    assertNotNull(((Refused<Envelope>) answer).message());
    assertFalse(answer.unreachable(), "a 502 is a response: something answered");
  }

  @Test
  void aRefusalMessageIsBoundedAndCarriesNoSecondLogLine() {
    stub.script(
        500, "{\"code\":\"BOOM\",\"message\":\"first line\\nWARN forged second line\"}");
    ContainersAnswer<Envelope> answer = client.status("qits-ci", "step", "run-1");

    String message = ((Refused<Envelope>) answer).message();
    assertEquals("first line", message, "only the first line, so a body cannot forge a log entry");
  }

  @Test
  void aBodyThisClientCannotReadIsARefusalAndNeverAnUnreachableService() {
    // The network is fine, the deadline was met, something answered — so the evidence is about the
    // response. A caller retrying this forever would be retrying against a body that will not
    // change.
    stub.script(200, "not json at all");
    ContainersAnswer<Envelope> answer = client.status("qits-ci", "step", "run-1");

    assertTrue(answer.refused());
    assertFalse(answer.unreachable());
    assertEquals(ContainersWire.UNREADABLE, ((Refused<Envelope>) answer).code());
  }

  // --- unreachable ------------------------------------------------------------------------------------

  @Test
  void nothingAnsweringIsUnreachableForEveryRouteAndNeverARefusal() {
    // Port 1 is reserved and nothing binds it. A connection refused is the cheapest honest
    // "no response arrived" there is.
    ContainersClient nowhere = client("http://127.0.0.1:1", null);

    assertInstanceOf(Unreachable.class, nowhere.ensure("qits-ci", "step", "run-1", ENSURE));
    assertInstanceOf(Unreachable.class, nowhere.status("qits-ci", "step", "run-1"));
    assertInstanceOf(Unreachable.class, nowhere.list("qits-ci"));
    assertInstanceOf(Unreachable.class, nowhere.stop("qits-ci", "step", "run-1"));
    assertInstanceOf(Unreachable.class, nowhere.touch("qits-ci", "step", "run-1"));
    assertInstanceOf(Unreachable.class, nowhere.logs("qits-ci", "step", "run-1", 50));
    assertInstanceOf(Unreachable.class, nowhere.delete("qits-ci", "step", "run-1", true, true));
    assertInstanceOf(
        Unreachable.class,
        nowhere.destroyAll("qits-ci", "step", java.time.Instant.parse("2026-08-11T00:00:00Z")));
    assertInstanceOf(Unreachable.class, nowhere.ensureVolume("qits-ci", "cache"));
    assertInstanceOf(Unreachable.class, nowhere.volume("qits-ci", "cache"));
    assertInstanceOf(Unreachable.class, nowhere.deleteVolume("qits-ci", "cache"));

    ContainersAnswer<Envelope> one = nowhere.status("qits-ci", "step", "run-1");
    assertFalse(one.refused());
    assertFalse(one.succeeded());
    assertNull(one.value());
    assertNotNull(((Unreachable<Envelope>) one).cause());
  }

  @Test
  void aNameThatDoesNotResolveIsUnreachableToo() {
    // The 2026-08-10 failure exactly: an alias that resolves nowhere. Every attempt raises out of
    // send(), and a status code is the only thing that makes an attempt a refusal.
    ContainersClient nowhere =
        client("http://qits-containers-that-does-not-exist.invalid:8080", null);
    assertInstanceOf(Unreachable.class, nowhere.status("qits-ci", "step", "run-1"));
  }

  // --- the shapes the list routes hand back ------------------------------------------------------------

  @Test
  void aListingComesBackAsTheListItselfAndAnEmptyOneIsNotNull() {
    stub.script(200, "{\"containers\":[" + ENVELOPE + "]}");
    ContainersAnswer<List<Envelope>> listed = client.list("qits-ci");
    assertTrue(listed.succeeded());
    assertEquals(1, listed.value().size());

    stub.script(200, "{\"containers\":[]}");
    assertEquals(List.of(), client.list("qits-ci", "step").value());

    // A body with the field absent altogether is an empty list, never a null a caller would iterate.
    stub.script(200, "{}");
    assertEquals(List.of(), client.list("qits-ci").value());
  }

  @Test
  void aDestroyAllComesBackAsItsOutcomes() {
    stub.script(
        200,
        """
        {"destroyed":[{"ref":"run-1","containerName":"qits-ci-step-run-1","removed":true,
                       "detail":null}]}""");

    ContainersAnswer<List<ContainersWire.Destroyed>> answer =
        client.destroyAll("qits-ci", "step", java.time.Instant.parse("2026-08-11T09:00:00Z"));

    assertTrue(answer.succeeded());
    assertEquals("run-1", answer.value().getFirst().ref());
    assertTrue(answer.value().getFirst().removed());
  }

  // --- forward compatibility ---------------------------------------------------------------------------

  @Test
  void aFieldAndAWordThisClientDoesNotKnowDoNotBreakTheResponse() {
    // The service is deployed before its consumers, always. A new envelope field and a new observed
    // state both have to read as "something this client does not know" rather than as a broken body.
    stub.script(
        200,
        """
        {"containerName":"qits-ci-step-run-1","somethingNew":{"deep":1},
         "state":{"desired":"RUNNING","observed":"QUIESCING"},"created":false}""");

    ContainersAnswer<Envelope> answer = client.status("qits-ci", "step", "run-1");

    assertTrue(answer.succeeded(), "an unknown field must not turn a 200 into a refusal");
    assertNull(answer.value().state().observed(), "an unknown word reads as null, not as a throw");
    assertEquals(ContainersWire.Desired.RUNNING, answer.value().state().desired());
  }

  private static ContainersClient client(String url, TokenSource tokens) {
    return new ContainersClient(url, Duration.ofSeconds(5), Duration.ofSeconds(5), tokens);
  }
}
