package eu.wohlben.qits.telemetry.sourcemap;

import java.io.IOException;
import java.util.List;

/**
 * Where published source maps are read from (epic qits-2689): the docs store's {@code
 * @sourcemaps/<application>} sites, in production {@link DocsSourceMapSource}. A seam so the cache
 * and the mapper are tested without a network.
 */
public interface SourceMapSource {

  /** The site, version or file does not exist: a fact, cached as such for a while. */
  final class NotFound extends Exception {
    public NotFound(String message) {
      super(message, null, false, false);
    }
  }

  /**
   * The map {@code file + ".map"} of {@code site} at {@code version}.
   *
   * @throws NotFound when the store answers 404
   * @throws SourceMap.Unreadable when the map is over the size cap or does not parse
   * @throws IOException on anything transient: a refused connection, a 5xx, a timeout
   */
  SourceMap fetch(String site, String version, String file)
      throws NotFound, SourceMap.Unreadable, IOException;

  /**
   * The site's published versions, newest first.
   *
   * @throws NotFound when the site does not exist
   * @throws IOException on anything transient
   */
  List<String> versions(String site) throws NotFound, IOException;
}
