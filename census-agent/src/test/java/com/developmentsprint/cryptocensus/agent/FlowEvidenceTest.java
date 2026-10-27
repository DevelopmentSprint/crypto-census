package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Primitive;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowEvidenceTest {
  private static final CryptoAsset ASSET = CryptoAsset.of(Primitive.CIPHER, "AES", 256, "test");
  private static final Occurrence SITE = new Occurrence("example.App", "work", 10, null);
  private static final ContextModel.Binding FRAME =
      new ContextModel.Binding("example.App", "work", "()V", "acme:app", ContextModel.Origin.MAIN);

  @Test
  void coreRetentionIsAuthoritativeAndParentsDoNotMultiplyCounts() {
    var health = new FlowHealth();
    var evidence = new FlowEvidence(8, health::increment);
    var inventory = Inventory.bounded(1, 1);
    var path =
        new EvidenceDocument.ScopeContext(
            "id",
            List.of(
                new EvidenceDocument.ScopeRef("id", new ContextModel.Subject("app", "outer")),
                new EvidenceDocument.ScopeRef("id", new ContextModel.Subject("app", "inner"))),
            EvidenceDocument.Association.SYNCHRONOUS_SCOPE);
    evidence.record(ASSET.ref(), inventory.addWithOutcome(ASSET, SITE, 4), path, FRAME, 4);
    evidence.record(
        ASSET.ref(),
        inventory.recordWithOutcome(ASSET, new Occurrence("other", "work", 1, null)),
        path,
        FRAME,
        1);
    var dropped = CryptoAsset.of(Primitive.SIGNATURE, "RSA", 2048, "test");
    evidence.record(dropped.ref(), inventory.recordWithOutcome(dropped, SITE), path, FRAME, 1);
    var observations = evidence.snapshot(inventory.entries(), false);
    assertThat(observations).hasSize(2);
    assertThat(observations.stream().mapToLong(EvidenceDocument.Observation::count).sum())
        .isEqualTo(5);
    assertThat(observations)
        .extracting(EvidenceDocument.Observation::assetRef)
        .containsOnly(ASSET.ref());
    assertThat(observations)
        .extracting(EvidenceDocument.Observation::occurrence)
        .contains(SITE, Occurrence.OVERFLOW);
    assertThat(inventory.droppedObservations()).isEqualTo(1);
    assertThat(health.snapshot().values()).containsOnly(0L);
  }

  @Test
  void writeTimeFoldingAndRunIntegrityLossPreserveOneCountPerEvent() {
    var health = new FlowHealth();
    var evidence = new FlowEvidence(8, health::increment);
    var inventory = new Inventory();
    var context = FlowCatalog.unassigned(EvidenceDocument.Association.NO_SCOPE);
    evidence.record(ASSET.ref(), inventory.recordWithOutcome(ASSET, SITE), context, FRAME, 1);
    evidence.record(
        ASSET.ref(),
        inventory.recordWithOutcome(ASSET, new Occurrence("other", "work", 1, null)),
        context,
        FRAME,
        1);
    Inventory.Entry original = inventory.entries().get(0);
    var folded = new Inventory.Entry(ASSET, original.assessment(), Map.of(Occurrence.OVERFLOW, 2L));
    var observations = evidence.snapshot(List.of(folded), true);
    assertThat(observations).hasSize(1);
    assertThat(observations.get(0).count()).isEqualTo(2);
    assertThat(observations.get(0).context().association())
        .isEqualTo(EvidenceDocument.Association.SCOPE_LOST);
    assertThat(observations.get(0).context().path()).isEmpty();
    assertThat(observations.get(0).occurrence()).isEqualTo(Occurrence.OVERFLOW);
  }

  @Test
  void attributionCapsMarkConservationUnverifiedWithoutChangingCoreCounts() {
    var health = new FlowHealth();
    var evidence = new FlowEvidence(1, health::increment);
    var inventory = new Inventory();
    evidence.record(
        ASSET.ref(),
        inventory.recordWithOutcome(ASSET, SITE),
        FlowCatalog.unassigned(EvidenceDocument.Association.NO_SCOPE),
        FRAME,
        1);
    evidence.record(
        ASSET.ref(),
        inventory.recordWithOutcome(ASSET, SITE),
        FlowCatalog.unassigned(EvidenceDocument.Association.SCOPE_LOST),
        FRAME,
        1);
    assertThat(inventory.entries().get(0).total()).isEqualTo(2);
    assertThat(evidence.snapshot(inventory.entries(), false)).hasSize(1);
    assertThat(health.snapshot().get(EvidenceDocument.Counter.LOST_ATTRIBUTION)).isEqualTo(1);
    assertThat(health.snapshot().get(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED))
        .isEqualTo(1);
    assertThat(evidence.snapshot(List.of(), false)).isEmpty();
    assertThat(health.snapshot().get(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED))
        .isEqualTo(2);
  }

  @Test
  void countsSaturateAndInvalidLimitsFailBeforeRecording() {
    var health = new FlowHealth();
    var evidence = new FlowEvidence(1, health::increment);
    var inventory = new Inventory();
    var context = FlowCatalog.unassigned(EvidenceDocument.Association.NO_SCOPE);
    evidence.record(
        ASSET.ref(),
        inventory.addWithOutcome(ASSET, SITE, Long.MAX_VALUE),
        context,
        FRAME,
        Long.MAX_VALUE);
    evidence.record(ASSET.ref(), inventory.recordWithOutcome(ASSET, SITE), context, FRAME, 1);
    assertThat(evidence.snapshot(inventory.entries(), false).get(0).count())
        .isEqualTo(Long.MAX_VALUE);
    assertThat(health.snapshot().get(EvidenceDocument.Counter.SATURATED_COUNTS)).isPositive();
    assertThatThrownBy(() -> new FlowEvidence(0, health::increment))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FlowEvidence(16385, health::increment))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                evidence.record(
                    ASSET.ref(), inventory.recordWithOutcome(ASSET, SITE), context, FRAME, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
