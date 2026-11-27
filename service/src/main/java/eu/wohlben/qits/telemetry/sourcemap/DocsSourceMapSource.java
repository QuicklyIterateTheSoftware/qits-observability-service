package eu.wohlben.qits.telemetry.sourcemap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.http.QitsHttpClients;
import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Reads {@code @sourcemaps/<application>} sites off the docs wire (epic qits-2689, qits-2766):
 * {@code GET <base>/<site>/-/<version>/<file>.map} for a map and {@code GET <base>/<site>} for the
 * version list, with this service's own {@code qits:system} bearer — the docs wire behind the edge
 * answers an anonymous read 401 (measured 2026-11-27).
 *
 * <p>Every URL is built from validated parts only: the site from the service name, the version
 * from {@link #VERSION}, the file from {@link BrowserStack#FILE}. Nothing a log carries reaches the
 * URL unchecked.
 *
 * <p><b>Bounded.</b> A map over {@code qits.observability.sourcemaps.max-map-size} (16M) is refused
 * by its {@code Content-Length}, and, should the length be absent or wrong, by a counting stream
 * while parsing — either way it is {@link SourceMap.Unreadable}, never a buffer of unknown size.
 *
 * <p>The client is a bean field built in {@link #open()}, never a static: a live {@link HttpClient}
 * in a static initializer lands in the native image heap, which native-image refuses (see {@code
 * OtelForwarder}).
 */
@ApplicationScoped
public class DocsSourceMapSource implements SourceMapSource {

  /** What a release version may look like before it is put in a URL. */
  static final Pattern VERSION =
      Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

  /** What a site may look like: {@code @sourcemaps/<application>}. */
  static final Pattern SITE =
      Pattern.compile("^@sourcemaps/[a-z0-9][a-z0-9-]{0,63}$");

  private static final Duration MINT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  @Inject QitsHttpClients clients;

  @Inject ObjectMapper json;

  @Inject
  @NamedOidcClient("qits")
  OidcClient qits;

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled", defaultValue = "false")
  boolean bearerEnabled;

  @ConfigProperty(name = SourceMapping.BASE_URL_KEY)
  String baseUrl;

  @ConfigProperty(name = SourceMapping.MAX_MAP_SIZE_KEY, defaultValue = "16M")
  MemorySize maxMapSize;

  private final TokensHelper tokens = new TokensHelper();

  private HttpClient client;

  @PostConstruct
  void open() {
    client =
        clients.wrap(
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
  }

  @Override
  public SourceMap fetch(String site, String version, String file)
      throws NotFound, SourceMap.Unreadable, IOException {
    require(site, version, file);
    HttpResponse<InputStream> response = get(site + "/-/" + version + "/" + file + ".map");
    try (InputStream body = response.body()) {
      if (response.statusCode() == 404) {
        throw new NotFound(site + "@" + version + " has no " + file + ".map");
      }
      if (response.statusCode() != 200) {
        throw new IOException("the docs store answered " + response.statusCode());
      }
      long max = maxMapSize.asLongValue();
      long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
      if (declared > max) {
        throw new SourceMap.Unreadable(file + ".map is " + declared + " bytes, over " + max);
      }
      return SourceMap.parse(new Capped(body, max));
    }
  }

  @Override
  public List<String> versions(String site) throws NotFound, IOException {
    if (!SITE.matcher(site).matches()) {
      throw new NotFound("not a source-map site: " + site);
    }
    HttpResponse<InputStream> response = get(site);
    try (InputStream body = response.body()) {
      if (response.statusCode() == 404) {
        throw new NotFound(site + " is not published");
      }
      if (response.statusCode() != 200) {
        throw new IOException("the docs store answered " + response.statusCode());
      }
      JsonNode root = json.readTree(new Capped(body, 4L * 1024 * 1024));
      record Published(String version, String at) {}
      List<Published> published = new ArrayList<>();
      for (JsonNode v : root.path("versions")) {
        String name = v.path("version").asText("");
        if (VERSION.matcher(name).matches()) {
          published.add(new Published(name, v.path("publishedAt").asText("")));
        }
      }
      // Newest first by publish time; the ISO-8601 instants the store writes sort as text.
      published.sort(Comparator.comparing(Published::at).reversed());
      return published.stream().map(Published::version).toList();
    }
  }

  private HttpResponse<InputStream> get(String path) throws IOException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/" + path))
            .timeout(REQUEST_TIMEOUT)
            .GET();
    bearer().ifPresent(token -> request.header("Authorization", "Bearer " + token));
    try {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }

  private Optional<String> bearer() throws IOException {
    if (!bearerEnabled) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              tokens.getTokens(qits).await().atMost(MINT_TIMEOUT).getAccessToken())
          .filter(token -> !token.isBlank());
    } catch (RuntimeException e) {
      throw new IOException("no service bearer: " + e.getMessage(), e);
    }
  }

  private static void require(String site, String version, String file) throws NotFound {
    if (!SITE.matcher(site).matches()
        || !VERSION.matcher(version).matches()
        || !BrowserStack.FILE.matcher(file).matches()) {
      throw new NotFound("not a source-map coordinate: " + site + " " + version + " " + file);
    }
  }

  /** Throws once more than {@code max} bytes have been read. */
  static final class Capped extends FilterInputStream {
    private final long max;
    private long read;

    Capped(InputStream in, long max) {
      super(in);
      this.max = max;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) {
        count(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = super.read(buffer, offset, length);
      if (n > 0) {
        count(n);
      }
      return n;
    }

    private void count(long n) throws IOException {
      read += n;
      if (read > max) {
        throw new IOException("over the " + max + "-byte cap");
      }
    }
  }
}
