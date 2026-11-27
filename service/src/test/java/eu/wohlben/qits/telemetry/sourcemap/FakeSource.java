package eu.wohlben.qits.telemetry.sourcemap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** A docs store in memory: maps by (site, version, file), version lists by site, and failures. */
final class FakeSource implements SourceMapSource {

  final Map<String, String> maps = new HashMap<>();
  final Map<String, List<String>> versions = new HashMap<>();
  final List<String> fetched = new ArrayList<>();
  volatile boolean failing;
  volatile CountDownLatch hold;

  FakeSource put(String site, String version, String file, String json) {
    maps.put(site + "|" + version + "|" + file, json);
    return this;
  }

  @Override
  public synchronized SourceMap fetch(String site, String version, String file)
      throws NotFound, SourceMap.Unreadable, IOException {
    fetched.add(site + "|" + version + "|" + file);
    await();
    if (failing) {
      throw new IOException("the docs store is down");
    }
    String json = maps.get(site + "|" + version + "|" + file);
    if (json == null) {
      throw new NotFound(file);
    }
    return TestMaps.parse(json);
  }

  @Override
  public synchronized List<String> versions(String site) throws NotFound, IOException {
    fetched.add("versions|" + site);
    if (failing) {
      throw new IOException("the docs store is down");
    }
    List<String> list = versions.get(site);
    if (list == null) {
      throw new NotFound(site);
    }
    return list;
  }

  private void await() {
    if (hold != null) {
      try {
        hold.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
