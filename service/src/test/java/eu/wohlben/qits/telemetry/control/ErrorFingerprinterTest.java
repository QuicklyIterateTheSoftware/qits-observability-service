package eu.wohlben.qits.telemetry.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.control.ErrorFingerprinter.ErrorFingerprint;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.sourcemap.BrowserStackMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit, no Quarkus: the three worked examples of the dossier page "Fingerprint and ingest"
 * (epic qits-2538), one case per normalising rule, and which logs feed an issue at all.
 */
class ErrorFingerprinterTest {

  private final ErrorFingerprinter fingerprinter = new ErrorFingerprinter();

  /** The qits-maintenance stack, verbatim from the page. */
  private static final String MAINTENANCE_STACK =
      "java.lang.IllegalStateException: EntityManagerFactory is closed\n"
          + "\tat org.hibernate.internal.SessionFactoryImpl.validateNotClosed(SessionFactoryImpl.java:591)\n"
          + "\tat org.hibernate.Session_5ppABaPEJjdJW8N_IsP06muQ8qg_Synthetic_ClientProxy.createSelectionQuery(Unknown Source)\n"
          + "\tat io.quarkus.hibernate.orm.panache.runtime.PanacheQueryImpl.firstResultOptional(PanacheQueryImpl.java:167)\n"
          + "\tat eu.wohlben.qits.maintenance.persistence.MaintenanceStore.lambda$newestOpenedRequest$0(MaintenanceStore.java:3774)\n"
          + "\tat eu.wohlben.qits.db.DbRetry.lambda$oneTransaction$0(DbRetry.java:286)\n";

  // --- the worked examples ---------------------------------------------------------------------

  @Test
  void maintenanceExampleIsKeyedOnTheFirstInAppFrame() {
    StoredLog log =
        error(
            "qits-maintenance",
            "EntityManagerFactory is closed",
            Map.of(
                "exception.type", "java.lang.IllegalStateException",
                "exception.message", "EntityManagerFactory is closed",
                "exception.stacktrace", MAINTENANCE_STACK,
                "code.function.name", "eu.wohlben.qits.maintenance.Something.else",
                "code.line.number", "120"));

    ErrorFingerprint print = fingerprinter.fingerprint(log);

    assertEquals("java.lang.IllegalStateException", print.type());
    assertEquals(
        "eu.wohlben.qits.maintenance.persistence.MaintenanceStore.newestOpenedRequest",
        print.location());
    assertEquals("", print.message(), "the message is unused when type and location exist");
    // SHA-256("qits-maintenance\njava.lang.IllegalStateException\n<location>\n"), first 16 bytes.
    assertEquals("f6b59211f7afcfc5bf4c59892a1a5605", print.fingerprint());
  }

  @Test
  void edgeExampleFallsBackToTheBridgeNameAndTheMessage() {
    StoredLog log =
        error(
            "qits-edge",
            "Connection was closed",
            Map.of(
                "code.function.name", "null.null",
                "code.line.number", "-1",
                "bridge.name", "io.vertx.core.http.impl.HttpClientRequestImpl"));

    ErrorFingerprint print = fingerprinter.fingerprint(log);

    assertEquals("", print.type());
    assertEquals("io.vertx.core.http.impl.HttpClientRequestImpl", print.location());
    assertEquals("Connection was closed", print.message());
    assertEquals("4fc94e9609571754b919ac1c5182cc4e", print.fingerprint());
  }

  @Test
  void landingBrowserErrorWithAMinifiedStackHasNoLocation() {
    String stack =
        "TypeError: Cannot read properties of undefined (reading 'id')\n"
            + "    at e.ngOnInit (https://qits.wohlben.eu/projects/main-7QX2LJ4K.js:1:23456)\n"
            + "    at Array.forEach (<anonymous>)\n"
            + "    at https://qits.wohlben.eu/projects/chunk-ABCD1234.js:2:345\n";
    StoredLog log =
        error(
            "qits-landing",
            "Cannot read properties of undefined (reading 'id') in 3 places",
            Map.of("exception.type", "TypeError", "exception.stacktrace", stack));

    ErrorFingerprint print = fingerprinter.fingerprint(log);

    assertEquals("TypeError", print.type());
    assertEquals("", print.location());
    assertEquals("Cannot read properties of undefined (reading <s>) in <n> places", print.message());
  }

  // --- mapped browser locations (qits-2768) ---------------------------------------------------

