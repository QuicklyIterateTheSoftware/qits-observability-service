package eu.wohlben.qits.telemetry.security;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * The deployed posture for tokens, with a local key in place of a live idp.
 *
 * <ul>
 *   <li>The OIDC tenant goes back on. The shipped file turns it off under {@code %test}.
 *   <li>The {@code %test} dev user is blanked, as in {@link NoDevUserProfile}, so a request with no
 *       credential is really anonymous.
 *   <li>{@code quarkus.oidc.public-key} replaces the key fetch. {@code auth-server-url} is cleared
 *       beside it, because a tenant that still has a server URL tries to reach it on the first
 *       bearer.
 * </ul>
 *
 * <p>Everything else, the issuer and the audience included, is the shipped configuration.
 * {@link BearerJwksTest} covers the shipped key fetch itself.
 */
public class BearerAuthProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of(
        "quarkus.oidc.tenant-enabled", "true",
        "qits.auth.forward.dev-user", "",
        "quarkus.oidc.auth-server-url", "",
        "quarkus.oidc.public-key", BearerTokens.verificationKey());
  }
}
