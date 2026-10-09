package com.developmentsprint.cryptocensus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Operation;
import com.developmentsprint.cryptocensus.core.Primitive;
import com.developmentsprint.cryptocensus.core.Risk;
import com.developmentsprint.cryptocensus.core.Targets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

/** Runs {@link CryptoWorkload} in a child JVM with the packaged (shaded) agent attached. */
class AgentIT {

  /**
   * Newest class file version the bundled Byte Buddy (1.18.14) parses without experimental mode.
   */
  static final int LATEST_JDK_SUPPORTED_BY_BYTE_BUDDY = 27;

  @TempDir static Path tmp;

  static Cbom.Document document;
  static Inventory inventory;
  static String stderr;
  static int exitCode;

  @BeforeAll
  static void runWorkload() throws Exception {
    Path keystore = ChildJvm.keystore(tmp);

    Path cbom = tmp.resolve("cbom.json");
    ChildJvm.Result run =
        runAgent(
            "output=" + cbom + ",app=agent-it",
            List.of(keystore.toString(), ChildJvm.KEYSTORE_PASSWORD));
    stderr = run.stderr();
    exitCode = run.exitCode();
    assertThat(exitCode).as(stderr).isZero();
    assertThat(cbom).as(stderr).exists();
    document = Cbom.read(cbom);
    inventory = document.inventory();
  }

  // ------------------------------------------------------------------ agent health

  @Test
  void agentStartsCleanlyAndReportsHealthyCollection() {
    assertThat(stderr).doesNotContain("failed to start", "failed to write", "Exception");
    assertThat(stderr).contains("CBOM written to");
    assertThat(document.hasAgentHealth()).isTrue();
    assertThat(document.properties())
        .containsEntry("cryptocensus:transformErrors", "0")
        .containsEntry("cryptocensus:recordingFailures", "0")
        .containsEntry("cryptocensus:dropped", "0")
        .containsEntry("cryptocensus:overflowedSites", "0")
        .containsEntry("cryptocensus:drainTimedOut", "0");
    assertThat(document.properties().get("cryptocensus:instrumented"))
        .contains("javax.crypto.Cipher", "java.security.Signature", "java.security.MessageDigest");
  }

  @Test
  void reportsEveryFixedTargetAndNoGaps() {
    Map<String, Targets.Status> targets =
        Targets.parse(document.properties().get(Targets.PROPERTY));
    assertThat(targets)
        .as(stderr)
        .containsEntry("javax.crypto.Cipher", Targets.Status.INSTRUMENTED)
        .containsEntry("java.security.KeyPairGenerator", Targets.Status.INSTRUMENTED)
        .containsEntry("sun.security.ssl.SSLSessionImpl", Targets.Status.INSTRUMENTED);
    assertThat(targets.values()).noneMatch(Targets.Status::isGap);
    if (Runtime.version().feature() >= 24) {
      // the workload uses ML-KEM there
      assertThat(targets).containsEntry("javax.crypto.KEM", Targets.Status.INSTRUMENTED);
    } else if (Runtime.version().feature() < 21) {
      assertThat(targets).doesNotContainKey("javax.crypto.KEM");
    }
    assertThat(Targets.parse(document.properties().get(Targets.MISSING_PROPERTY)))
        .doesNotContainValue(Targets.Status.INSTRUMENTED);
    assertThat(stderr).doesNotContain("instrumentation gap");
  }

  @Test
  void unusedTargetsAreNotLoadedRatherThanMissing() throws Exception {
    // no TLS in this run: the session class is never loaded, which is not a gap
    Path cbom = tmp.resolve("no-tls.json");
    ChildJvm.Result run = runAgent("output=" + cbom + ",summary=false", List.of());
    assertThat(run.exitCode()).as(run.stderr()).isZero();
    Map<String, Targets.Status> missing =
        Targets.parse(Cbom.read(cbom).properties().get(Targets.MISSING_PROPERTY));
    assertThat(missing).containsEntry("sun.security.ssl.SSLSessionImpl", Targets.Status.NOT_LOADED);
    assertThat(missing.values()).noneMatch(Targets.Status::isGap);
  }

  // ------------------------------------------------------------------ newer JDKs

  @Test
  void byteBuddyExperimentalIsOffByDefault() {
    assertThat(document.properties()).containsEntry("cryptocensus:byteBuddyExperimental", "false");
  }

