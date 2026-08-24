package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The surface of the <em>packaged artifact</em> — the fast-jar under {@code -DskipITs=false}, the
 * GraalVM binary under {@code -Dnative} — because that is the only place a whole class of failure
 * is visible.
 *
 * <p>Every other test here is a {@code @QuarkusTest}: it runs in the build JVM with <b>Quinoa
 * disabled</b> — the extension is off by default in test mode, so no {@code @QuarkusTest} in this
 * repo has ever seen the client at all. What the SPA is actually served as is proven here or
 * nowhere. {@link OtelReceiverIT} covers the ingest half of the artifact; this covers the serving
 * half, matching the qits-ci / qits-cd / qits-projects / qits-events precedent.
 *
 * <p><b>The client is served at the root</b> since this service got a host of its own
 * ({@code observability.<env>.<domain>}). The segment survives only as the wire prefix, which is
 * what the probe list below turns on:
 *
 * <ul>
 *   <li>{@code /} → 200 HTML carrying {@code <base href="/">} — the client's own spelling, set in
 *       another repository's {@code angular.json}, where no build here can check it. Wrong, and the
 *       page loads and then fetches its JavaScript from nowhere.
 *   <li>a deep link, scoped and unscoped → 200 {@code index.html}, so the Angular router owns it
 *       across a reload
 *   <li>{@code /observability/} → 404: the whole segment is ignored by SPA routing now, so the old
 *       address is not a second door into the client. The edge sends the bookmark on with a
 *       redirect.
 *   <li>{@code /observability/api/<real>} → the API's own answer; {@code /observability/api/nope}
 *       → 404 and <b>never</b> the client. A machine client parses {@code index.html} as data, so
 *       the absence of the client is as much of the assertion as the status.
 *   <li>the readiness endpoint the deployer's health gate curls, at the address the deployment
 *       assumes
 *   <li>{@code /observability/mcp}: covered by the one ignored prefix, and a mistyped path under it
 *       must reach the machine surface rather than the SPA fallback.
 * </ul>
 *
 * <p>ITs are skipped by default ({@code skipITs} in the root pom) because they need a `package`,
 * and a package here needs the webui submodule and a node on PATH — neither of which the
 * clone-alone rule promises. Ask for them explicitly.
 */
@QuarkusIntegrationTest
class PackagedSurfaceIT {

  private static final String CLIENT_MARK = "<base href=\"/\">";

  @Test
  void theClientIsServedAtTheRootWithItsOwnBaseHref() {
    String html =
        given().when().get("/").then().statusCode(200).contentType(ContentType.HTML).extract()
            .asString();
    assertTrue(
        html.contains(CLIENT_MARK),
        "the client's baseHref must be the root it is mounted at; got: "
            + html.substring(0, Math.min(400, html.length())));
  }

  @Test
  void aDeepLinkFallsBackToTheClientSoItsRouterOwnsIt() {
    // /traces/abc is a route only the Angular router knows; across a reload only enable-spa-routing
    // keeps it alive. /qits/traces is the project-scoped form of the same page, which the server
    // knows nothing about and has to answer the same way.
    String deepLink =
        given().when().get("/traces/abc").then().statusCode(200).contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(
        deepLink.contains(CLIENT_MARK),
        "a deep link must answer with index.html, not with a differently-shaped page");

    String scoped =
        given().when().get("/qits/traces").then().statusCode(200).contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(scoped.contains(CLIENT_MARK), "a project-scoped deep link must answer with index.html");
  }

  @Test
  void theOldSegmentIsNoLongerADoorIntoTheClient() {
    // The whole /observability prefix is in quarkus.quinoa.ignored-path-prefixes, so nothing under
    // it is rerouted to index.html. An old bookmark is the edge's problem, answered there with a
    // redirect — here it is an honest 404.
    given().when().get("/observability").then().statusCode(404);
    String body = given().when().get("/observability/").then().statusCode(404).extract().asString();
    assertFalse(body.contains(CLIENT_MARK), "the old segment must not serve the client; got: " + body);
  }

  @Test
  void realRoutesAnswerAndAMistypedOneIsNeverTheClient() {
    given()
        .when()
        .get("/observability/api/telemetry/store")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON);

    // The whole reason quarkus.quinoa.ignored-path-prefixes carries /observability: without it this
    // answers 200 with index.html, and a machine client parses the client's not-found page as data.
    //
    // The assertion is "404, and not the CLIENT" rather than "404, never HTML", because what
    // actually comes back is Vert.x' own stock <h1>Resource not found</h1> — text/html, and
    // correct. The content type alone cannot tell the two apart (index.html is text/html too), so
    // the status and the absence of the client are what is pinned.
    String body =
        given().when().get("/observability/api/nope").then().statusCode(404).extract().asString();
    assertFalse(
        body.contains(CLIENT_MARK),
        "a mistyped machine path must not be answered with the client; got: " + body);

    // The edge path-routes verbatim by prefix, so there is no unprefixed form to fall back to — and
    // at the root an unprefixed /api/telemetry/store is the CLIENT's ground, which is why the check
    // is that it never answers as the API.
    given()
        .when()
        .get("/api/telemetry/store")
        .then()
        .statusCode(200)
        .contentType(ContentType.HTML);
  }

  @Test
  void aMistypedMcpPathReachesTheMachineSurfaceAndNeverTheClient() {
    // The MCP server mounts outside quarkus.rest.path, so Quinoa's own derivation would never cover
    // it — if the one hand-spelled prefix ever regressed to that derivation, this path would fall
    // through to the SPA and answer 200 index.html. The MCP server's own answer for a wrong
    // sub-path may be any 4xx; the pinned fact is only that the client is not it.
    var response = given().when().get("/observability/mcp/nope").then().extract();
    assertFalse(
        response.asString().contains(CLIENT_MARK),
        "a mistyped MCP path must not be answered with the client");
    assertTrue(
        response.statusCode() >= 400,
        "a mistyped MCP path must be an error, not a page; got: " + response.statusCode());
  }

  @Test
  void theReadinessEndpointIsWhereTheDeploymentLooksForIt() {
    given()
        .when()
        .get("/observability/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", org.hamcrest.Matchers.equalTo("UP"));
  }

  @Test
  void theApiDocumentAndItsUiAreServedUnderTheSegment() {
    // Both live under quarkus.http.non-application-root-path, which sits OUTSIDE quarkus.rest.path
    // and carries /observability on its own; at / they would be the client's ground now.
    given().when().get("/observability/q/openapi").then().statusCode(200);
    given().when().get("/observability/q/swagger-ui/").then().statusCode(200);
  }
}
