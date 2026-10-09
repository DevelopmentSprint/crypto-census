package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Key sizes and parameter sets read from public parameters, per key family. */
class KeyInfoParametersTest {

  private static KeyPair generate(String algorithm, Object init) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
    if (init instanceof Integer bits) {
      generator.initialize(bits);
    } else if (init instanceof ECGenParameterSpec spec) {
      generator.initialize(spec);
    }
    return generator.generateKeyPair();
  }

  @Test
  void sizesClassicPublicKeys() throws Exception {
    assertThat(KeyInfo.size(generate("RSA", 2048).getPublic())).isEqualTo(2048);
    assertThat(KeyInfo.size(generate("EC", new ECGenParameterSpec("secp384r1")).getPublic()))
        .isEqualTo(384);
    assertThat(KeyInfo.size(generate("DSA", 2048).getPublic())).isEqualTo(2048);
    assertThat(KeyInfo.size(generate("DH", 2048).getPublic())).isEqualTo(2048);
  }

  @Test
  void sizesNamedCurvesOfAnyCase() throws Exception {
    assertThat(KeyInfo.size(generate("X25519", null).getPublic())).isEqualTo(255);
    assertThat(KeyInfo.size(generate("X448", null).getPublic())).isEqualTo(448);
    assertThat(KeyInfo.size(generate("Ed25519", null).getPublic())).isEqualTo(255);
    assertThat(KeyInfo.size(generate("Ed448", null).getPublic())).isEqualTo(448);
  }

  @Test
  void readsCertificatesThroughTheirPublicKey() throws Exception {
    PublicKey rsa = generate("RSA", 2048).getPublic();
    assertThat(KeyInfo.size(new FakeCertificate(rsa))).isEqualTo(2048);
    assertThat(KeyInfo.size(new FakeCertificate(null))).isZero();
    PublicKey x25519 = generate("X25519", null).getPublic();
    assertThat(KeyInfo.refine("XDH", new FakeCertificate(x25519))).isEqualTo("X25519");
  }

  @Test
  void unknownOrMissingKeysAreSizeZero() {
    assertThat(KeyInfo.size(null)).isZero();
    assertThat(KeyInfo.size("not a key")).isZero();
    assertThat(KeyInfo.parameterSet(null)).isNull();
    assertThat(KeyInfo.parameterSet("not a key")).isNull();
  }

  @Test
  void sizesTripleDesUnderEitherName() {
    assertThat(KeyInfo.size(new SecretKeySpec(new byte[24], "DESede"))).isEqualTo(168);
    assertThat(KeyInfo.size(new SecretKeySpec(new byte[24], "TripleDES"))).isEqualTo(168);
    assertThat(KeyInfo.size(new SecretKeySpec(new byte[16], "HmacSHA256"))).isEqualTo(128);
  }

  @Test
  void refinesFamilyNamesToTheKeysParameterSet() throws Exception {
    PublicKey x448 = generate("X448", null).getPublic();
    PublicKey ed25519 = generate("Ed25519", null).getPublic();
    assertThat(KeyInfo.refine("XDH", x448)).isEqualTo("X448");
    assertThat(KeyInfo.refine("EdDSA", ed25519)).isEqualTo("Ed25519");
    // already specific, or a different family: left alone
    assertThat(KeyInfo.refine("X448", x448)).isEqualTo("X448");
    assertThat(KeyInfo.refine("ECDH", x448)).isEqualTo("ECDH");
    // nothing to refine with
    assertThat(KeyInfo.refine("RSA", generate("RSA", 2048).getPublic())).isEqualTo("RSA");
    assertThat(KeyInfo.refine("XDH", null)).isEqualTo("XDH");
    assertThat(KeyInfo.refine(null, x448)).isNull();
  }

  @Test
  void refinesPostQuantumFamiliesOnJdk24() throws Exception {
    assumeTrue(Runtime.version().feature() >= 24, "ML-KEM and ML-DSA need JDK 24");
    KeyPair mlKem = generate("ML-KEM", null);
    assertThat(KeyInfo.refine("ML-KEM", mlKem.getPublic())).isEqualTo("ML-KEM-768");
    assertThat(KeyInfo.size(mlKem.getPublic())).isZero();
    assertThat(KeyInfo.refine("ML-DSA", generate("ML-DSA", null).getPublic()))
        .isEqualTo("ML-DSA-65");
  }

  @Test
  void exportsOnlyReviewedJdkKeyClasses() {
    assertThat(KeyInfo.isExportableSoftwareKey(new SecretKeySpec(new byte[16], "AES"))).isTrue();
    assertThat(KeyInfo.isTokenKey(new SecretKeySpec(new byte[16], "AES"))).isFalse();
  }

  /** A certificate that is nothing but a public key. */
  private static final class FakeCertificate extends Certificate {
    private static final long serialVersionUID = 1L;
    private final transient PublicKey key;

    FakeCertificate(PublicKey key) {
      super("fake");
      this.key = key;
    }

    @Override
    public PublicKey getPublicKey() {
      return key;
    }

    @Override
    public byte[] getEncoded() {
      return new byte[0];
    }

    @Override
    public void verify(PublicKey key) {}

    @Override
    public void verify(PublicKey key, String sigProvider) {}

    @Override
    public String toString() {
      return "FakeCertificate";
    }
  }
}