  @Test
  void standardByteBuddyExperimentalPropertyReachesTheShadedCopy() throws Exception {
    // Regression: shading relocated the property name, so -Dnet.bytebuddy.experimental had no
    // effect. The CBOM reports the value the shaded Byte Buddy actually reads.
    Path cbom = tmp.resolve("experimental-property.json");
    ChildJvm.Result run =
        runAgent(
            List.of(
                "-Dnet.bytebuddy.experimental=true",
                "-javaagent:" + agentJar() + "=output=" + cbom + ",summary=false"),
            List.of());
    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stderr()).contains("experimental mode");
    assertThat(Cbom.read(cbom).properties())
        .containsEntry("cryptocensus:byteBuddyExperimental", "true")
        .containsEntry("cryptocensus:transformErrors", "0");
  }

  @Test
  void experimentalAgentOptionEnablesIt() throws Exception {
    Path cbom = tmp.resolve("experimental-option.json");
    ChildJvm.Result run =
        runAgent("output=" + cbom + ",summary=false,experimental=true", List.of());
    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(Cbom.read(cbom).properties())
        .containsEntry("cryptocensus:byteBuddyExperimental", "true");
  }

  @Test
  @EnabledIf("jdkNewerThanByteBuddySupports")
  void newerJdkIsInstrumentedOnlyInExperimentalModeAndSaysSo() throws Exception {
    // Without the switch the agent must not pretend: errors and gaps are reported.
    Path strict = tmp.resolve("newer-jdk-default.json");
    ChildJvm.Result failing = runAgent("output=" + strict + ",summary=false", List.of());
    assertThat(failing.exitCode()).as(failing.stderr()).isZero();
    Cbom.Document doc = Cbom.read(strict);
    assertThat(doc.health("cryptocensus:transformErrors")).isPositive();
    assertThat(failing.stderr()).contains("instrumentation gap");

    Path experimental = tmp.resolve("newer-jdk-experimental.json");
    ChildJvm.Result run =
        runAgent("output=" + experimental + ",summary=false,experimental=true", List.of());
    assertThat(run.exitCode()).as(run.stderr()).isZero();
    Cbom.Document ok = Cbom.read(experimental);
    assertThat(ok.properties()).containsEntry("cryptocensus:transformErrors", "0");
    assertThat(ok.inventory().entries()).isNotEmpty();
  }

  static boolean jdkNewerThanByteBuddySupports() {
    return Runtime.version().feature() > LATEST_JDK_SUPPORTED_BY_BYTE_BUDDY;
  }

  // ------------------------------------------------------------------ vendor providers

  @Test
  @EnabledIf("openJcePlusAvailable")
  void attributesOpenJcePlusKeyGenerationToTheApplication() {
    // Regression (IBM Semeru): OpenJCEPlus's own generateKeyPair frame was reported as the caller.
    List<Inventory.Entry> dsa =
        inventory.entries().stream()
            .filter(e -> e.asset().primitive() == Primitive.KEY_PAIR_GENERATION)
            .filter(e -> CryptoWorkload.OPEN_JCE_PLUS.equals(e.asset().provider()))
            .toList();
    assertThat(dsa).as(describe()).isNotEmpty();
    assertThat(dsa)
        .allSatisfy(
            e ->
                assertThat(e.occurrences().keySet())
                    .allSatisfy(
                        o -> {
                          assertThat(o.location()).isEqualTo(CryptoWorkload.class.getName());
                          assertThat(o.symbol()).isEqualTo("openJcePlus");
                        }));
  }

  static boolean openJcePlusAvailable() {
    return Security.getProvider(CryptoWorkload.OPEN_JCE_PLUS) != null;
  }

  @Test
  void doesNotExportThirdPartySecretKeys() {
    // a key we may not safely export is reported with unknown size rather than read
    assertThat(find(Primitive.CIPHER, "AES", 0, "CBC", Operation.ENCRYPT)).isPresent();
  }

  // ------------------------------------------------------------------ verdicts

  @Test
  void recordsAesWithObservedKeySizeModeAndOperation() {
    assertThat(risk(Primitive.CIPHER, "AES", 256, "GCM", Operation.ENCRYPT))
        .isEqualTo(Risk.QUANTUM_SAFE);
    assertThat(risk(Primitive.CIPHER, "AES", 128, "ECB", Operation.ENCRYPT)).isEqualTo(Risk.WEAK);
  }

  @Test
  void bareAesIsReportedAsProviderDefaultEcb() {
    assertThat(risk(Primitive.CIPHER, "AES", 256, null, Operation.ENCRYPT)).isEqualTo(Risk.WEAK);
  }

  @Test
  void md5RequestedByOidIsStillMd5() {
    assertThat(risk(Primitive.MESSAGE_DIGEST, "MD5", 0, null, null)).isEqualTo(Risk.BROKEN);
    assertThat(inventory.entries())
        .noneSatisfy(e -> assertThat(e.asset().algorithm()).contains("1.2.840"));
  }

  @Test
  void providerAliasesResolveToCanonicalNames() {
    // regression: DSS and the OIW OID signed successfully but were reported as unknown
    assertThat(risk(Primitive.SIGNATURE, "SHA1withDSA", 1024, null, Operation.SIGN))
        .isEqualTo(Risk.BROKEN);
    assertThat(inventory.entries())
        .noneSatisfy(e -> assertThat(e.asset().algorithm()).isIn("DSS", "1.3.14.3.2.29"));
  }

  @Test
  void paddingNamesContainingSlashesAreKeptWhole() {
    assertThat(risk(Primitive.CIPHER, "RSA", 2048, "ECB", Operation.ENCRYPT))
        .isEqualTo(Risk.QUANTUM_VULNERABLE);
    assertThat(inventory.entries())
        .anySatisfy(
            e -> assertThat(e.asset().padding()).isEqualTo("OAEPWithSHA-512/256AndMGF1Padding"));
  }

  @Test
  void unobservableHmacKeyIsUnknownNotAcceptable() {
    assertThat(risk(Primitive.MAC, "HmacSHA256", 0, null, null)).isEqualTo(Risk.UNKNOWN);
  }

  @Test
  void shortHmacKeyIsBroken() {
    assertThat(risk(Primitive.MAC, "HmacSHA512", 8, null, null)).isEqualTo(Risk.BROKEN);
    assertThat(risk(Primitive.MAC, "HmacSHA256", 256, null, null)).isEqualTo(Risk.ACCEPTABLE);
  }

  @Test
  void distinguishesProtectionFromLegacyUse() {
    assertThat(risk(Primitive.SIGNATURE, "SHA1withRSA", 2048, null, Operation.SIGN))
        .isEqualTo(Risk.BROKEN);
    assertThat(risk(Primitive.SIGNATURE, "SHA1withRSA", 2048, null, Operation.VERIFY))
        .isEqualTo(Risk.WEAK);
    assertThat(risk(Primitive.CIPHER, "DESede", 168, "CBC", Operation.ENCRYPT))
        .isEqualTo(Risk.BROKEN);
    assertThat(risk(Primitive.CIPHER, "DESede", 168, "CBC", Operation.DECRYPT))
        .isEqualTo(Risk.WEAK);
  }

  @Test
  void recordsRsaSignatureAsQuantumVulnerable() {
    assertThat(risk(Primitive.SIGNATURE, "SHA256withRSA", 2048, null, Operation.SIGN))
        .isEqualTo(Risk.QUANTUM_VULNERABLE);
  }

  @Test
  void refinesXdhToTheConcreteCurve() {
    assertThat(find(Primitive.KEY_AGREEMENT, "X25519", 255, null, null)).isPresent();
  }

  @Test
  void recordsDigestsAndMacs() {
    assertThat(find(Primitive.MESSAGE_DIGEST, "SHA-1", 0, null, null)).isPresent();
    assertThat(risk(Primitive.MESSAGE_DIGEST, "SHA-384", 0, null, null))
        .isEqualTo(Risk.QUANTUM_SAFE);
  }

  // ------------------------------------------------------------------ attribution

  @Test
  void attributesUseToTheApplicationCallSite() {
    Inventory.Entry md5 = find(Primitive.MESSAGE_DIGEST, "MD5", 0, null, null).orElseThrow();
    assertThat(md5.occurrences().keySet())
        .allSatisfy(
            site -> {
              assertThat(site.location()).isEqualTo(CryptoWorkload.class.getName());
              assertThat(site.line()).isPositive();
            });
  }

  @Test
  void countsOuterJcaCallOnlyOnce() {
    // Cipher.init(int, Key) delegates to another init overload; that is still one use.
    assertThat(find(Primitive.CIPHER, "AES", 128, "ECB", Operation.ENCRYPT).orElseThrow().total())
        .isEqualTo(1);
  }

  @Test
  void instrumentsKeyPairGeneratorDelegatesAndHidesTheirInternals() {
    // Regression: KeyPairGenerator$Delegate used to slip past instrumentation, which also let the
    // SHA-1 used internally by BigInteger prime generation leak into the inventory.
    assertThat(risk(Primitive.KEY_PAIR_GENERATION, "RSA", 2048, null, null))
        .isEqualTo(Risk.QUANTUM_VULNERABLE);
    Inventory.Entry sha1 = find(Primitive.MESSAGE_DIGEST, "SHA-1", 0, null, null).orElseThrow();
    assertThat(sha1.occurrences().keySet())
        .noneSatisfy(o -> assertThat(o.via()).isEqualTo("java.math.BigInteger"));
  }

  // ------------------------------------------------------------------ TLS

  @Test
  void recordsNegotiatedTlsSessionsOncePerSessionObject() {
    List<Inventory.Entry> tls =
        inventory.entries().stream().filter(e -> e.asset().primitive() == Primitive.TLS).toList();
    assertThat(tls)
        .as(stderr)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.asset().protocolVersion()).isEqualTo("TLSv1.3");
              assertThat(e.asset().algorithm()).startsWith("TLS_");
              assertThat(e.occurrences().keySet())
                  .allSatisfy(o -> assertThat(o.via()).isEqualTo("jsse"));
              // one handshake: client session, server session, plus at most a couple of resumption
              // tickets.
              // Constructor chaining used to multiply this.
              assertThat(e.total()).isBetween(2L, 4L);
            });
  }

  @Test
  void attributesTlsKeyExchangeToJsse() {
    assertThat(inventory.entries())
        .anySatisfy(
            e -> {
              assertThat(e.asset().primitive()).isEqualTo(Primitive.KEY_AGREEMENT);
              assertThat(e.occurrences().keySet())
                  .anySatisfy(o -> assertThat(o.via()).isEqualTo("jsse"));
            });
  }

  // ------------------------------------------------------------------ post-quantum

  @Test
  @EnabledForJreRange(min = JRE.JAVA_24)
  void recordsPostQuantumOperationsWithParameterSets() {
    // exact primitives: a key-generation entry must not satisfy the KEM / signature assertions
    assertThat(risk(Primitive.KEM, "ML-KEM-768", 0, null, null)).isEqualTo(Risk.QUANTUM_SAFE);
    assertThat(risk(Primitive.SIGNATURE, "ML-DSA-65", 0, null, Operation.SIGN))
        .isEqualTo(Risk.QUANTUM_SAFE);
    assertThat(find(Primitive.KEY_PAIR_GENERATION, "ML-KEM-768", 0, null, null)).isPresent();
  }

  // ------------------------------------------------------------------ lifecycle

  @Test
  void repeatedJavaagentInstallsOnce() throws Exception {
    Path cbom = tmp.resolve("twice.json");
    String opts = "output=" + cbom + ",summary=false";
    ChildJvm.Result run =
        runAgent(
            List.of(
                "-javaagent:" + agentJar() + "=" + opts, "-javaagent:" + agentJar() + "=" + opts),
            List.of());
    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stderr()).contains("already started");
    Inventory twice = Cbom.read(cbom).inventory();
    // counts are not doubled by a second transformer (MD5 by name, plus by OID where the JDK
    // supports it)
    long expected = Runtime.version().feature() >= 21 ? 2 : 1;
    assertThat(
            twice.entries().stream()
                .filter(e -> e.asset().algorithm().equals("MD5"))
                .findFirst()
                .orElseThrow()
                .total())
        .isEqualTo(expected);
  }

  @Test
  void optionValuesArePercentDecodedAndUnknownOptionsWarn() throws Exception {
    Path dir = tmp.resolve("odd, dir=name");
    String encoded =
        dir.toString()
            .replace("%", "%25")
            .replace(",", "%2C")
            .replace("=", "%3D")
            .replace(" ", "%20");
    ChildJvm.Result run = runAgent("dir=" + encoded + ",summary=false,bogus=1", List.of());
    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stderr()).contains("unknown option 'bogus'");
    try (var files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString()).toList())
          .singleElement()
          .asString()
          .matches("cbom-\\d+-[0-9a-f]+\\.json");
    }
  }

  // ------------------------------------------------------------------ helpers

  private static Optional<Inventory.Entry> find(
      Primitive primitive, String algorithm, int keySize, String mode, Operation op) {
    return inventory.entries().stream()
        .filter(
            e -> {
              CryptoAsset a = e.asset();
              return a.primitive() == primitive
                  && a.algorithm().equalsIgnoreCase(algorithm)
                  && a.keySize() == keySize
                  && (mode == null ? a.mode() == null : mode.equalsIgnoreCase(a.mode()))
                  && a.operation() == op;
            })
        .findFirst();
  }

  private static Risk risk(
      Primitive primitive, String algorithm, int keySize, String mode, Operation op) {
    return find(primitive, algorithm, keySize, mode, op)
        .orElseThrow(
            () ->
                new AssertionError(
                    "no "
                        + primitive
                        + " "
                        + algorithm
                        + "/"
                        + keySize
                        + "/"
                        + mode
                        + "/"
                        + op
                        + " in\n"
                        + describe()))
        .assessment()
        .risk();
  }

  private static String describe() {
    StringBuilder sb = new StringBuilder();
    inventory.entries().forEach(e -> sb.append("  ").append(e.asset()).append('\n'));
    return sb.toString();
  }

  private static ChildJvm.Result runAgent(String agentOptions, List<String> args) throws Exception {
    return runAgent(List.of("-javaagent:" + agentJar() + "=" + agentOptions), args);
  }

  private static ChildJvm.Result runAgent(List<String> jvmArgs, List<String> args)
      throws Exception {
    return ChildJvm.run(
        tmp,
        jvmArgs,
        List.of(ChildJvm.classpathEntry(CryptoWorkload.class)),
        CryptoWorkload.class,
        args);
  }

  private static String agentJar() {
    return ChildJvm.agentJar();
  }
}
