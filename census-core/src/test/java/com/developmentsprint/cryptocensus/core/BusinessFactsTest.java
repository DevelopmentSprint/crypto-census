package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class BusinessFactsTest {
  private final Policy policy = new Policy();

  @Test
  void preservesLegacyBinarySignaturesAndExposesEffectiveRetention() throws Exception {
    Method add = Inventory.class.getMethod("add", CryptoAsset.class, Occurrence.class, long.class);
    Method record = Inventory.class.getMethod("record", CryptoAsset.class, Occurrence.class);
    assertThat(add.getReturnType()).isEqualTo(void.class);
    assertThat(record.getReturnType()).isEqualTo(void.class);
    Inventory inventory = Inventory.bounded(1, 1);
    CryptoAsset aes = CryptoAsset.cipher("AES/GCM/NoPadding", 256, "SunJCE", Operation.ENCRYPT);
    Occurrence first = new Occurrence("a.First", "encrypt", 1, null);
    assertThat(inventory.recordWithOutcome(aes, first))
        .isEqualTo(new Inventory.RecordOutcome(Inventory.Disposition.KEPT, first));
    Inventory.RecordOutcome folded = inventory.addWithOutcome(aes, Occurrence.UNKNOWN, 3);
    assertThat(folded.disposition()).isEqualTo(Inventory.Disposition.FOLDED);
    assertThat(folded.occurrence()).isEqualTo(Occurrence.OVERFLOW);
    Inventory.RecordOutcome dropped =
        inventory.recordWithOutcome(
            CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, null), Occurrence.UNKNOWN);
    assertThat(dropped.disposition()).isEqualTo(Inventory.Disposition.DROPPED);
    assertThat(dropped.occurrence()).isNull();
    assertThat(inventory.entries().get(0).total()).isEqualTo(4);
    assertThat(inventory.overflowedSites()).isEqualTo(3);
    assertThat(inventory.droppedObservations()).isEqualTo(1);
    assertThatThrownBy(() -> inventory.addWithOutcome(aes, first, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsImpossibleOutcomes() {
    assertThatThrownBy(() -> new Inventory.RecordOutcome(null, Occurrence.UNKNOWN))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new Inventory.RecordOutcome(Inventory.Disposition.KEPT, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Inventory.RecordOutcome(Inventory.Disposition.DROPPED, Occurrence.UNKNOWN))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Inventory.RecordOutcome(Inventory.Disposition.KEPT, Occurrence.OVERFLOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new Inventory.RecordOutcome(Inventory.Disposition.FOLDED, Occurrence.UNKNOWN))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void brokenRsaRetainsQuantumExposureWithoutChangingVerdict() {
    for (int size : new int[] {1024, 2048}) {
      CryptoAsset rsa =
          CryptoAsset.cipher(
              "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", size, "SunJCE", Operation.ENCRYPT);
      TechnicalFacts facts = policy.facts(rsa);
      assertThat(facts.assessment()).isEqualTo(policy.assess(rsa));
      assertThat(facts.quantumExposure()).isEqualTo(TechnicalFacts.QuantumExposure.VULNERABLE);
      assertThat(facts.role()).isEqualTo(TechnicalFacts.ObservedRole.CONFIDENTIALITY);
      assertThat(facts.version()).isEqualTo(Policy.FACTS_VERSION);
    }
    assertThat(
            policy
                .facts(CryptoAsset.cipher("RSA/ECB/PKCS1Padding", 1024, null))
                .assessment()
                .risk())
        .isEqualTo(Risk.BROKEN);
  }

  @Test
  void usesCanonicalClassicalKeyFamiliesEvenWhenTechnicalDetailsAreUnknown() {
    for (String name :
        new String[] {"SHA256withRSA", "SHA1withECDSA", "SHA256withDSA", "RSASSA-PSS", "ED25519"}) {
      TechnicalFacts facts =
          policy.facts(CryptoAsset.of(Primitive.SIGNATURE, name, 0, null, Operation.SIGN));
      assertThat(facts.quantumExposure())
          .as(name)
          .isEqualTo(TechnicalFacts.QuantumExposure.VULNERABLE);
      assertThat(facts.role()).isEqualTo(TechnicalFacts.ObservedRole.AUTHENTICITY);
    }
    for (String name : new String[] {"DH", "ECDH", "DHKEM", "X25519", "X448"}) {
      assertThat(
              policy
                  .facts(CryptoAsset.of(Primitive.KEY_AGREEMENT, name, 256, null))
                  .quantumExposure())
          .as(name)
          .isEqualTo(TechnicalFacts.QuantumExposure.VULNERABLE);
    }
    assertThat(
            policy
                .facts(CryptoAsset.of(Primitive.SIGNATURE, "SHA256withUnrecognized", 256, null))
                .quantumExposure())
        .isEqualTo(TechnicalFacts.QuantumExposure.NOT_ESTABLISHED);
  }

  @Test
  void neverInfersExchangeSecurityFromTlsSuiteOrRoleFromKeyGeneration() {
    TechnicalFacts tls = policy.facts(CryptoAsset.tls("TLSv1.3", "TLS_AES_256_GCM_SHA384"));
    assertThat(tls.assessment().risk()).isEqualTo(Risk.ACCEPTABLE);
    assertThat(tls.quantumExposure()).isEqualTo(TechnicalFacts.QuantumExposure.NOT_ESTABLISHED);
    assertThat(tls.role()).isEqualTo(TechnicalFacts.ObservedRole.NOT_ESTABLISHED);
    for (Primitive primitive :
        new Primitive[] {
          Primitive.KEY_GENERATION,
          Primitive.KEY_PAIR_GENERATION,
          Primitive.MESSAGE_DIGEST,
          Primitive.MAC
        }) {
      assertThat(policy.facts(CryptoAsset.of(primitive, "AES", 256, null)).role())
          .isEqualTo(TechnicalFacts.ObservedRole.NOT_ESTABLISHED);
    }
  }

  @Test
  void approvedPqcAndStrongSymmetricFactsRemainDistinctFromUnknownParameters() {
    for (CryptoAsset asset :
        new CryptoAsset[] {
          CryptoAsset.of(Primitive.KEM, "ML-KEM-768", 768, null),
          CryptoAsset.of(Primitive.SIGNATURE, "ML-DSA-65", 65, null),
          CryptoAsset.cipher("AES/GCM/NoPadding", 256, null)
        }) {
      assertThat(policy.facts(asset).quantumExposure())
          .isEqualTo(TechnicalFacts.QuantumExposure.RESISTANT);
    }
    assertThat(policy.facts(CryptoAsset.cipher("AES/GCM/NoPadding", 128, null)).quantumExposure())
        .isEqualTo(TechnicalFacts.QuantumExposure.NOT_ESTABLISHED);
    assertThat(policy.facts(CryptoAsset.of(Primitive.KEM, "Kyber768", 768, null)).quantumExposure())
        .isEqualTo(TechnicalFacts.QuantumExposure.NOT_ESTABLISHED);
  }
}
