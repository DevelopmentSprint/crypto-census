package com.developmentsprint.cryptocensus.assessment;

import static org.assertj.core.api.Assertions.assertThat;

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
        List.of(),
        List.of(),
        List.of(),
        List.of(identities),
        List.of(),
        EvidenceDocument.zeroHealth());
  }
}
