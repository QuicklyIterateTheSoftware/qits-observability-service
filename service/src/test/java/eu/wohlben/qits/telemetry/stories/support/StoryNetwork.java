package eu.wohlben.qits.telemetry.stories.support;

import eu.wohlben.qits.userflows.NetworkTaps;

/**
 * <b>Both ends of every diagram in this catalogue, wired in one call</b> — so a story class's
 * {@code @BeforeAll} is one line and no class can wire half of it.
 *
 * <p>There are two feeds, and they are two different mechanisms:
 *
 * <ul>
 *   <li><b>the near side</b>, {@link NetworkTaps#restAssured}: every request a story makes becomes
 *       {@code <actor> -> qits-observability}, labelled {@code METHOD <scrubbed path> -> <status>}
 *       with the status this service really answered. The framework ships it; this repository's
 *       hand-copied {@code api/StoryNetworkFilter} was deleted when these stories were written. It
 *       is idempotent per service, which is why every class may call this method. Its default skip —
 *       any path carrying a {@code /q/} segment — was checked against this service's own {@code
 *       quarkus.http.non-application-root-path}; see {@link StoryTarget}.
 *   <li><b>the far side</b>, {@link StoryParent#install()}: the parent collector's access log,
 *       cumulative and with <b>no floor</b>, which is what lets the first story's edge count say that
 *       the launched process posted the parent nothing at boot.
 * </ul>
 *
 * <p>There is no third feed and there could not be: with its own SDK dark (see {@link StoryProfile})
 * this process dials exactly one thing, and only when something was exported into it. That is the
 * whole architecture — "a managed qits is watched from both tiers" — and a diagram set in which every
 * story's only far side is its own supervisor is that sentence, drawn.
 *
 * <h2>Order is load-bearing, and it is the class names that carry it</h2>
 *
 * <p>A cumulative source is attributed by a cursor, so anything recorded before the first drain lands
 * in whichever story drains FIRST. {@code UserflowClassOrderer} sorts by fully-qualified class name,
 * so {@code …telemetry.TelemetryBootstrapIT} runs before every {@code …telemetry.stories.*} class and
 * owns the boot. {@code @UserflowRunsAfter} states that as a dependency on the classes whose edge
 * counts assume it, rather than leaving it as a coincidence of spelling.
 *
 * <p>Beyond that the stories are order-independent by construction: each exports into a bucket no
 * other story reads, and each awaits <i>one more</i> forward than it found on entry rather than an
 * absolute count.
 */
public final class StoryNetwork {

  private StoryNetwork() {}

  /**
   * Install the near-side tap and register the far-side recording. Idempotent, and safe from any
   * story class's {@code @BeforeAll} — {@link eu.wohlben.qits.userflows.NetworkCapture#source}
   * replaces a supplier while keeping its cursor, so a class that runs second does not re-attribute
   * what the first drained.
   */
  public static void install() {
    NetworkTaps.restAssured(StoryTarget.SERVICE);
    StoryParent.install();
  }
}
