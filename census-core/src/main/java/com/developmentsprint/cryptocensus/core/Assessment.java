package com.developmentsprint.cryptocensus.core;

/**
 * The policy's verdict on an asset.
 *
 * @param risk                     risk class
 * @param reason                   one-line human explanation
 * @param nistQuantumSecurityLevel CycloneDX {@code nistQuantumSecurityLevel}: {@code 0} for
 *                                 algorithms broken by a quantum computer, {@code 1}-{@code 5} for
 *                                 the NIST PQC security categories, {@code -1} when not established
 * @param cnsa2                    {@code true} only for the specific algorithms and parameter sets
 *                                 NSA CNSA 2.0 lists (AES-256, SHA-384/512, ML-KEM-1024, ML-DSA-87,
 *                                 LMS/XMSS); "quantum-safe" alone does not imply CNSA 2.0
 */
public record Assessment(Risk risk, String reason, int nistQuantumSecurityLevel, boolean cnsa2) {

    static Assessment of(Risk risk, String reason) {
        return new Assessment(risk, reason, risk == Risk.QUANTUM_VULNERABLE ? 0 : -1, false);
    }

    static Assessment of(Risk risk, String reason, int nistLevel) {
        return new Assessment(risk, reason, nistLevel, false);
    }

    static Assessment cnsa2(Risk risk, String reason, int nistLevel) {
        return new Assessment(risk, reason, nistLevel, true);
    }
}
