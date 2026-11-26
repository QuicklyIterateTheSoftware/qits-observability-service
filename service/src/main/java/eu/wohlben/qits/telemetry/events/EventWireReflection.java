package eu.wohlben.qits.telemetry.events;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * What the event bus writes to JSON, told to native-image. No code, no bean, nothing at runtime:
 * the annotation is the entire content, and this class exists so that it has somewhere to live
 * that can say why. The qits-ci {@code bus/EventWireReflection} shape.
 *
 * <p><b>Why nothing registers these on its own.</b> qits-eventstream's {@code CanonicalJson} builds
 * its own {@code ObjectMapper} by hand — the canonical form is a wire contract compared
 * byte-for-byte, so it must not be downstream of this application's customisers — and to Quarkus'
 * reflection scan that mapper and everything it touches are invisible. An event record with no
 * reflection metadata has no components to find, so in the binary its publish fails while the
 * envelope is being built: not a late event, a lost one, with one WARN to say so, while the JVM
 * suite stays green. The library's extension also registers every {@code QitsEvent} implementor
 * in the index; this list keeps this image's records anyway, because it costs nothing and does not
 * depend on the index reaching them.
 *
 * <p><b>Both are publish-only.</b> This service subscribes to nothing, so a type here is serialized
 * on the way out and never read back. A new event type is added here and in {@code
 * EventWireReflectionTest} in the same commit. The library's own wire types ({@code EventEnvelope},
 * {@code EventFrame}, the mix-in) are its extension's to register and are deliberately absent.
 */
@RegisterForReflection(targets = {ErrorIssueOpened.class, ErrorIssueRegressed.class})
public final class EventWireReflection {

  private EventWireReflection() {}
}
