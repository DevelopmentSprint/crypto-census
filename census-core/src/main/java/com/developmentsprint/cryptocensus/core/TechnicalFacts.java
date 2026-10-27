package com.developmentsprint.cryptocensus.core;

import java.util.Objects;

/** Core-owned technical facts, independent of business declarations and final verdict severity. */
public record TechnicalFacts(
    Assessment assessment, QuantumExposure quantumExposure, ObservedRole role, String version) {
  /** Requires a complete canonical technical result and facts version. */
  public TechnicalFacts {
    Objects.requireNonNull(assessment, "assessment");
    Objects.requireNonNull(quantumExposure, "quantumExposure");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(version, "version");
  }

  /**
   * Vulnerability to cryptanalytic quantum attacks, without overwriting current technical flaws.
   */
  public enum QuantumExposure {
    VULNERABLE,
    RESISTANT,
    NOT_ESTABLISHED
  }

  /** The observed primitive's protection role, not a claim about business data or completed use. */
  public enum ObservedRole {
    CONFIDENTIALITY,
    AUTHENTICITY,
    NOT_ESTABLISHED
  }
}
