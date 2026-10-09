package com.developmentsprint.cryptocensus.core;

import java.util.Locale;

/** Risk classification of a cryptographic asset, ordered by severity. */
public enum Risk {
  /**
   * Quantum-resistant (ML-KEM, ML-DSA, 256-bit symmetric keys, SHA-384+). Not the same as CNSA 2.0;
   * see {@link Assessment#cnsa2()}.
   */
  QUANTUM_SAFE(0),
  /**
   * Classically secure and not broken by Shor, but not quantum-safe grade (AES-128, SHA-256, HMAC).
   */
  ACCEPTABLE(1),
  /**
   * Not recognized, or not enough was observed to judge; needs a human look. See {@code
   * failOnUnknown}.
   */
  UNKNOWN(1),
  /**
   * Public-key cryptography that a cryptographically relevant quantum computer breaks (RSA, ECC,
   * DH).
   */
  QUANTUM_VULNERABLE(2),
  /**
   * Deprecated, legacy-use-only or misused today, independent of quantum (ECB, SHA-1 digests, TDEA
   * decryption).
   */
  WEAK(3),
  /**
   * Practically broken or disallowed for new use (DES, RC4, MD5, TDEA encryption, RSA &lt; 2048,
   * TLS 1.0).
   */
  BROKEN(4);

  private final int severity;

  Risk(int severity) {
    this.severity = severity;
  }

  /**
   * Severity used for {@code failOn} thresholds; {@link #UNKNOWN} shares {@link #ACCEPTABLE}'s.
   *
   * @return 0 (quantum-safe) to 4 (broken)
   */
  public int severity() {
    return severity;
  }

  /**
   * Whether this risk meets or exceeds {@code threshold}.
   *
   * @param threshold the lowest failing risk
   * @return true if this risk is at least as severe
   */
  public boolean isAtLeast(Risk threshold) {
    return severity >= threshold.severity;
  }

  /** Parses {@code quantum-vulnerable}, {@code QUANTUM_VULNERABLE}, etc. */
  public static Risk parse(String value) {
    return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
  }

  /**
   * Lower-case, hyphenated label as used in CBOMs and configuration.
   *
   * @return e.g. {@code quantum-vulnerable}
   */
  public String label() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
