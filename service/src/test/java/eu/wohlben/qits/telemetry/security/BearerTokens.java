package eu.wohlben.qits.telemetry.security;

import io.smallrye.jwt.build.Jwt;
import io.smallrye.jwt.util.KeyUtils;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.Set;

/**
 * The test issuer. It signs RS256 tokens shaped like the ones qits-platform-idp gives a person's
 * command-line tool (idp client {@code qits-cli}): {@code iss} the idp, {@code aud} the one
 * platform-wide audience {@code qits-platform}, {@code sub} the user id, {@code groups} the person's
 * roles, {@code credential_type} {@code cli}, and a {@code kid} in the header.
 *
 * <p>The real extension checks every token made here: {@link BearerAuthProfile} gives it the public
 * half of the key pair in {@code src/test/resources}, and {@link JwksStub} serves that half as a
 * JWKS. The key pair exists for this suite only and protects nothing.
 */
final class BearerTokens {

  static final String SIGNING_KEY = "/bearer-token-signing-key.pem";
  static final String VERIFICATION_KEY = "/bearer-token-verification-key.pem";

  /** The key id in every token's header. {@link JwksStub} serves the key under it. */
  static final String KEY_ID = "observability-suite-key";

  /** What qits-platform-idp writes into {@code iss}, and what the shipped config expects. */
  static final String ISSUER = "http://qits-platform-idp:8080/idp";

  /** The platform-wide audience a person's CLI token carries. */
  static final String PLATFORM_AUDIENCE = "qits-platform";

  /** A user id, as the idp writes it into {@code sub}. */
  static final String SUBJECT = "0b6f4d2e-7a31-4c1e-9d55-3f2a8e6c9b10";

  /** A person's CLI token with {@code roles} in {@code groups}. */
  static String cliToken(String... roles) {
    return token(ISSUER, PLATFORM_AUDIENCE, privateKey(), roles);
  }

  /** The same token, addressed to {@code audience} instead. */
  static String tokenFor(String audience, String... roles) {
    return token(ISSUER, audience, privateKey(), roles);
  }

  /**
   * A peer service's machine token: the same issuer and the same {@code qits-platform} audience — the
   * idp stamps it on everything it mints — with a client id in {@code sub} and no {@code cli}
   * credential type.
   */
  static String machineToken(String... roles) {
    return Jwt.claims()
        .issuer(ISSUER)
        .subject("qits-canary")
        .audience(Set.of(PLATFORM_AUDIENCE))
        .groups(Set.of(roles))
        .expiresIn(Duration.ofMinutes(5))
        .jws()
        .keyId(KEY_ID)
        .sign(privateKey());
  }

  /** The same token, from {@code issuer} instead. Correctly signed. */
  static String tokenFrom(String issuer, String... roles) {
    return token(issuer, PLATFORM_AUDIENCE, privateKey(), roles);
  }

  /** A well-formed CLI token, same {@code kid}, signed by a key this service does not trust. */
  static String foreignToken(String... roles) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return token(ISSUER, PLATFORM_AUDIENCE, generator.generateKeyPair().getPrivate(), roles);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot make a foreign key", e);
    }
  }

  /** The value of an {@code Authorization} header that carries {@code token}. */
  static String bearer(String token) {
    return "Bearer " + token;
  }

  /** The public key without its PEM armour: quarkus.oidc.public-key takes the key itself. */
  static String verificationKey() {
    return pem(VERIFICATION_KEY)
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replaceAll("\\s", "");
  }

  private static String token(String issuer, String audience, PrivateKey key, String... roles) {
    return Jwt.claims()
        .issuer(issuer)
        .subject(SUBJECT)
        .audience(Set.of(audience))
        .groups(Set.of(roles))
        .claim("credential_type", "cli")
        .expiresIn(Duration.ofMinutes(5))
        .jws()
        .keyId(KEY_ID)
        .sign(key);
  }

  private static String pem(String resource) {
    try (var in = BearerTokens.class.getResourceAsStream(resource)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Missing test key " + resource, e);
    }
  }

  private static PrivateKey privateKey() {
    try {
      return KeyUtils.decodePrivateKey(pem(SIGNING_KEY));
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read the test signing key", e);
    }
  }

  private BearerTokens() {}
}
