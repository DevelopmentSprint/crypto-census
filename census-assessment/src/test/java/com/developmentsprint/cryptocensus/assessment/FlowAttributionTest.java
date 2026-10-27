package com.developmentsprint.cryptocensus.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Operation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowAttributionTest {
  private static final String NONCE = "12345678-1234-1234-1234-123456789abc";
  private static final ContextModel.Subject OUTER = new ContextModel.Subject("app", "archive");
  private static final ContextModel.Subject INNER = new ContextModel.Subject("app", "helper");
  private static final ContextModel.Binding FRAME =
      new ContextModel.Binding(
          "example.Archive", "save", "()V", "example:archive", ContextModel.Origin.MAIN);
  private static final CryptoAsset ASSET =
      CryptoAsset.cipher("AES/GCM/NoPadding", 256, "provider", Operation.ENCRYPT);
  private static final Occurrence SITE = new Occurrence("example.Archive", "save", 10, null);

  private static AssessmentInputs.Run run(
      List<ContextModel.Subject> path,
      EvidenceDocument.Association association,
      boolean supported,
      boolean unresolved,
      boolean countGap) {
    return run(path, association, supported, unresolved, countGap, value -> value);
  }

  private static AssessmentInputs.Run run(
      List<ContextModel.Subject> path,
      EvidenceDocument.Association association,
      boolean supported,
      boolean unresolved,
      boolean countGap,
      java.util.function.UnaryOperator<EvidenceDocument> edit) {
    Inventory inventory = new Inventory();
    inventory.add(ASSET, SITE, countGap ? 4 : 3);
    byte[] cbom =
        Cbom.toJson(
                inventory,
                new Cbom.Metadata("display", Map.of(EvidenceDocument.RUN_PROPERTY, NONCE)))
            .getBytes(StandardCharsets.UTF_8);
    EvidenceDocument.IdentityMapping identity =
        new EvidenceDocument.IdentityMapping(
            "source",
            "deployment",
            "loader",
            "example:archive",
            unresolved ? "" : "app",
            unresolved
                ? EvidenceDocument.IdentityStatus.UNRESOLVED
                : EvidenceDocument.IdentityStatus.RESOLVED,
            "source-catalog");
    List<EvidenceDocument.DiscoveredFlow> flows =
        path.stream()
            .distinct()
            .map(
                subject ->
                    new EvidenceDocument.DiscoveredFlow(
                        "source",
                        new ContextModel.FlowDefinition(
                            subject, subject.flowId(), List.of(), List.of(FRAME))))
            .toList();
    EvidenceDocument.ScopeContext scope =
        new EvidenceDocument.ScopeContext(
            "source",
            path.stream().map(subject -> new EvidenceDocument.ScopeRef("source", subject)).toList(),
            association);
    Map<EvidenceDocument.Counter, Long> health =
        new java.util.EnumMap<>(EvidenceDocument.zeroHealth());
    if (countGap) {
      health.put(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED, 1L);
    }
    EvidenceDocument evidence =
        new EvidenceDocument(
            1,
            NONCE,
            CanonicalJson.hash(cbom),
            flows,
            List.of(new EvidenceDocument.Observation(ASSET.ref(), SITE, scope, 3, FRAME)),
            List.of(),
            List.of(identity),
            supported
                ? List.of(
                    new EvidenceDocument.Capability(
                        "annotation-scopes",
                        EvidenceDocument.CapabilityStatus.SUPPORTED,
                        "synchronous"))
                : List.of(),
            health);
    return AssessmentInputs.load(
            List.of(
                new AssessmentInputs.Input(
                    cbom, EvidenceCodec.encode(edit.apply(evidence)), "test", "test")))
        .get(0);
  }

  private static ContextDocument saved(ContextModel.Subject... subjects) {
    return new ContextDocument(
        1,
        java.util.Arrays.stream(subjects)
            .map(
                subject ->
                    new ContextDocument.Flow(
                        subject, subject.flowId(), ContextDocument.State.ACTIVE, List.of(FRAME)))
            .toList(),
        List.of(),
        List.of(),
        List.of());
  }

  private static EvidenceDocument revised(
      EvidenceDocument value,
      List<EvidenceDocument.Observation> observations,
      List<EvidenceDocument.DiscoveredFlow> flows,
      List<EvidenceDocument.IdentityMapping> identities,
      Map<EvidenceDocument.Counter, Long> health) {
    return new EvidenceDocument(
        1,
        value.runId(),
        value.cbomHash(),
        flows,
        observations,
        value.handshakes(),
        identities,
        value.capabilities(),
        health);
  }

  @Test
  void ambiguousDeploymentCannotAcquireSavedBindingBySharedApplicationId() {
    var run =
        run(
            List.of(),
            EvidenceDocument.Association.NO_SCOPE,
            true,
            false,
            false,
            value ->
                revised(
                    value,
                    value.observations(),
                    value.flows(),
                    List.of(
                        value.identities().get(0),
                        new EvidenceDocument.IdentityMapping(
                            "other",
                            "other-deployment",
                            "other-loader",
                            "example:archive",
                            "app",
                            EvidenceDocument.IdentityStatus.RESOLVED,
                            "source-catalog:GENERATED_BUILD")),
                    value.health()));
    var attributed = FlowAttribution.attribute(run, saved(OUTER));
    assertThat(attributed.gaps()).contains(DeploymentOwnership.GAP);
    assertThat(attributed.uses())
        .allSatisfy(
            use -> {
              assertThat(use.subject()).isNull();
              assertThat(use.association()).isEqualTo(FlowAttribution.Association.NO_SCOPE);
            });
    assertThat(attributed.bindings())
        .allSatisfy(
            binding ->
                assertThat(binding.status()).isEqualTo(FlowAttribution.BindingStatus.AMBIGUOUS));
  }

  @Test
  void zeroCountInventoryRemainsVisibleAsUnobservedTechnicalEvidence() {
    Inventory inventory = new Inventory();
    inventory.add(ASSET, SITE, 0);
    byte[] cbom =
        Cbom.toJson(
                inventory,
                new Cbom.Metadata("display", Map.of(EvidenceDocument.RUN_PROPERTY, NONCE)))
            .getBytes(StandardCharsets.UTF_8);
    EvidenceDocument evidence =
        new EvidenceDocument(
            1,
            NONCE,
            CanonicalJson.hash(cbom),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            EvidenceDocument.zeroHealth());
    AssessmentInputs.Run input =
        AssessmentInputs.load(
                List.of(
                    new AssessmentInputs.Input(
                        cbom, EvidenceCodec.encode(evidence), "test", "test")))
            .get(0);
    FlowAttribution.Result result = FlowAttribution.attribute(input, ContextDocument.empty());
    assertThat(result.uses())
        .singleElement()
        .satisfies(
            use -> {
              assertThat(use.count()).isZero();
              assertThat(use.subject()).isNull();
            });
    assertThat(result.gaps()).contains("NO_OBSERVATION");
  }

  @Test
  void integrityOrRestorationFailureInvalidatesEarlierScopedRows() {
    for (EvidenceDocument.Counter counter :
        List.of(
            EvidenceDocument.Counter.SCOPE_INTEGRITY,
            EvidenceDocument.Counter.SCOPE_RESTORE_FAILURES)) {
      AssessmentInputs.Run input =
          run(
              List.of(OUTER),
              EvidenceDocument.Association.SYNCHRONOUS_SCOPE,
              true,
              false,
              false,
              value -> {
                Map<EvidenceDocument.Counter, Long> health =
                    new java.util.EnumMap<>(value.health());
                health.put(counter, 1L);
                return revised(
                    value, value.observations(), value.flows(), value.identities(), health);
              });
      FlowAttribution.Result result = FlowAttribution.attribute(input, ContextDocument.empty());
      assertThat(result.uses())
          .singleElement()
          .satisfies(
              use -> {
                assertThat(use.subject()).isNull();
                assertThat(use.association()).isEqualTo(FlowAttribution.Association.UNVERIFIED);
                assertThat(use.identityId()).isEqualTo("source");
              });
    }
  }

  @Test
  void deploymentRowsRetainIdentityAndShareAnEventBucketForBusinessInclusiveDeduplication() {
    AssessmentInputs.Run input =
        run(
            List.of(OUTER),
            EvidenceDocument.Association.SYNCHRONOUS_SCOPE,
            true,
            false,
            false,
            value -> {
              EvidenceDocument.IdentityMapping second =
                  new EvidenceDocument.IdentityMapping(
                      "source-2",
                      "deployment-2",
                      "loader-2",
                      "example:archive",
                      "app",
                      EvidenceDocument.IdentityStatus.RESOLVED,
                      "explicit-mapping");
              EvidenceDocument.DiscoveredFlow flow =
                  new EvidenceDocument.DiscoveredFlow(
                      "source-2", value.flows().get(0).definition());
              EvidenceDocument.ScopeContext scope =
                  new EvidenceDocument.ScopeContext(
                      "source-2",
                      List.of(
                          new EvidenceDocument.ScopeRef("source", OUTER),
                          new EvidenceDocument.ScopeRef("source-2", OUTER),
                          new EvidenceDocument.ScopeRef("source-2", OUTER)),
                      EvidenceDocument.Association.SYNCHRONOUS_SCOPE);
              return revised(
                  value,
                  List.of(new EvidenceDocument.Observation(ASSET.ref(), SITE, scope, 3, FRAME)),
                  List.of(value.flows().get(0), flow),
                  List.of(value.identities().get(0), second),
                  value.health());
            });
    FlowAttribution.Result result = FlowAttribution.attribute(input, ContextDocument.empty());
    assertThat(result.uses()).hasSize(2);
    assertThat(result.uses())
        .extracting(FlowAttribution.Use::identityId)
        .containsExactly("source", "source-2");
    assertThat(result.uses())
        .extracting(FlowAttribution.Use::bucketId)
        .containsOnly(result.uses().get(0).bucketId());
    assertThat(result.uses())
        .filteredOn(FlowAttribution.Use::direct)
        .singleElement()
        .satisfies(use -> assertThat(use.identityId()).isEqualTo("source-2"));
  }

  @Test
  void equivalentBucketsAggregateBeforeInclusiveExpansion() {
    AssessmentInputs.Run normal =
        run(List.of(OUTER), EvidenceDocument.Association.SYNCHRONOUS_SCOPE, true, false, false);
    AssessmentInputs.Run split =
        run(
            List.of(OUTER),
            EvidenceDocument.Association.SYNCHRONOUS_SCOPE,
            true,
            false,
            false,
            value -> {
              EvidenceDocument.Observation original = value.observations().get(0);
              return revised(
                  value,
                  List.of(
                      new EvidenceDocument.Observation(
                          ASSET.ref(), SITE, original.context(), 2, FRAME),
                      new EvidenceDocument.Observation(
                          ASSET.ref(), SITE, original.context(), 1, FRAME)),
                  value.flows(),
                  value.identities(),
                  value.health());
            });
    assertThat(FlowAttribution.attribute(split, ContextDocument.empty()).uses())
        .isEqualTo(FlowAttribution.attribute(normal, ContextDocument.empty()).uses());
  }

  @Test
  void recursionCountsEachApplicableSubjectOnceWithOnlyActiveFlowDirect() {
    FlowAttribution.Result result =
        FlowAttribution.attribute(
            run(
                List.of(OUTER, INNER, INNER),
                EvidenceDocument.Association.SYNCHRONOUS_SCOPE,
                true,
                false,
                false),
            ContextDocument.empty());
    assertThat(result.uses()).hasSize(2);
    assertThat(result.uses())
        .filteredOn(FlowAttribution.Use::direct)
        .singleElement()
        .satisfies(
            use -> {
              assertThat(use.subject()).isEqualTo(INNER);
              assertThat(use.count()).isEqualTo(3);
              assertThat(use.path()).hasSize(3);
            });
    assertThat(result.uses())
        .filteredOn(use -> !use.direct())
        .singleElement()
        .satisfies(
            use -> {
              assertThat(use.subject()).isEqualTo(OUTER);
              assertThat(use.count()).isEqualTo(3);
            });
  }

  @Test
  void unsupportedScopesRetainTechnicalEvidenceWithoutBusinessAssociation() {
    FlowAttribution.Result result =
        FlowAttribution.attribute(
            run(
                List.of(OUTER),
                EvidenceDocument.Association.SYNCHRONOUS_SCOPE,
                false,
                false,
                false),
            ContextDocument.empty());
    assertThat(result.uses())
        .singleElement()
        .satisfies(
            use -> {
              assertThat(use.subject()).isNull();
              assertThat(use.association()).isEqualTo(FlowAttribution.Association.UNVERIFIED);
              assertThat(use.assetRef()).isEqualTo(ASSET.ref());
              assertThat(use.count()).isEqualTo(3);
            });
    assertThat(result.gaps()).contains("SCOPE_ASSOCIATION_NOT_ESTABLISHED");
  }

  @Test
  void exactSavedBindingRequiresResolvedIdentityAndUniqueSubject() {
    AssessmentInputs.Run unscoped =
        run(List.of(), EvidenceDocument.Association.NO_SCOPE, true, false, false);
    FlowAttribution.Result exact = FlowAttribution.attribute(unscoped, saved(OUTER));
    assertThat(exact.uses())
        .singleElement()
        .satisfies(
            use -> {
              assertThat(use.subject()).isEqualTo(OUTER);
              assertThat(use.association()).isEqualTo(FlowAttribution.Association.EXPLICIT_BINDING);
            });
    FlowAttribution.Result ambiguous = FlowAttribution.attribute(unscoped, saved(OUTER, INNER));
    assertThat(ambiguous.uses())
        .singleElement()
        .satisfies(use -> assertThat(use.subject()).isNull());
    assertThat(ambiguous.bindings())
        .allSatisfy(
            binding ->
                assertThat(binding.status()).isEqualTo(FlowAttribution.BindingStatus.AMBIGUOUS));
    assertThat(ambiguous.gaps()).contains("AMBIGUOUS_SAVED_BINDING");
    FlowAttribution.Result unresolved =
        FlowAttribution.attribute(
            run(List.of(), EvidenceDocument.Association.NO_SCOPE, true, true, false), saved(OUTER));
    assertThat(unresolved.uses())
        .singleElement()
        .satisfies(use -> assertThat(use.subject()).isNull());
    assertThat(unresolved.bindings())
        .singleElement()
        .satisfies(
            binding ->
                assertThat(binding.status()).isEqualTo(FlowAttribution.BindingStatus.NOT_OBSERVED));
  }

  @Test
  void lostScopeCannotBeRecoveredThroughSavedHelperBinding() {
    FlowAttribution.Result result =
        FlowAttribution.attribute(
            run(List.of(), EvidenceDocument.Association.SCOPE_LOST, true, false, false),
            saved(OUTER));
    assertThat(result.uses())
        .singleElement()
        .satisfies(
            use -> {
              assertThat(use.subject()).isNull();
              assertThat(use.association()).isEqualTo(FlowAttribution.Association.SCOPE_LOST);
            });
  }

  @Test
  void unmatchedOrIneligibleBindingsNeverFollowSimilarNames() {
    ContextModel.Binding changed =
        new ContextModel.Binding(
            FRAME.className(), FRAME.method(), "(I)V", FRAME.sourceArtifact(), FRAME.origin());
    ContextModel.Binding library =
        new ContextModel.Binding(
            FRAME.className(),
            FRAME.method(),
            FRAME.descriptor(),
            FRAME.sourceArtifact(),
            ContextModel.Origin.LIBRARY);
    ContextDocument context =
        new ContextDocument(
            1,
            List.of(
                new ContextDocument.Flow(
                    OUTER, "Archive", ContextDocument.State.ACTIVE, List.of(changed, library))),
            List.of(),
            List.of(),
            List.of());
    FlowAttribution.Result result =
        FlowAttribution.attribute(
            run(List.of(), EvidenceDocument.Association.NO_SCOPE, true, false, false), context);
    assertThat(result.uses()).singleElement().satisfies(use -> assertThat(use.subject()).isNull());
    assertThat(result.bindings())
        .extracting(FlowAttribution.BindingReview::status)
        .containsExactlyInAnyOrder(
            FlowAttribution.BindingStatus.NOT_OBSERVED, FlowAttribution.BindingStatus.INELIGIBLE);
  }

  @Test
  void unreconciledCountsProduceUnknownBucketWithoutInventingMissingDelta() {
    FlowAttribution.Result result =
        FlowAttribution.attribute(
            run(List.of(OUTER), EvidenceDocument.Association.SYNCHRONOUS_SCOPE, true, false, true),
            ContextDocument.empty());
    assertThat(result.uses()).hasSize(2);
    assertThat(result.uses())
        .filteredOn(use -> use.subject() == null)
        .singleElement()
        .satisfies(use -> assertThat(use.count()).isNull());
    assertThat(result.gaps()).contains("UNRECONCILED_OBSERVATION_COUNTS");
  }
}
