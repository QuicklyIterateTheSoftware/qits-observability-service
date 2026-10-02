package eu.wohlben.qits.telemetry.security;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.JwtContext;
import org.jose4j.jwt.consumer.Validator;

/**
 * The bearer's {@code iss} check. The issuer is derived from the domain, {@code
 * https://idp.qits.<QITS_DOMAIN>} with no path, and is never a configuration key (qits-730): so
 * there is no {@code quarkus.oidc.token.issuer}, and with discovery off Quarkus checks no issuer
 * of its own. This bean is the whole check.
 *
 * <p>quarkus-oidc applies every {@link Validator} bean without a {@code @TenantFeature} qualifier to
 * the default tenant's token verification; a non-null answer is a 401. {@code @Unremovable} is
 * load-bearing: the extension looks the beans up programmatically, nothing injects this one, and
 * without it ArC removes the bean as unused and every issuer passes — measured, not assumed.
 */
@Unremovable
@ApplicationScoped
public class IssuerValidator implements Validator {

  /**
   * What qits-platform-idp stamps today. Goes once the idp stamps the derived issuer (qits-730 wave
   * 3).
   */
  static final String LEGACY_ISSUER = "http://qits-platform-idp:8080/idp";

  private final Set<String> accepted;

  IssuerValidator(@ConfigProperty(name = "QITS_DOMAIN") Optional<String> domain) {
    String derived =
        "https://idp.qits." + domain.map(String::strip).filter(d -> !d.isEmpty()).orElse("localhost");
    this.accepted = Set.of(derived, LEGACY_ISSUER);
  }

  @Override
  public String validate(JwtContext context) throws MalformedClaimException {
    String issuer = context.getJwtClaims().getIssuer();
    if (issuer != null && accepted.contains(issuer)) {
      return null;
    }
    return "Issuer (iss) claim value (" + issuer + ") is not one of " + accepted;
  }
}
