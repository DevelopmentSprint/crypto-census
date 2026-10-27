package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Runtime ownership qualification; build coordinates alone never distinguish deployments. */
final class DeploymentOwnership {
  static final String GAP = "AMBIGUOUS_DEPLOYMENT_OWNERSHIP";

  private DeploymentOwnership() {}

  static Set<String> ambiguous(EvidenceDocument evidence) {
    if (evidence == null) {
      return Set.of();
    }
    Map<String, Set<String>> loaders = new HashMap<>();
    Map<String, Set<String>> deployments = new HashMap<>();
    for (EvidenceDocument.IdentityMapping identity : evidence.identities()) {
      if (!identity.applicationId().isEmpty()) {
        loaders
            .computeIfAbsent(identity.applicationId(), ignored -> new HashSet<>())
            .add(identity.loaderId());
        if (explicit(identity)) {
          deployments
              .computeIfAbsent(identity.applicationId(), ignored -> new HashSet<>())
              .add(identity.deploymentId());
        }
      }
    }
    Set<String> result = new HashSet<>();
    for (EvidenceDocument.IdentityMapping identity : evidence.identities()) {
      if (!identity.applicationId().isEmpty()
          && (loaders.get(identity.applicationId()).size() > 1
              || deployments.getOrDefault(identity.applicationId(), Set.of()).size() > 1)
          && !explicit(identity)) {
        result.add(identity.id());
      }
    }
    return Set.copyOf(result);
  }

  private static boolean explicit(EvidenceDocument.IdentityMapping identity) {
    return "source-catalog:EXPLICIT_DEPLOYMENT".equals(identity.provenance());
  }
}