  /** The minified stack of the live qits-2766 check (qits-landing 2026.1127.191530's main chunk). */
  private static final String LIVE_BROWSER_STACK =
      "SyntaxError: qits-2766 live check: synthetic browser error, safe to dismiss\n"
          + "    at JSON.parse (<anonymous>)\n"
          + "    at m (https://qits.wohlben.eu/main-6V4LQOBD.js:21:7747)\n"
          + "    at v$1 (https://qits.wohlben.eu/main-6V4LQOBD.js:21:7927)";

  private static StoredLog liveBrowserError(Map<String, String> extra) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("exception.type", "SyntaxError");
    attributes.put(
        "exception.message", "qits-2766 live check: synthetic browser error, safe to dismiss");
    attributes.put("exception.stacktrace", LIVE_BROWSER_STACK);
    attributes.putAll(extra);
    return error(
        "qits-landing-browser",
        "qits-2766 live check: synthetic browser error, safe to dismiss",
        attributes);
  }

  @Test
  void aMappedBrowserErrorIsKeyedOnItsSourceFileAndFunction() {
    ErrorFingerprint print =
        fingerprinter.fingerprint(
            liveBrowserError(
                Map.of(
                    BrowserStackMapper.MAPPED_LOCATION,
                    "src/app/core/events/refresh-on.ts#payloadOf")));

    assertEquals("SyntaxError", print.type());
    assertEquals("src/app/core/events/refresh-on.ts#payloadOf", print.location());
    assertEquals("", print.message(), "the message is unused when type and location exist");
    // SHA-256("qits-landing-browser\nSyntaxError\nsrc/app/core/events/refresh-on.ts#payloadOf\n").
    assertEquals("0475605b4e52a343cf51ca9259e16ffd", print.fingerprint());
  }

  /**
   * Measured live on 2026-11-27: this log, at a version with maps (mapped stack, no location yet)
   * and at one without, was stamped {@code e9217bc2547e222761a71fb6f9a58a12} both times. An unmapped
   * browser error keeps that fingerprint, byte for byte.
   */
  @Test
  void anUnmappedBrowserErrorKeepsTodaysFingerprint() {
    ErrorFingerprint print = fingerprinter.fingerprint(liveBrowserError(Map.of()));

    assertEquals("", print.location());
    assertEquals("e9217bc2547e222761a71fb6f9a58a12", print.fingerprint());
  }

  @Test
  void theSameMappedErrorInALaterReleaseKeepsItsFingerprint() {
    String later =
        "TypeError: x is undefined\n"
            + "    at Qz (https://qits.wohlben.eu/main-NEWER123.js:3:120)\n"
            + "    at Rk (https://qits.wohlben.eu/chunk-Zz9_aB.js:1:44)";
    StoredLog first =
        error(
            "qits-landing-browser",
            "x is undefined",
            Map.of(
                "exception.type", "TypeError",
                "exception.stacktrace", "TypeError: x is undefined\n    at m (https://q/main-A.js:21:7747)",
                BrowserStackMapper.MAPPED_LOCATION, "src/app/a.ts#load"));
    StoredLog next =
        error(
            "qits-landing-browser",
            "x is undefined (2nd)",
            Map.of(
                "exception.type", "TypeError",
                "exception.stacktrace", later,
                BrowserStackMapper.MAPPED_LOCATION, "src/app/a.ts#load"));

    assertEquals(
        fingerprinter.fingerprint(first).fingerprint(),
        fingerprinter.fingerprint(next).fingerprint());
  }

  @Test
  void aMappedLocationWinsOverAJavaFrameAndABlankOneIsIgnored() {
    assertEquals(
        "src/app/a.ts",
        location(
            Map.of(
                "exception.stacktrace", MAINTENANCE_STACK,
                BrowserStackMapper.MAPPED_LOCATION, "src/app/a.ts")));
    assertEquals(
        "eu.wohlben.qits.maintenance.persistence.MaintenanceStore.newestOpenedRequest",
        location(
            Map.of(
                "exception.stacktrace", MAINTENANCE_STACK,
                BrowserStackMapper.MAPPED_LOCATION, " ")));
  }

  // --- location fallbacks ----------------------------------------------------------------------

  @Test
  void withNoInAppFrameTheFirstFrameIsTheLocation() {
    String stack =
        "java.lang.IllegalStateException: closed\n"
            + "\tat org.hibernate.internal.SessionFactoryImpl.validateNotClosed(Impl.java:591)\n"
            + "\tat io.quarkus.Other.run(Other.java:1)\n";
    assertEquals(
        "org.hibernate.internal.SessionFactoryImpl.validateNotClosed",
        location(Map.of("exception.stacktrace", stack)));
  }

  @Test
  void theInAppPrefixesAreConfigurable() {
    ErrorFingerprinter custom = new ErrorFingerprinter(List.of(" io.quarkus. ", "com.example."));
    assertEquals(
        "io.quarkus.hibernate.orm.panache.runtime.PanacheQueryImpl.firstResultOptional",
        custom.location(Map.of("exception.stacktrace", MAINTENANCE_STACK)));
  }

  @Test
  void withoutAStackTheFunctionNameThenNamespaceThenBridgeName() {
    assertEquals(
        "eu.wohlben.qits.ci.Runner.lease",
        location(
            Map.of(
                "code.function.name", "eu.wohlben.qits.ci.Runner.lease",
                "code.namespace", "eu.wohlben.qits.ci.Runner",
                "bridge.name", "bridge")));
    assertEquals(
        "eu.wohlben.qits.system.Runner",
        location(
            Map.of(
                "code.function.name", "null.null",
                "code.namespace", "eu.wohlben.qits.system.Runner",
                "bridge.name", "bridge")));
    assertEquals("bridge", location(Map.of("code.function.name", " ", "bridge.name", "bridge")));
    assertEquals("", location(Map.of()));
  }

  // --- frame normalising -----------------------------------------------------------------------

  @Test
  void aLambdaNamesItsEnclosingMethod() {
    assertEquals("a.B.run", ErrorFingerprinter.normaliseFrame("a.B", "lambda$run$12"));
  }

  @Test
  void anAnonymousClassNamesItsOuterClass() {
    assertEquals("a.B.call", ErrorFingerprinter.normaliseFrame("a.B$1", "call"));
    assertEquals("a.B$Inner.call", ErrorFingerprinter.normaliseFrame("a.B$Inner$2", "call"));
  }

  @Test
  void cdiSubclassesAndClientProxiesNameTheirBean() {
    assertEquals("a.Store.find", ErrorFingerprinter.normaliseFrame("a.Store_Subclass", "find"));
    assertEquals("a.Store.find", ErrorFingerprinter.normaliseFrame("a.Store_ClientProxy", "find"));
  }

  @Test
  void generatedDoubleDollarClassesNameTheirOrigin() {
    assertEquals(
        "a.Store.find", ErrorFingerprinter.normaliseFrame("a.Store$$QuarkusImpl$1", "find"));
  }

  @Test
  void aHashSuffixedSyntheticClassNamesItsStem() {
    assertEquals(
        "org.hibernate.Session.createSelectionQuery",
        ErrorFingerprinter.normaliseFrame(
            "org.hibernate.Session_5ppABaPEJjdJW8N_IsP06muQ8qg_Synthetic_ClientProxy",
            "createSelectionQuery"));
    // A short underscore suffix is part of the name, not a hash.
    assertEquals("a.My_Store.find", ErrorFingerprinter.normaliseFrame("a.My_Store", "find"));
  }

  // --- message normalising ---------------------------------------------------------------------

  @Test
  void uuidsBecomeAPlaceholder() {
    assertEquals(
        "workspace <uuid> gone",
        ErrorFingerprinter.normaliseMessage("workspace 3f2b8c1e-9d4a-4e7b-8c2d-1a2b3c4d5e6f gone"));
  }

  @Test
  void longHexRunsBecomeAPlaceholder() {
    assertEquals(
        "commit <hex> missing, short abc<n>",
        ErrorFingerprinter.normaliseMessage("commit 0eaaca7f9b2c4d1e missing, short abc1234"));
  }

  @Test
  void quotedStringsBecomeAPlaceholder() {
    assertEquals(
        "no repo <s> in <s>", ErrorFingerprinter.normaliseMessage("no repo 'qits-ci' in \"x y\""));
  }

  @Test
  void remainingDigitRunsBecomeAPlaceholder() {
    assertEquals(
        "took <n> ms, attempt <n>", ErrorFingerprinter.normaliseMessage("took 1534 ms, attempt 3"));
  }

  @Test
  void whitespaceCollapsesAndTheTextIsTrimmedAndCut() {
    assertEquals("a b c", ErrorFingerprinter.normaliseMessage("  a \n\t b   c  "));
    String cut = ErrorFingerprinter.normaliseMessage("x".repeat(500));
    assertEquals(ErrorFingerprinter.MAX_MESSAGE_LENGTH, cut.length());
  }

  @Test
  void theMessageIsUsedWhenEitherTypeOrLocationIsMissing() {
    StoredLog first = error("svc", "failed for order 1", Map.of("exception.type", "X"));
    StoredLog second = error("svc", "failed for order 2", Map.of("exception.type", "X"));
    StoredLog other = error("svc", "something else", Map.of("exception.type", "X"));
    assertEquals(
        fingerprinter.fingerprint(first).fingerprint(),
        fingerprinter.fingerprint(second).fingerprint());
    assertNotEquals(
        fingerprinter.fingerprint(first).fingerprint(),
        fingerprinter.fingerprint(other).fingerprint());
  }

  @Test
  void theLineNumberAndTheVersionAreNotInputs() {
    StoredLog log =
        error(
            "qits-maintenance",
            "EntityManagerFactory is closed",
            Map.of(
                "exception.type", "java.lang.IllegalStateException",
                "exception.stacktrace", MAINTENANCE_STACK));
    StoredLog moved =
        new StoredLog(
            1L,
            17,
            "ERROR",
            "EntityManagerFactory is closed",
            "",
            "",
            "qits-maintenance",
            Map.of(
                "exception.type",
                "java.lang.IllegalStateException",
                "exception.stacktrace",
                MAINTENANCE_STACK.replace("3774", "3801")),
            Map.of("service.version", "2027.101.10101"),
            1L);
    assertEquals(
        fingerprinter.fingerprint(log).fingerprint(),
        fingerprinter.fingerprint(moved).fingerprint());
  }

  // --- which logs feed, and the stamp ----------------------------------------------------------

  @Test
  void onlyServiceErrorsOutsideWorkspacesFeed() {
    assertTrue(fingerprinter.feeds(error("svc", "boom", Map.of())));
    assertFalse(fingerprinter.feeds(log(13, "svc", Map.of())), "WARN does not feed");
    assertFalse(fingerprinter.feeds(error(" ", "boom", Map.of())), "a blank service does not feed");
    assertFalse(
        fingerprinter.feeds(
            new StoredLog(
                1L,
                17,
                "ERROR",
                "boom",
                "",
                "",
                "svc",
                Map.of(),
                Map.of("qits.workspace.id", "ws-1"),
                1L)),
        "workspace telemetry does not feed");
  }

  @Test
  void stampCopiesFedLogsWithTheAttributeAndLeavesTheRestAlone() {
    StoredLog fed = error("svc", "boom", Map.of("exception.type", "X"));
    StoredLog info = log(9, "svc", Map.of("k", "v"));

    List<StoredLog> stamped = fingerprinter.stamp(List.of(fed, info));

    assertEquals(2, stamped.size());
    String print = stamped.get(0).attributes().get(ErrorFingerprinter.FINGERPRINT_ATTRIBUTE);
    assertEquals(fingerprinter.fingerprint(fed).fingerprint(), print);
    assertTrue(ErrorFingerprinter.isFingerprint(print));
    assertEquals("X", stamped.get(0).attributes().get("exception.type"));
    assertFalse(fed.attributes().containsKey(ErrorFingerprinter.FINGERPRINT_ATTRIBUTE));
    assertSame(info, stamped.get(1));
  }

  @Test
  void fingerprintShape() {
    assertTrue(ErrorFingerprinter.isFingerprint("f6b59211f7afcfc5bf4c59892a1a5605"));
    assertFalse(ErrorFingerprinter.isFingerprint("F6B59211F7AFCFC5BF4C59892A1A5605"));
    assertFalse(ErrorFingerprinter.isFingerprint("f6b59211"));
    assertFalse(ErrorFingerprinter.isFingerprint(""));
    assertFalse(ErrorFingerprinter.isFingerprint(null));
  }

  private String location(Map<String, String> attributes) {
    return fingerprinter.location(attributes);
  }

  private static StoredLog error(String service, String body, Map<String, String> attributes) {
    return new StoredLog(
        1L, 17, "ERROR", body, "", "", service, new LinkedHashMap<>(attributes), Map.of(), 1L);
  }

  private static StoredLog log(int severity, String service, Map<String, String> attributes) {
    return new StoredLog(1L, severity, "X", "body", "", "", service, attributes, Map.of(), 1L);
  }
}
