package eu.wohlben.qits.telemetry.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import io.quarkus.oidc.OidcConfigurationMetadata;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.junit.jupiter.api.Test;

/**
 * This service's pact with qits-idp-service (ticket qits-1149). The only calls it makes to the idp
 * are quarkus-oidc's: the discovery document, then the JWKS. The tenant is lazy ({@code
 * jwks.resolve-early=false}), so the trigger is the first authenticated request.
 *
 * <p>Each row's call reads the answer the way quarkus-oidc does: the discovery document through
 * {@link OidcConfigurationMetadata}, the keys as a jose4j {@link JsonWebKeySet}.
 *
 * <p>{@code issuer} is bound EXACTLY, not by type: quarkus-oidc refuses every token whose {@code
 * iss} differs from it by one character. qits-pact-consumer matches every leaf by type, so the
 * committed file drops that one rule (see {@link #theCommittedPactIsWhatTheRowsWrite}).
 */
class IdpConsumerPactTest {

  static final String CONSUMER = "qits-observability-service";

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final Trigger FIRST_BEARER = Trigger.operation("the first authenticated request");

  static final String STATE = "a published signing key";

  static final GoldenInteraction DISCOVERY =
      GoldenInteraction.of(FIRST_BEARER, STATE, "getOpenIdConfiguration")
          .consumes("issuer", "jwks_uri", "token_endpoint");

  static final GoldenInteraction JWKS =
      GoldenInteraction.of(FIRST_BEARER, STATE, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  static final ConsumerPact PACT = ConsumerPact.of(CONSUMER, IDP, DISCOVERY, JWKS);

  /** The pact path quarkus-oidc compares exactly. */
  static final String EXACT_ISSUER = "$.issuer";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Test
  void discoveryNamesTheIssuerAndTheKeyAddress() {
    PACT.run(
        DISCOVERY,
        (url, recorded) -> {
          OidcConfigurationMetadata metadata =
              new OidcConfigurationMetadata(
                  new JsonObject(get(url + "/idp/.well-known/openid-configuration")));
          String recordedIssuer = IDP.json(STATE, "getOpenIdConfiguration").path("issuer").asText();
          assertEquals(recordedIssuer, metadata.getIssuer());
          assertNotNull(metadata.getJsonWebKeySetUri());
          assertNotNull(metadata.getTokenUri());
        });
  }

  @Test
  void theKeysParseAsRsaSigningKeys() {
    PACT.run(
        JWKS,
        (url, recorded) -> {
          JsonWebKeySet set = new JsonWebKeySet(get(url + "/idp/jwks"));
          assertFalse(set.getJsonWebKeys().isEmpty(), "at least one key");
          for (JsonWebKey key : set.getJsonWebKeys()) {
            assertNotNull(key.getKeyId());
            assertEquals("sig", key.getUse());
            assertNotNull(key.getAlgorithm());
            assertTrue(key instanceof RsaJsonWebKey, "an RSA key");
            assertNotNull(((RsaJsonWebKey) key).getRsaPublicKey());
            assertTrue(key.getKey() instanceof RSAPublicKey);
          }
        });
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PACT.assertEveryInteractionCarriesBothReferences();
  }

  /**
   * {@code pacts/qits-observability-service_qits-idp-service.json}: the rows' pact with the type
   * rule on {@code issuer} removed, so the verifier compares it by value.
   */
  @Test
  void theCommittedPactIsWhatTheRowsWrite() throws Exception {
    JsonNode pact = PACT.normalisedPact();
    int exact = 0;
    for (JsonNode interaction : pact.path("interactions")) {
      if (interaction.path("description").asText().equals(DISCOVERY.description())
          && interaction.path("response").path("matchingRules").path("body")
              instanceof ObjectNode body
          && body.remove(EXACT_ISSUER) != null) {
        exact++;
      }
    }
    assertEquals(1, exact, "the discovery row binds " + EXACT_ISSUER + " by value");
    eu.wohlben.qits.pact.consumer.GoldenFiles.compareOrWrite(
        ConsumerPact.pactsDirectory().resolve(PACT.file()),
        ConsumerPact.normalise(MAPPER.writeValueAsString(pact)));
  }

  private static String get(String url) throws Exception {
    HttpResponse<String> response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), url);
    return response.body();
  }
}
