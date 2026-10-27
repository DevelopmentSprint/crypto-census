package com.developmentsprint.cryptocensus.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeploymentOwnershipTest {
  @Test
  void generatedBuildLabelsDoNotInventDeploymentsInsideOneLoader() {
    var one = identity("one", "loader", "maven:one", "app", "GENERATED_BUILD");
    var two = identity("two", "loader", "maven:two", "app", "GENERATED_BUILD");
    assertThat(DeploymentOwnership.ambiguous(evidence(one, two))).isEmpty();
    var otherLoader = identity("three", "other", "maven:two", "app", "GENERATED_BUILD");
    assertThat(DeploymentOwnership.ambiguous(evidence(one, otherLoader)))
        .containsExactlyInAnyOrder("one", "three");
  }

  @Test
  void explicitDeploymentsPreserveTheirIdentityAndRevealUnknownSharedOwnership() {
    var one = identity("one", "loader", "deployment-one", "app", "EXPLICIT_DEPLOYMENT");
    var two = identity("two", "loader", "deployment-two", "app", "EXPLICIT_DEPLOYMENT");
    var unknown = identity("unknown", "loader", "build", "app", "UNSPECIFIED");
    assertThat(DeploymentOwnership.ambiguous(evidence(one, two, unknown)))
        .containsExactly("unknown");
    assertThat(DeploymentOwnership.ambiguous(evidence(one, unknown))).isEmpty();
    assertThat(DeploymentOwnership.ambiguous(null)).isEmpty();
    assertThat(
            DeploymentOwnership.ambiguous(
                evidence(identity("unresolved", "other", "none", "", "UNSPECIFIED"), one)))
        .isEmpty();
  }

  @Test
  void bystanderIdentityCannotInventCompetingOwnership() {
    var base =
        evidence(
            identity("one", "application", "unspecified", "app", "UNSPECIFIED"),
            identity("two", "container", "unspecified", "app", "UNSPECIFIED"));
    var oneFlow =
        new EvidenceDocument(
            1,
            base.runId(),
            base.cbomHash(),
            base.flows().subList(0, 1),
            List.of(),
            List.of(),
            base.identities(),
            List.of(),
            base.health());
    assertThat(DeploymentOwnership.ambiguous(oneFlow)).isEmpty();
    for (ContextModel.Origin origin : ContextModel.Origin.values()) {
      var frame = new ContextModel.Binding("example.Frame", "run", "()V", "artifact:two", origin);
      var observation =
          new EvidenceDocument.Observation(
              "asset",
              new com.developmentsprint.cryptocensus.core.Occurrence(
                  "example.Frame", "run", 1, null),
              new EvidenceDocument.ScopeContext(
                  "two", List.of(), EvidenceDocument.Association.NO_SCOPE),
              1,
              frame);
      var withFrame =
          new EvidenceDocument(
              1,
              base.runId(),
              base.cbomHash(),
              oneFlow.flows(),
              List.of(observation),
              List.of(),
              base.identities(),
              List.of(),
              base.health());
      assertThat(DeploymentOwnership.ambiguous(withFrame).isEmpty())
          .isEqualTo(origin != ContextModel.Origin.MAIN && origin != ContextModel.Origin.TEST);
    }
  }

  private static EvidenceDocument.IdentityMapping identity(
      String id, String loader, String deployment, String application, String qualification) {
    return new EvidenceDocument.IdentityMapping(
        id,
        deployment,
        loader,
        "artifact:" + id,
        application,
        application.isEmpty()
            ? EvidenceDocument.IdentityStatus.UNRESOLVED
            : EvidenceDocument.IdentityStatus.RESOLVED,
        "source-catalog:" + qualification);
  }

  private static EvidenceDocument evidence(EvidenceDocument.IdentityMapping... identities) {
    return new EvidenceDocument(
        1,
        "12345678-1234-1234-1234-123456789abc",
        "a".repeat(64),
        java.util.Arrays.stream(identities)
            .filter(identity -> !identity.applicationId().isEmpty())
            .map(
                identity ->
                    new EvidenceDocument.DiscoveredFlow(
                        identity.id(),
                        new ContextModel.FlowDefinition(
                            new ContextModel.Subject(identity.applicationId(), "flow"),
                            "Flow",
                            List.of(),
                            List.of())))
            .toList(),
        List.of(),
        List.of(),
        List.of(identities),
        List.of(),
        EvidenceDocument.zeroHealth());
  }
}
