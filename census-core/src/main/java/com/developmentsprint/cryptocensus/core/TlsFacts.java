package com.developmentsprint.cryptocensus.core;

import java.util.List;
import java.util.Objects;

/**
 * Canonical TLS facts keep current exchange, authentication and inherited PSK evidence separate.
 */
public record TlsFacts(
    EvidenceDocument.Outcome outcome,
    TechnicalFacts.QuantumExposure selectedKeyExchangeExposure,
    TechnicalFacts.QuantumExposure confidentialityExposure,
    TechnicalFacts.QuantumExposure localAuthenticationExposure,
    TechnicalFacts.QuantumExposure peerAuthenticationExposure,
    EvidenceDocument.Flag resumed,
    EvidenceDocument.Flag freshKeyExchange,
    Lineage lineage,
    TechnicalFacts.QuantumExposure pskOriginExposure,
    List<String> limitations,
    String version) {
  /** Requires complete independent facts and makes the limitations immutable. */
  public TlsFacts {
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(selectedKeyExchangeExposure, "selected exchange");
    Objects.requireNonNull(confidentialityExposure, "confidentiality");
    Objects.requireNonNull(localAuthenticationExposure, "local authentication");
    Objects.requireNonNull(peerAuthenticationExposure, "peer authentication");
    Objects.requireNonNull(resumed, "resumed");
    Objects.requireNonNull(freshKeyExchange, "fresh exchange");
    Objects.requireNonNull(lineage, "lineage");
    Objects.requireNonNull(pskOriginExposure, "PSK origin");
    limitations = List.copyOf(limitations);
    Objects.requireNonNull(version, "version");
  }

  /** Verified means the complete recorded in-run ancestry exists, not that it is quantum safe. */
  public enum Lineage {
    NOT_APPLICABLE,
    VERIFIED,
    UNKNOWN
  }
}
