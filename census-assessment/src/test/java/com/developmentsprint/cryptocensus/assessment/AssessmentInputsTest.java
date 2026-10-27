package com.developmentsprint.cryptocensus.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Inventory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssessmentInputsTest {
  private static final String NONCE = "12345678-1234-1234-1234-123456789abc";

  private static byte[] cbom(String nonce, boolean healthy) {
    Map<String, String> properties = new java.util.TreeMap<>();
    if (nonce != null) {
      properties.put(EvidenceDocument.RUN_PROPERTY, nonce);
    }
    if (healthy) {
      properties.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
      Cbom.HEALTH_PROPERTIES.forEach(property -> properties.put(property, "0"));
    }
    return Cbom.toJson(new Inventory(), new Cbom.Metadata("display-label", properties))
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] evidence(byte[] cbom) {
    return EvidenceCodec.encode(
        new EvidenceDocument(
            1,
            NONCE,
            CanonicalJson.hash(cbom),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            EvidenceDocument.zeroHealth()));
  }

  @Test
  void unreadableSidecarHasNoInventedHashAndDuplicateOrderPreservesGap() {
    byte[] cbom = cbom(NONCE, true);
    AssessmentInputs.Input unreadable = AssessmentInputs.unreadableEvidence(cbom, "test", "test");
    AssessmentInputs.Input absent = new AssessmentInputs.Input(cbom, null, "test", "test");
    AssessmentInputs.Run run = AssessmentInputs.load(List.of(unreadable, absent)).get(0);
    assertThat(run.evidenceHash()).isEmpty();
    assertThat(run.evidence()).isNull();
    assertThat(run.gaps()).contains("SIDECAR_UNREADABLE", "NO_FLOW_OR_CORRELATION_EVIDENCE");
    assertThat(run).isEqualTo(AssessmentInputs.load(List.of(absent, unreadable)).get(0));
  }

  @Test
  void pairedRunPreservesHealthAndDuplicateInputsDoNotDuplicateCounts() {
    byte[] cbom = cbom(NONCE, true);
    byte[] evidence = evidence(cbom);
    List<AssessmentInputs.Run> runs =
        AssessmentInputs.load(
            List.of(
                new AssessmentInputs.Input(cbom, evidence, "unit", "test"),
                new AssessmentInputs.Input(cbom, evidence, "integration", "staging")));
    assertThat(runs).hasSize(1);
    AssessmentInputs.Run run = runs.get(0);
    assertThat(run.id()).isEqualTo("run:" + NONCE);
    assertThat(run.coreHealth()).hasSize(Cbom.HEALTH_PROPERTIES.size());
    assertThat(run.incomplete()).isFalse();
    assertThat(run.workloads()).containsExactly("integration", "unit");
    assertThat(run.environments()).containsExactly("staging", "test");
    assertThat(run.evidence()).isNotNull();
    assertThatThrownBy(() -> run.entries().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> run.coreHealth().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void legacyAndMissingSidecarsRemainUsableWithExplicitGaps() {
    byte[] legacy = cbom(null, false);
    AssessmentInputs.Run run =
        AssessmentInputs.load(
                List.of(new AssessmentInputs.Input(legacy, null, "unknown", "unknown")))
            .get(0);
    assertThat(run.id()).startsWith("cbom:");
    assertThat(run.nonce()).isEmpty();
    assertThat(run.evidence()).isNull();
    assertThat(run.gaps())
        .contains(
            "RUN_NONCE_NOT_ESTABLISHED",
            "CORE_COLLECTION_HEALTH_NOT_ESTABLISHED",
            "NO_FLOW_OR_CORRELATION_EVIDENCE");
    assertThat(run.incomplete()).isTrue();
    assertThat(
            AssessmentInputs.load(
                    List.of(
                        new AssessmentInputs.Input(cbom("invalid", true), null, "test", "test")))
                .get(0)
                .nonce())
        .isEmpty();
  }

  @Test
  void mismatchedOrMalformedSidecarsNeverSupplyRelationships() {
    byte[] cbom = cbom(NONCE, true);
    byte[] different = cbom(NONCE, false);
    for (byte[] sidecar : List.of(evidence(different), "{bad".getBytes(StandardCharsets.UTF_8))) {
      AssessmentInputs.Run run =
          AssessmentInputs.load(List.of(new AssessmentInputs.Input(cbom, sidecar, "test", "test")))
              .get(0);
      assertThat(run.evidence()).isNull();
      assertThat(run.gaps()).containsExactly("INVALID_OR_MISMATCHED_SIDECAR");
      assertThat(run.incomplete()).isTrue();
      assertThat(run.cbomHash()).isEqualTo(CanonicalJson.hash(cbom));
      assertThat(run.evidenceHash()).isEqualTo(CanonicalJson.hash(sidecar));
    }
  }

  @Test
  void reusedNonceWithDifferentBytesAndConflictingSidecarsAreRejected() {
    byte[] first = cbom(NONCE, true);
    byte[] second = cbom(NONCE, false);
    assertThatThrownBy(
            () ->
                AssessmentInputs.load(
                    List.of(
                        new AssessmentInputs.Input(first, null, "a", "a"),
                        new AssessmentInputs.Input(second, null, "b", "b"))))
        .hasMessageContaining("Conflicting documents");
    assertThatThrownBy(
            () ->
                AssessmentInputs.load(
                    List.of(
                        new AssessmentInputs.Input(first, null, "a", "a"),
                        new AssessmentInputs.Input(first, evidence(first), "b", "b"))))
        .hasMessageContaining("Conflicting documents");
  }

  @Test
  void inputOwnsBytesAndEnforcesAggregateBudgets() {
    byte[] cbom = cbom(NONCE, true);
    byte[] sidecar = evidence(cbom);
    AssessmentInputs.Input input = new AssessmentInputs.Input(cbom, sidecar, "test", "test");
    cbom[0] = '!';
    sidecar[0] = '!';
    assertThat(AssessmentInputs.load(List.of(input)).get(0).evidence()).isNotNull();
    assertThatThrownBy(() -> AssessmentInputs.load(java.util.Collections.nCopies(65, input)))
        .hasMessageContaining("Too many");
    assertThatThrownBy(
            () ->
                new AssessmentInputs.Input(
                    new byte[(int) Cbom.MAX_INPUT_BYTES + 1], null, "test", "test"))
        .hasMessageContaining("file budget");
    AssessmentInputs.Input large =
        new AssessmentInputs.Input(new byte[(int) Cbom.MAX_INPUT_BYTES], null, "test", "test");
    assertThatThrownBy(() -> AssessmentInputs.load(java.util.Collections.nCopies(5, large)))
        .hasMessageContaining("total byte budget");
  }
}
