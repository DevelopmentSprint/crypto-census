package com.developmentsprint.cryptocensus.agent;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.DocumentFiles;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Primitive;
import com.developmentsprint.cryptocensus.core.SourceCatalog;
import com.developmentsprint.cryptocensus.it.FlowWorkload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentEnrichmentTest {
  private static final CryptoAsset ASSET = CryptoAsset.of(Primitive.CIPHER, "AES", 256, "unit");
  private static final Occurrence SITE = new Occurrence("example.App", "work", 1, null);
  @TempDir Path tmp;

  @AfterEach
  void reset() {
    AgentEnrichment.configure(AgentConfig.parse(""));
    Recorder.configure(500, 50);
    Recorder.disable();
    Recorder.beforeEnrichmentHook = null;
  }

  @Test
  void typedBindingsAndOneRetainedEventProduceValidatedPair() throws Exception {
    final Path output = configure("complete", true, "");
    assertThat(AgentEnrichment.enabled()).isTrue();
    AgentEnrichment.installed();
    int binding = binding();
    long previous = FlowAdvice.enter(binding);
    var inventory = new Inventory();
    FlowWorkload.invoke(
        () -> AgentEnrichment.record(ASSET, inventory.recordWithOutcome(ASSET, SITE)));
    assertThat(AgentEnrichment.context().path()).hasSize(1);
    FlowAdvice.exit(previous);
    write(output, inventory);
    AgentEnrichment.stop(0);
    assertThat(AgentEnrichment.enabled()).isFalse();
    assertThat(AgentEnrichment.publish(inventory.entries(), 5000)).isTrue();
    var evidence = EvidenceCodec.read(tmp.resolve("complete.evidence.json"));
    EvidenceCodec.validatePair(evidence, Cbom.readBytes(output));
    assertThat(evidence.observations()).hasSize(1);
    assertThat(evidence.observations().get(0).projectFrame().origin())
        .isEqualTo(ContextModel.Origin.TEST);
    assertThat(evidence.flows()).hasSize(1);
    assertThat(evidence.capabilities())
        .anySatisfy(
            capability -> {
              assertThat(capability.feature()).isEqualTo("annotation-scopes");
              assertThat(capability.status())
                  .isEqualTo(EvidenceDocument.CapabilityStatus.SUPPORTED);
            });
  }

  @Test
  void depthOverflowDoesNotInvalidateIndependentScopes() throws Exception {
    final Path output = configure("local-overflow", true, ",maxScopeDepth=1");
    AgentEnrichment.installed();
    int binding = binding();
    Inventory inventory = new Inventory();
    final long outer = FlowAdvice.enter(binding);
    AgentEnrichment.record(ASSET, inventory.recordWithOutcome(ASSET, SITE));
    long inner = FlowAdvice.enter(binding);
    AgentEnrichment.record(ASSET, inventory.recordWithOutcome(ASSET, SITE));
    FlowAdvice.exit(inner);
    FlowAdvice.exit(outer);
    long independent = FlowAdvice.enter(binding);
    AgentEnrichment.record(ASSET, inventory.recordWithOutcome(ASSET, SITE));
    FlowAdvice.exit(independent);
    write(output, inventory);
    AgentEnrichment.stop(0);
    assertThat(AgentEnrichment.publish(inventory.entries(), 5000)).isTrue();
    var evidence = EvidenceCodec.read(tmp.resolve("local-overflow.evidence.json"));
    EvidenceCodec.validatePair(evidence, Cbom.readBytes(output));
    assertThat(evidence.health().get(EvidenceDocument.Counter.SCOPE_INTEGRITY)).isZero();
    assertThat(evidence.health().get(EvidenceDocument.Counter.LOST_ATTRIBUTION)).isPositive();
    assertThat(evidence.observations())
        .filteredOn(
            value -> value.context().association() == EvidenceDocument.Association.SCOPE_LOST)
        .hasSize(1);
    assertThat(evidence.observations())
        .filteredOn(
            value ->
                value.context().association() == EvidenceDocument.Association.SYNCHRONOUS_SCOPE)
        .singleElement()
        .satisfies(value -> assertThat(value.count()).isEqualTo(2));
  }

  @Test
  void attributionFailureAndDrainTimeoutNeverChangeCoreCounts() throws Exception {
    final Path output = configure("partial", false, "");
    var inventory = new Inventory();
    final long failures = Recorder.failures();
    AgentEnrichment.record(null, inventory.recordWithOutcome(ASSET, SITE));
    assertThat(inventory.entries().get(0).total()).isEqualTo(1);
    assertThat(Recorder.failures()).isEqualTo(failures);
    AgentEnrichment.diagnostic(EvidenceDocument.Counter.ANNOTATION_ERRORS);
    write(output, inventory);
    AgentEnrichment.stop(1);
    assertThat(AgentEnrichment.publish(inventory.entries(), 5000)).isTrue();
    var evidence = EvidenceCodec.read(tmp.resolve("partial.evidence.json"));
    EvidenceCodec.validatePair(evidence, Cbom.readBytes(output));
    assertThat(evidence.health().get(EvidenceDocument.Counter.RECORDING_FAILURES)).isEqualTo(1);
    assertThat(evidence.health().get(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED))
        .isEqualTo(1);
    assertThat(evidence.health().get(EvidenceDocument.Counter.DRAIN_TIMEOUT)).isEqualTo(1);
  }

  @Test
  void unresolvedIdentityMissingCatalogAndCoreDropsRemainExplicit() throws Exception {
    final Path output =
        configure(
            "unresolved",
            false,
            ",applicationId=consumer,sourcesFile=" + tmp.resolve("missing.json"));
    AgentEnrichment.installed();
    assertThat(binding()).isZero();
    final long previous = FlowBridge.enter(0);
    var inventory = Inventory.bounded(1, 1);
    FlowWorkload.invoke(
        () -> AgentEnrichment.record(ASSET, inventory.recordWithOutcome(ASSET, SITE)));
    var other = CryptoAsset.of(Primitive.SIGNATURE, "RSA", 2048, "unit");
    AgentEnrichment.record(other, inventory.recordWithOutcome(other, SITE));
    FlowBridge.exit(previous);
    write(output, inventory);
    AgentEnrichment.stop(0);
    assertThat(AgentEnrichment.publish(inventory.entries(), 5000)).isTrue();
    var evidence = EvidenceCodec.read(tmp.resolve("unresolved.evidence.json"));
    assertThat(evidence.flows()).isEmpty();
    assertThat(evidence.observations()).hasSize(1);
    assertThat(evidence.observations().get(0).context().association())
        .isEqualTo(EvidenceDocument.Association.SCOPE_LOST);
    assertThat(evidence.health().get(EvidenceDocument.Counter.LOST_ATTRIBUTION)).isPositive();
    assertThat(evidence.observations().get(0).projectFrame().origin())
        .isEqualTo(ContextModel.Origin.UNKNOWN);
  }

  @Test
  void failedAndTimedOutPublicationCannotReplaceTheCoreArtifact() throws Exception {
    Path directory = Files.createDirectory(tmp.resolve("directory"));
    final Path output = configure("failed", false, ",evidenceOutput=" + directory);
    var inventory = new Inventory();
    write(output, inventory);
    final byte[] original = Files.readAllBytes(output);
    AgentEnrichment.stop(0);
    assertThat(AgentEnrichment.publish(inventory.entries(), 5000)).isFalse();
    assertThat(AgentEnrichment.publish(inventory.entries(), 0)).isFalse();
    assertThat(Files.readAllBytes(output)).isEqualTo(original);
    assertThat(AgentEnrichment.health().get(EvidenceDocument.Counter.OUTPUT_FAILURES)).isEqualTo(2);
  }

  @Test
  void disabledFacadeAndInterruptedPublicationRespectCallerState() throws Exception {
    AgentEnrichment.configure(AgentConfig.parse(""));
    assertThat(AgentEnrichment.runId()).isNull();
    assertThat(AgentEnrichment.enabled()).isFalse();
    AgentEnrichment.installed();
    AgentEnrichment.diagnostic(EvidenceDocument.Counter.ANNOTATION_ERRORS);
    AgentEnrichment.record(null, null);
    AgentEnrichment.stop(0);
    assertThat(AgentEnrichment.context().association())
        .isEqualTo(EvidenceDocument.Association.NO_SCOPE);
    assertThat(AgentEnrichment.health()).isEqualTo(EvidenceDocument.zeroHealth());
    assertThat(AgentEnrichment.publish(List.of(), 0)).isTrue();
    final Path output = configure("interrupted", false, "");
    var inventory = new Inventory();
    write(output, inventory);
    Thread.currentThread().interrupt();
    try {
      assertThat(AgentEnrichment.publish(inventory.entries(), 5000)).isFalse();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void unannotatedObservationsRetainMappedApplicationIdentity() throws Exception {
    final Path output = configure("observe-first", true, "");
    var inventory = new Inventory();
    FlowWorkload.invoke(
        () -> AgentEnrichment.record(ASSET, inventory.recordWithOutcome(ASSET, SITE)));
    write(output, inventory);
    AgentEnrichment.stop(0);
    assertThat(AgentEnrichment.publish(inventory.entries(), 5000)).isTrue();
    var evidence = EvidenceCodec.read(tmp.resolve("observe-first.evidence.json"));
    var observation = evidence.observations().get(0);
    assertThat(observation.context().association())
        .isEqualTo(EvidenceDocument.Association.NO_SCOPE);
    assertThat(evidence.identities())
        .anySatisfy(
            identity -> {
              assertThat(identity.id()).isEqualTo(observation.context().identityId());
              assertThat(identity.status()).isEqualTo(EvidenceDocument.IdentityStatus.RESOLVED);
              assertThat(identity.applicationId()).isEqualTo("fixture:test");
            });
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs({
    org.junit.jupiter.api.condition.OS.LINUX,
    org.junit.jupiter.api.condition.OS.MAC
  })
  void symlinkedParentCannotRedirectEvidenceOverTheCoreArtifact() throws Exception {
    Path real = Files.createDirectory(tmp.resolve("real"));
    Path alias = Files.createSymbolicLink(tmp.resolve("alias"), real);
    Path output = configure("real/cbom", false, ",evidenceOutput=" + alias.resolve("cbom.json"));
    var inventory = new Inventory();
    write(output, inventory);
    byte[] original = Files.readAllBytes(output);
    AgentEnrichment.stop(0);
    assertThat(AgentEnrichment.publish(inventory.entries(), 5000)).isFalse();
    assertThat(Files.readAllBytes(output)).isEqualTo(original);
    assertThat(Cbom.read(output).properties().get(EvidenceDocument.RUN_PROPERTY))
        .isEqualTo(AgentEnrichment.runId());
  }

  @Test
  void failureAfterCanonicalRecordingCannotIncrementCoreFailureHealth() throws Exception {
    final Path output = configure("callback-failed", true, "");
    Recorder.configure(500, 50);
    Recorder.enable();
    Recorder.enableEnrichment();
    var key = new javax.crypto.spec.SecretKeySpec(new byte[16], "AES");
    var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key);
    final long failures = Recorder.failures();
    Recorder.beforeEnrichmentHook =
        () -> {
          throw new IllegalStateException("injected attribution failure");
        };
    Recorder.exit(Recorder.enter(), cipher, key, javax.crypto.Cipher.ENCRYPT_MODE, null);
    assertThat(Recorder.inventory().entries()).hasSize(1);
    assertThat(Recorder.failures()).isEqualTo(failures);
    assertThat(Recorder.enrichmentFailures()).isEqualTo(1);
    write(output, Recorder.inventory());
    AgentEnrichment.stop(0);
    assertThat(AgentEnrichment.publish(Recorder.inventory().entries(), 5000)).isTrue();
    var evidence = EvidenceCodec.read(tmp.resolve("callback-failed.evidence.json"));
    EvidenceCodec.validatePair(evidence, Cbom.readBytes(output));
    assertThat(evidence.health().get(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED))
        .isPositive();
  }

  private Path configure(String name, boolean catalog, String extra) throws Exception {
    final Path output = tmp.resolve(name + ".json");
    String args = "enrichment=true,output=" + output;
    if (catalog) {
      Path source = tmp.resolve("sources.json");
      var entries =
          List.of(
              new SourceCatalog.Entry(
                  AgentEnrichmentTest.class
                      .getProtectionDomain()
                      .getCodeSource()
                      .getLocation()
                      .toURI()
                      .toString(),
                  "fixture:test",
                  "fixture:test",
                  ContextModel.Origin.TEST,
                  "test-deployment"));
      DocumentFiles.write(source, new SourceCatalog(1, entries).encode());
      args += ",sourcesFile=" + source;
    }
    AgentEnrichment.configure(AgentConfig.parse(args + extra));
    return output;
  }

  private static int binding() {
    return AgentEnrichment.binding(
        TypeDescription.ForLoadedType.of(FlowDeclarationsTest.Declarations.class)
            .getDeclaredMethods()
            .filter(named("archive"))
            .getOnly(),
        AgentEnrichmentTest.class.getClassLoader(),
        AgentEnrichmentTest.class.getProtectionDomain());
  }

  private static void write(Path output, Inventory inventory) throws Exception {
    Cbom.write(
        inventory,
        new Cbom.Metadata("unit", Map.of(EvidenceDocument.RUN_PROPERTY, AgentEnrichment.runId())),
        output);
  }
}
