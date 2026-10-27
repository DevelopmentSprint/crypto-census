package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceContractTest {
  private static final String RUN = "12345678-1234-1234-1234-123456789abc";
  private static final String HASH = "a".repeat(64);
  private static final ContextModel.Subject SUBJECT = new ContextModel.Subject("app", "archive");
  private static final ContextModel.Binding BINDING =
      new ContextModel.Binding(
          "example.Archive", "save", "()V", "example:archive", ContextModel.Origin.MAIN);
  private static final EvidenceDocument.IdentityMapping IDENTITY =
      new EvidenceDocument.IdentityMapping(
          "source-1",
          "deployment-1",
          "loader-1",
          "example:archive",
          "app",
          EvidenceDocument.IdentityStatus.RESOLVED,
          "source-catalog");
  private static final EvidenceDocument.ScopeContext CONTEXT =
      new EvidenceDocument.ScopeContext(
          "source-1",
          List.of(new EvidenceDocument.ScopeRef("source-1", SUBJECT)),
          EvidenceDocument.Association.SYNCHRONOUS_SCOPE);
  private static final CryptoAsset ASSET =
      CryptoAsset.cipher("AES/GCM/NoPadding", 256, "SunJCE", Operation.ENCRYPT);
  private static final Occurrence FIRST = new Occurrence("example.Archive", "save", 10, null);
  private static final Occurrence SECOND = new Occurrence("example.Archive", "save", 20, null);

  private static EvidenceDocument document(
      String hash,
      List<EvidenceDocument.Observation> observations,
      List<EvidenceDocument.Handshake> handshakes,
      Map<EvidenceDocument.Counter, Long> health) {
    ContextModel.Source source =
        new ContextModel.Source(
            ContextModel.SourceKind.ANNOTATION, "example.Archive#save", HASH, "", "");
    ContextModel.Declaration declaration =
        new ContextModel.Declaration(
            SUBJECT, ContextModel.Field.CONFIDENTIALITY_YEARS, "20", source);
    return new EvidenceDocument(
        1,
        RUN,
        hash,
        List.of(
            new EvidenceDocument.DiscoveredFlow(
                "source-1",
                new ContextModel.FlowDefinition(
                    SUBJECT, "Archive", List.of(declaration), List.of(BINDING)))),
        observations,
        handshakes,
        List.of(IDENTITY),
        List.of(
            new EvidenceDocument.Capability(
                "annotation-scopes",
                EvidenceDocument.CapabilityStatus.SUPPORTED,
                "synchronous only")),
        health);
  }

  private static EvidenceDocument.Observation observation(Occurrence site, long count) {
    return new EvidenceDocument.Observation(ASSET.ref(), site, CONTEXT, count, BINDING);
  }

  private static EvidenceDocument.Handshake handshake(
      String connection, EvidenceDocument.AttemptRef parent) {
    return new EvidenceDocument.Handshake(
        connection,
        1,
        EvidenceDocument.Transport.ENGINE,
        EvidenceDocument.PeerRole.CLIENT,
        EvidenceDocument.Outcome.COMPLETED,
        new EvidenceDocument.Negotiation(
            "TLSv1.3", "TLS_AES_256_GCM_SHA384", 4588, "X25519MLKEM768", "", "rsa_pss_rsae_sha256"),
        new EvidenceDocument.Resumption(
            parent == null ? EvidenceDocument.Flag.NO : EvidenceDocument.Flag.YES,
            EvidenceDocument.Flag.YES,
            parent),
        List.of(ASSET.ref()),
        "sunjsse-25",
        List.of(),
        CONTEXT);
  }

  @Test
  void declarationOnlyFixtureDoesNotInventExecution() throws Exception {
    try (java.io.InputStream input =
        getClass().getResourceAsStream("/context/unobserved-evidence-v1.json")) {
      assertThat(input).isNotNull();
      EvidenceDocument document = EvidenceCodec.parse(input.readAllBytes());
      assertThat(document.flows()).hasSize(1);
      assertThat(document.observations()).isEmpty();
      assertThat(document.handshakes()).isEmpty();
      assertThat(EvidenceCodec.parse(EvidenceCodec.encode(document))).isEqualTo(document);
    }
  }

  @Test
  void completeWireRoundTripAndAtomicFileRoundTrip(@TempDir Path directory) throws Exception {
    EvidenceDocument document =
        document(
            HASH,
            List.of(observation(FIRST, 2)),
            List.of(
                handshake("c1", null), handshake("c2", new EvidenceDocument.AttemptRef("c1", 1))),
            EvidenceDocument.zeroHealth());
    byte[] encoded = EvidenceCodec.encode(document);
    assertThat(EvidenceCodec.parse(encoded)).isEqualTo(document);
    assertThat(EvidenceCodec.encode(EvidenceCodec.parse(encoded))).isEqualTo(encoded);
    Path output = directory.resolve("nested/evidence.json");
    EvidenceCodec.write(document, output);
    assertThat(EvidenceCodec.read(output)).isEqualTo(document);
    EvidenceCodec.write(document, output);
    assertThat(DocumentFiles.read(output)).isEqualTo(encoded);
  }

  @Test
  void conservationChecksEachEffectiveOccurrenceAndExactPairing() {
    Inventory inventory = new Inventory();
    inventory.add(ASSET, FIRST, 2);
    inventory.add(ASSET, SECOND, 3);
    byte[] cbom =
        Cbom.toJson(inventory, new Cbom.Metadata("app", Map.of(EvidenceDocument.RUN_PROPERTY, RUN)))
            .getBytes(StandardCharsets.UTF_8);
    String hash = CanonicalJson.hash(cbom);
    EvidenceDocument valid =
        document(
            hash,
            List.of(observation(FIRST, 2), observation(SECOND, 3)),
            List.of(handshake("c1", null)),
            EvidenceDocument.zeroHealth());
    assertThat(EvidenceCodec.validatePair(valid, cbom).inventory().entries()).hasSize(1);
    EvidenceDocument swapped =
        document(
            hash,
            List.of(observation(FIRST, 3), observation(SECOND, 2)),
            List.of(),
            EvidenceDocument.zeroHealth());
    assertThatThrownBy(() -> EvidenceCodec.validatePair(swapped, cbom))
        .hasMessageContaining("occurrence totals");
    assertThatThrownBy(
            () ->
                EvidenceCodec.validatePair(
                    document(HASH, List.of(), List.of(), EvidenceDocument.zeroHealth()), cbom))
        .hasMessageContaining("hash mismatch");
    byte[] otherRun =
        Cbom.toJson(inventory, new Cbom.Metadata("app", Map.of())).getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                EvidenceCodec.validatePair(
                    document(
                        CanonicalJson.hash(otherRun),
                        List.of(),
                        List.of(),
                        EvidenceDocument.zeroHealth()),
                    otherRun))
        .hasMessageContaining("nonce mismatch");
    Map<EvidenceDocument.Counter, Long> health = new EnumMap<>(EvidenceDocument.zeroHealth());
    health.put(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED, 1L);
    assertThat(EvidenceCodec.validatePair(document(hash, List.of(), List.of(), health), cbom))
        .isNotNull();
    EvidenceDocument missingSite =
        document(
            hash,
            List.of(observation(new Occurrence("Other", "run", 1, null), 1)),
            List.of(),
            health);
    assertThatThrownBy(() -> EvidenceCodec.validatePair(missingSite, cbom))
        .hasMessageContaining("absent from CBOM");
  }

  @Test
  void saturationAcrossSitesRequiresAnExplicitDiagnostic() {
    long count = Long.MAX_VALUE / 2 + 1;
    Inventory inventory = new Inventory();
    inventory.add(ASSET, FIRST, count);
    inventory.add(ASSET, SECOND, count);
    byte[] cbom =
        Cbom.toJson(inventory, new Cbom.Metadata("app", Map.of(EvidenceDocument.RUN_PROPERTY, RUN)))
            .getBytes(StandardCharsets.UTF_8);
    List<EvidenceDocument.Observation> observations =
        List.of(observation(FIRST, count), observation(SECOND, count));
    String hash = CanonicalJson.hash(cbom);
    assertThatThrownBy(
            () ->
                EvidenceCodec.validatePair(
                    document(hash, observations, List.of(), EvidenceDocument.zeroHealth()), cbom))
        .hasMessageContaining("Saturated");
    Map<EvidenceDocument.Counter, Long> health = new EnumMap<>(EvidenceDocument.zeroHealth());
    health.put(EvidenceDocument.Counter.SATURATED_COUNTS, 1L);
    assertThat(EvidenceCodec.validatePair(document(hash, observations, List.of(), health), cbom))
        .isNotNull();
    health.put(EvidenceDocument.Counter.SATURATED_COUNTS, 0L);
    health.put(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED, 1L);
    assertThatThrownBy(
            () -> EvidenceCodec.validatePair(document(hash, List.of(), List.of(), health), cbom))
        .hasMessageContaining("Saturated");
  }

  @Test
  void healthCannotBeOmittedOrNegativeAndCollectionsAreSnapshots() {
    assertThatThrownBy(() -> document(HASH, List.of(), List.of(), Map.of()))
        .hasMessageContaining("All evidence health");
    Map<EvidenceDocument.Counter, Long> health = new EnumMap<>(EvidenceDocument.zeroHealth());
    health.put(EvidenceDocument.Counter.DROPPED_FLOWS, -1L);
    assertThatThrownBy(() -> document(HASH, List.of(), List.of(), health))
        .hasMessageContaining("Invalid health");
    health.put(EvidenceDocument.Counter.DROPPED_FLOWS, 0L);
    List<EvidenceDocument.Observation> observations =
        new ArrayList<>(List.of(observation(FIRST, 1)));
    EvidenceDocument snapshot = document(HASH, observations, List.of(), health);
    observations.clear();
    health.put(EvidenceDocument.Counter.DROPPED_FLOWS, 10L);
    assertThat(snapshot.observations()).hasSize(1);
    assertThat(snapshot.health().get(EvidenceDocument.Counter.DROPPED_FLOWS)).isZero();
    assertThatThrownBy(() -> snapshot.observations().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> snapshot.health().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void lineageMustResolveWithoutCycles() {
    assertThatThrownBy(
            () ->
                document(
                    HASH,
                    List.of(),
                    List.of(handshake("c1", new EvidenceDocument.AttemptRef("missing", 1))),
                    EvidenceDocument.zeroHealth()))
        .hasMessageContaining("PSK lineage");
    assertThatThrownBy(
            () ->
                document(
                    HASH,
                    List.of(),
                    List.of(handshake("c1", new EvidenceDocument.AttemptRef("c1", 1))),
                    EvidenceDocument.zeroHealth()))
        .hasMessageContaining("PSK lineage");
    assertThatThrownBy(
            () ->
                document(
                    HASH,
                    List.of(),
                    List.of(
                        handshake("c1", new EvidenceDocument.AttemptRef("c2", 1)),
                        handshake("c2", new EvidenceDocument.AttemptRef("c1", 1))),
                    EvidenceDocument.zeroHealth()))
        .hasMessageContaining("PSK lineage");
    assertThatThrownBy(
            () ->
                document(
                    HASH,
                    List.of(),
                    List.of(handshake("c1", null), handshake("c1", null)),
                    EvidenceDocument.zeroHealth()))
        .hasMessageContaining("Duplicate handshake");
    assertThatThrownBy(
            () ->
                new EvidenceDocument.Resumption(
                    EvidenceDocument.Flag.UNKNOWN,
                    EvidenceDocument.Flag.UNKNOWN,
                    new EvidenceDocument.AttemptRef("c1", 1)))
        .hasMessageContaining("without observed resumption");
  }

  @Test
  void scopeIdentityAndUnknownAssociationCannotInventFlows() {
    assertThatThrownBy(
            () ->
                new EvidenceDocument.ScopeContext(
                    "source-1", List.of(), EvidenceDocument.Association.SYNCHRONOUS_SCOPE))
        .hasMessageContaining("scope path");
    assertThatThrownBy(
            () ->
                new EvidenceDocument.ScopeContext(
                    "other", CONTEXT.path(), EvidenceDocument.Association.SYNCHRONOUS_SCOPE))
        .hasMessageContaining("Active identity");
    EvidenceDocument.ScopeContext missing =
        new EvidenceDocument.ScopeContext(
            "missing", List.of(), EvidenceDocument.Association.NO_SCOPE);
    EvidenceDocument.Observation invalid =
        new EvidenceDocument.Observation(ASSET.ref(), FIRST, missing, 1, BINDING);
    assertThatThrownBy(
            () -> document(HASH, List.of(invalid), List.of(), EvidenceDocument.zeroHealth()))
        .hasMessageContaining("dangling");
    assertThatThrownBy(
            () -> new EvidenceDocument.ScopeRef("source-1", new ContextModel.Subject("app", "")))
        .hasMessageContaining("Application default");
    assertThatThrownBy(
            () ->
                new EvidenceDocument.IdentityMapping(
                    "id",
                    "deployment",
                    "loader",
                    "",
                    "app",
                    EvidenceDocument.IdentityStatus.UNRESOLVED,
                    "unknown"))
        .hasMessageContaining("disagree");
    assertThatThrownBy(() -> new EvidenceDocument.Negotiation("", "", 65536, "", "", ""))
        .hasMessageContaining("named group");
    assertThatThrownBy(() -> new EvidenceDocument.AttemptRef("c", 0))
        .hasMessageContaining("attempt");
    assertThatThrownBy(() -> observation(FIRST, 0)).hasMessageContaining("count");
  }

  @Test
  void strictCodecRejectsUnknownFieldsVersionsAndMissingHealth() {
    byte[] bytes =
        EvidenceCodec.encode(document(HASH, List.of(), List.of(), EvidenceDocument.zeroHealth()));
    Map<String, Object> fields =
        new java.util.TreeMap<>(CanonicalJson.object(CanonicalJson.decode(bytes)));
    fields.put("unexpected", true);
    assertThatThrownBy(() -> EvidenceCodec.parse(CanonicalJson.encode(fields)))
        .hasMessageContaining("Unexpected JSON fields");
    fields.remove("unexpected");
    fields.put("schemaVersion", 2);
    assertThatThrownBy(() -> EvidenceCodec.parse(CanonicalJson.encode(fields)))
        .hasMessageContaining("Unsupported evidence schema");
    fields.put("schemaVersion", Long.MAX_VALUE);
    assertThatThrownBy(() -> EvidenceCodec.parse(CanonicalJson.encode(fields)))
        .isInstanceOf(IllegalArgumentException.class);
    fields.put("schemaVersion", 1);
    fields.put("health", Map.of());
    assertThatThrownBy(() -> EvidenceCodec.parse(CanonicalJson.encode(fields)))
        .hasMessageContaining("All evidence health");
  }
}
