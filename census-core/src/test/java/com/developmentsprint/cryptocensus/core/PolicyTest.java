package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PolicyTest {

  private final Policy policy = new Policy();

  private Assessment assess(Primitive primitive, String algorithm, int keySize, String op) {
    Operation operation = op == null || op.isBlank() ? null : Operation.valueOf(op);
    CryptoAsset asset =
        primitive == Primitive.CIPHER
            ? CryptoAsset.cipher(algorithm, keySize, "SunJCE", operation)
            : CryptoAsset.of(primitive, algorithm, keySize, "SUN", operation);
    return policy.assess(asset);
  }

  @ParameterizedTest(name = "{0} {1} ({2} bits, {3}) -> {4}")
  @CsvSource(
      nullValues = "-",
      value = {
        // primitive,          algorithm,                 keySize, op,      expected
        "CIPHER,               AES/GCM/NoPadding,         256,     ENCRYPT, QUANTUM_SAFE",
        "CIPHER,               AES/GCM/NoPadding,         128,     ENCRYPT, ACCEPTABLE",
        "CIPHER,               AES/GCM/NoPadding,         0,       ENCRYPT, ACCEPTABLE",
        "CIPHER,               AES/ECB/PKCS5Padding,      256,     ENCRYPT, WEAK",
        "CIPHER,               AES_256/GCM/NoPadding,     0,       ENCRYPT, QUANTUM_SAFE",
        "CIPHER,               DES/CBC/PKCS5Padding,      56,      ENCRYPT, BROKEN",
        "CIPHER,               DESede/CBC/PKCS5Padding,   168,     ENCRYPT, BROKEN",
        "CIPHER,               DESede/CBC/PKCS5Padding,   168,     DECRYPT, WEAK",
        "CIPHER,               RC4,                       128,     ENCRYPT, BROKEN",
        "CIPHER,               RSA/ECB/OAEPPadding,       3072,    ENCRYPT, QUANTUM_VULNERABLE",
        "CIPHER,               RSA/ECB/PKCS1Padding,      2048,    ENCRYPT, BROKEN",
        "CIPHER,               RSA/ECB/PKCS1Padding,      2048,    DECRYPT, BROKEN", // SP 800-131A
        // s6:
        // decryption
        // too
        "CIPHER,               RSA/ECB/NoPadding,         2048,    ENCRYPT, BROKEN",
        "CIPHER,               RSA/ECB/OAEPPadding,       1024,    ENCRYPT, BROKEN",
        "CIPHER,               ChaCha20-Poly1305,         256,     ENCRYPT, QUANTUM_SAFE",
        "CIPHER,               Camellia/GCM/NoPadding,    256,     ENCRYPT, QUANTUM_SAFE",
        "CIPHER,               PBEWithHmacSHA256AndAES_256, 0,     ENCRYPT, UNKNOWN",
        "CIPHER,               PBEWithMD5AndDES,          0,       ENCRYPT, BROKEN",
        "SIGNATURE,            SHA256withRSA,             2048,    SIGN,    QUANTUM_VULNERABLE",
        "SIGNATURE,            SHA1withRSA,               2048,    SIGN,    BROKEN",
        "SIGNATURE,            SHA1withRSA,               2048,    VERIFY,  WEAK",
        "SIGNATURE,            SHA256withECDSA,           256,     SIGN,    QUANTUM_VULNERABLE",
        "SIGNATURE,            SHA256withECDSAinP1363Format, 256,  SIGN,    QUANTUM_VULNERABLE",
        "SIGNATURE,            RSASSA-PSS,                3072,    SIGN,    UNKNOWN", // hash unseen
        "SIGNATURE,            RSASSA-PSS,                1024,    SIGN,    BROKEN",
        "SIGNATURE,            SHA256withRSASSA-PSS,      3072,    SIGN,    QUANTUM_VULNERABLE",
        "SIGNATURE,            SHA1withRSASSA-PSS,        2048,    SIGN,    BROKEN",
        "SIGNATURE,            SHA1withRSASSA-PSS,        2048,    VERIFY,  WEAK",
        "SIGNATURE,            SHA256withDSA,             2048,    SIGN,    BROKEN", // FIPS 186-5:
        // no new DSA
        // signatures
        "SIGNATURE,            Ed25519,                   255,     SIGN,    QUANTUM_VULNERABLE",
        "SIGNATURE,            ML-DSA-65,                 0,       SIGN,    QUANTUM_SAFE",
        "SIGNATURE,            SLH-DSA-SHA2-128s,         0,       SIGN,    QUANTUM_SAFE",
        "SIGNATURE,            SHA999withRSA,             2048,    SIGN,    UNKNOWN",
        "KEY_AGREEMENT,        X25519,                    255,     -,       QUANTUM_VULNERABLE",
        "KEY_AGREEMENT,        ECDH,                      256,     -,       QUANTUM_VULNERABLE",
        "KEY_AGREEMENT,        DH,                        1024,    -,       BROKEN",
        "KEM,                  ML-KEM-768,                0,       -,       QUANTUM_SAFE",
        "KEM,                  X25519MLKEM768,            0,       -,       QUANTUM_SAFE",
        "KEM,                  DHKEM,                     256,     -,       QUANTUM_VULNERABLE",
        "KEM,                  Kyber768,                  0,       -,       UNKNOWN",
        "KEY_PAIR_GENERATION,  RSA,                       4096,    -,       QUANTUM_VULNERABLE",
        "KEY_PAIR_GENERATION,  DSA,                       2048,    -,       BROKEN",
        "KEY_PAIR_GENERATION,  EC,                        192,     -,       BROKEN",
        "KEY_PAIR_GENERATION,  ML-KEM,                    0,       -,       QUANTUM_SAFE",
        "MESSAGE_DIGEST,       MD5,                       0,       -,       BROKEN",
        "MESSAGE_DIGEST,       SHA-1,                     0,       -,       WEAK",
        "MESSAGE_DIGEST,       SHA-256,                   0,       -,       ACCEPTABLE",
        "MESSAGE_DIGEST,       SHA-384,                   0,       -,       QUANTUM_SAFE",
        "MESSAGE_DIGEST,       SHA3-512,                  0,       -,       QUANTUM_SAFE",
        "MAC,                  HmacSHA256,                256,     -,       ACCEPTABLE",
        "MAC,                  HmacSHA512,                512,     -,       QUANTUM_SAFE",
        "MAC,                  HmacSHA512,                0,       -,       UNKNOWN",
        "MAC,                  HmacMD5,                   128,     -,       WEAK",
        "KEY_GENERATION,       AES,                       256,     -,       QUANTUM_SAFE",
        "KEY_GENERATION,       DESede,                    168,     -,       BROKEN",
        "MESSAGE_DIGEST,       Whirlpool,                 0,       -,       UNKNOWN",
      })
  void classifiesAlgorithms(
      Primitive primitive, String algorithm, int keySize, String op, Risk expected) {
    Assessment verdict = assess(primitive, algorithm, keySize, op);

    assertThat(verdict.risk()).as(verdict.reason()).isEqualTo(expected);
    assertThat(verdict.reason()).isNotBlank();
  }

  // ---- regressions from the external audit -------------------------------------------------

  @ParameterizedTest(name = "bare {0} uses the provider default (ECB) -> weak")
  @CsvSource({"AES", "Camellia"})
  void bareBlockCipherIsTreatedAsEcb(String algorithm) {
    Assessment verdict = assess(Primitive.CIPHER, algorithm, 256, "ENCRYPT");
    assertThat(verdict.risk()).isEqualTo(Risk.WEAK);
    assertThat(verdict.reason()).contains("ECB");
  }

  @ParameterizedTest(name = "{0} {1} resolves to {2}")
  @CsvSource({
    "MESSAGE_DIGEST, 1.2.840.113549.2.5,        BROKEN",
    "MESSAGE_DIGEST, OID.1.2.840.113549.2.5,    BROKEN",
    "MESSAGE_DIGEST, SHA,                       WEAK",
    "MESSAGE_DIGEST, 1.3.14.3.2.26,             WEAK",
    "SIGNATURE,      SHA-1withRSA,              BROKEN",
    "SIGNATURE,      1.2.840.113549.1.1.5,      BROKEN",
    "SIGNATURE,      SHA-256withRSA,            QUANTUM_VULNERABLE",
    "MAC,            1.2.840.113549.2.9,        ACCEPTABLE",
    "KEM,            2.16.840.1.101.3.4.4.2,    QUANTUM_SAFE",
  })
  void aliasesAndOidsCannotBypassThePolicy(Primitive primitive, String algorithm, Risk expected) {
    assertThat(assess(primitive, algorithm, 2048, "SIGN").risk()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} {1} must stay unknown")
  @CsvSource({
    "KEM,            ML-KEM-garbage",
    "MESSAGE_DIGEST, SHA-999",
    "SIGNATURE,      ML-DSA-1",
    "MAC,            HmacSHA999",
  })
  void unrecognizedNamesAreNeverApproved(Primitive primitive, String algorithm) {
    assertThat(assess(primitive, algorithm, 0, null).risk()).isEqualTo(Risk.UNKNOWN);
  }

  @ParameterizedTest
  @CsvSource({"8", "64", "111"})
  void shortHmacKeysAreBroken(int bits) {
    assertThat(assess(Primitive.MAC, "HmacSHA512", bits, null).risk()).isEqualTo(Risk.BROKEN);
  }

  // ---- regressions from the second external review ----------------------------------------

  @ParameterizedTest(name = "{0} {1} bits -> {2}")
  @CsvSource({
    "HmacSHA256, 0,   UNKNOWN", // custom key: size not observable, must not be approved
    "HmacSHA512, 0,   UNKNOWN",
    "KMAC128,    8,   BROKEN", // SP 800-131A minimum applies to KMAC too
    "KMAC128,    111, BROKEN",
    "KMAC128,    112, ACCEPTABLE",
    "KMAC256,    0,   UNKNOWN",
    "AESCMAC,    0,   ACCEPTABLE", // AES keys are >= 128 bits by construction
  })
  void macStrengthNeedsEvidence(String algorithm, int bits, Risk expected) {
    assertThat(assess(Primitive.MAC, algorithm, bits, null).risk()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} {1} bits {2} -> {3}")
  @CsvSource({
    "SHA1withRSA,     512,  VERIFY, BROKEN", // key floor applies even when the hash rule is lenient
    "SHA256withRSA,   1024, VERIFY, WEAK", // legacy verification is permitted
    "SHA256withRSA,   1024, SIGN,   BROKEN",
    "SHA256withRSA,   2048, VERIFY, QUANTUM_VULNERABLE",
    "SHA1withRSA,     2048, VERIFY, WEAK",
    "SHA256withECDSA, 192,  VERIFY, WEAK",
    "SHA256withECDSA, 192,  SIGN,   BROKEN",
    "SHA256withECDSA, 128,  VERIFY, BROKEN",
    "SHA256withFOO,   2048, SIGN,   UNKNOWN", // unknown key type is not approved by prefix
    "MD5withFOO,      2048, SIGN,   BROKEN", // a broken hash cannot be hidden by unknown key type
    "MD2withFOO,      2048, SIGN,   BROKEN",
    "SHA1withFOO,     2048, SIGN,   BROKEN",
    "SHA1withFOO,     2048, VERIFY, UNKNOWN", // unknown still wins over legacy-use WEAK
    "MD5withRSA/ISO9796-2, 2048, SIGN, BROKEN", // real Bouncy Castle signature name
    "SHA256withRSA/ISO9796-2, 2048, SIGN, UNKNOWN",
    "SHA256withDSA,   512,  VERIFY, WEAK", // SP 800-131A Table 2: DSA legacy verification from 512
    // bits
    "SHA256withDSA,   256,  VERIFY, BROKEN",
    "SHA256withDSA,   1024, SIGN,   BROKEN",
    "SHA256withRSA,   0,    SIGN,   UNKNOWN", // token key: size not observable
    "SHA1withRSA,     0,    VERIFY, UNKNOWN", // round 4: a lesser SHA-1 finding must not hide
    // unknown strength
    "SHA1withDSA,     0,    VERIFY, UNKNOWN",
    "MD5withRSA,      0,    SIGN,   BROKEN", // a definite BROKEN still wins over unknown
    "RIPEMD160withRSA, 1024, SIGN, BROKEN", // unknown hash must not hide a broken key
    "RIPEMD160withRSA, 512, VERIFY, BROKEN",
    "RIPEMD160withRSA, 1024, VERIFY, UNKNOWN", // unknown wins over legacy-use WEAK
    "RIPEMD160withRSA, 2048, SIGN, UNKNOWN",
    "RIPEMD160withRSA, 0, SIGN, UNKNOWN",
    "SHA999withECDSA, 128, VERIFY, BROKEN",
    "SHA999withDSA, 2048, SIGN, BROKEN",
    "NONEwithRSA, 1024, SIGN, BROKEN",
    "NONEwithRSA, 2048, SIGN, UNKNOWN",
    "NONEwithRSA, 1024, VERIFY, UNKNOWN",
    "NONEwithECDSA, 256, SIGN, UNKNOWN",
    "NONEwithECDSA, 256, VERIFY, UNKNOWN",
    "NONEwithECDSAinP1363Format, 256, SIGN, UNKNOWN",
    "NONEwithECDSA, 192, SIGN, BROKEN",
    "NONEwithECDSA, 128, VERIFY, BROKEN",
    "NONEwithDSA, 2048, SIGN, BROKEN",
    "NONEwithDSA, 1024, VERIFY, UNKNOWN",
  })
  void signatureRulesCombineHashKeyAndOperation(
      String algorithm, int bits, String op, Risk expected) {
    assertThat(assess(Primitive.SIGNATURE, algorithm, bits, op).risk()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "RSA cipher {0} bits {1} {2} -> {3}")
  @CsvSource({
    "RSA/ECB/OAEPPadding, 1024, DECRYPT, BROKEN", // key transport < 2048 is disallowed on both
    // sides
    "RSA/ECB/OAEPPadding, 512,  DECRYPT, BROKEN",
    "RSA/ECB/FooPadding,  2048, ENCRYPT, UNKNOWN",
    "RSA/ECB/FooPadding,  1024, ENCRYPT, BROKEN",
    "RSA/ECB/FooPadding,  1024, DECRYPT, BROKEN",
    "RSA/BOGUS/OAEPPadding, 1024, ENCRYPT, BROKEN",
    "RSA/BOGUS/FooPadding, 1024, UNWRAP, BROKEN",
    "RSA/BOGUS/NoPadding, 2048, ENCRYPT, BROKEN",
    "RSA/BOGUS/PKCS1Padding, 2048, DECRYPT, BROKEN",
    "RSA/BOGUS/NoPadding, 0, ENCRYPT, BROKEN",
    "RSA/BOGUS/OAEPPadding, 0, ENCRYPT, UNKNOWN",
    "RSA/ECB/OAEPPadding, 2048, UNWRAP,  QUANTUM_VULNERABLE",
    "RSA/ECB/PKCS1Padding,2048, UNWRAP,  BROKEN",
    "RSA/BOGUS/OAEPPadding, 2048, ENCRYPT, UNKNOWN",
    "RSA/ECB/OAEPnot-padding, 2048, ENCRYPT, UNKNOWN",
    "RSA/ECB/OAEPWithSHA-256AndMGF1Padding, 3072, ENCRYPT, QUANTUM_VULNERABLE",
    "RSA/ECB/OAEPWithSHA-512/256AndMGF1Padding, 3072, ENCRYPT, QUANTUM_VULNERABLE", // padding
    // contains '/'
    "RSA/ECB/OAEPWithSHA-512/224AndMGF1Padding, 3072, ENCRYPT, QUANTUM_VULNERABLE",
  })
  void rsaCipherRules(String transformation, int bits, String op, Risk expected) {
    assertThat(assess(Primitive.CIPHER, transformation, bits, op).risk()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "AES/not-a-mode/NoPadding,  UNKNOWN",
    "AES/GCM/WeirdPadding,      UNKNOWN",
    "AES/PCBC/PKCS5Padding,     WEAK",
    "AES/CTR/NoPadding,         QUANTUM_SAFE",
    "AES/KW/NoPadding,          QUANTUM_SAFE",
  })
  void blockCipherModesAndPaddingsAreAllowListed(String transformation, Risk expected) {
    assertThat(assess(Primitive.CIPHER, transformation, 256, "ENCRYPT").risk()).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"40", "64", "512"})
  void unexpectedAesKeySizesAreUnknown(int bits) {
    assertThat(assess(Primitive.CIPHER, "AES/GCM/NoPadding", bits, "ENCRYPT").risk())
        .isEqualTo(Risk.UNKNOWN);
  }

  @org.junit.jupiter.api.Test
  void whitespacePaddingCannotDisguiseEcb() {
    // regression: truncating before stripping whitespace turned this into mode "E" and QUANTUM_SAFE
    String disguised = "AES/" + " ".repeat(251) + "ECB/PKCS5Padding";
    Assessment verdict = assess(Primitive.CIPHER, disguised, 256, "ENCRYPT");
    assertThat(verdict.risk()).isEqualTo(Risk.WEAK);
    assertThat(verdict.reason()).contains("ECB");
  }

  @ParameterizedTest
  @CsvSource({"LMS", "HSS/LMS", "XMSS", "XMSSMT"})
  void statefulHashSignaturesDoNotClaimCnsa2WithoutParameters(String algorithm) {
    Assessment verdict = assess(Primitive.SIGNATURE, algorithm, 0, "SIGN");
    assertThat(verdict.risk()).isEqualTo(Risk.QUANTUM_SAFE);
    assertThat(verdict.cnsa2()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({"AES/  GCM /NoPadding", " AES/GCM/NoPadding "})
  void whitespaceInTransformationsIsCanonicalized(String transformation) {
    CryptoAsset asset = CryptoAsset.cipher(transformation, 256, null);
    assertThat(asset).isEqualTo(CryptoAsset.cipher("AES/GCM/NoPadding", 256, null));
  }

  // ---- CNSA 2.0 is a specific list, not "strong enough" ------------------------------------

  @ParameterizedTest(name = "{0} {1} ({2}) cnsa2={3}")
  @CsvSource({
    "CIPHER,         AES/GCM/NoPadding,      256, true",
    "CIPHER,         Camellia/GCM/NoPadding, 256, false",
    "CIPHER,         ChaCha20-Poly1305,      256, false",
    "MESSAGE_DIGEST, SHA-384,                0,   true",
    "MESSAGE_DIGEST, SHA3-384,               0,   false",
    "KEM,            ML-KEM-1024,            0,   true",
    "KEM,            ML-KEM-768,             0,   false",
    "SIGNATURE,      ML-DSA-87,              0,   true",
    "SIGNATURE,      ML-DSA-65,              0,   false",
  })
  void cnsa2FlagIsExact(Primitive primitive, String algorithm, int keySize, boolean cnsa2) {
    Assessment verdict = assess(primitive, algorithm, keySize, "ENCRYPT");
    assertThat(verdict.cnsa2()).as(verdict.reason()).isEqualTo(cnsa2);
    if (!cnsa2) {
      assertThat(verdict.reason()).doesNotContain("(CNSA 2.0)");
    }
  }

  @ParameterizedTest(name = "{0} {1} -> {2}")
  @CsvSource({
    "TLSv1.3, TLS_AES_256_GCM_SHA384,                  ACCEPTABLE",
    "TLSv1.2, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,   ACCEPTABLE",
    "TLSv1.2, TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,   WEAK",
    "TLSv1.2, TLS_RSA_WITH_AES_128_CBC_SHA,            WEAK",
    "TLSv1.2, SSL_RSA_WITH_RC4_128_SHA,                BROKEN",
    "TLSv1,   TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,      BROKEN",
    "TLSv9,   TLS_AES_256_GCM_SHA384,                  UNKNOWN",
    "TLSv1.3, TLS_MADE_UP_SUITE,                       UNKNOWN",
    "TLSv1.2, TLS_ECDHE_BOGUS_WITH_FAKE_GCM_BOGUS,     UNKNOWN",
    "TLSv1.2, TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256, ACCEPTABLE",
    "TLSv1.2, TLS_RSA_WITH_AES_256_GCM_SHA384,         WEAK",
    "TLSv1.2, TLS_DHE_ECDSA_WITH_AES_128_GCM_SHA384,   UNKNOWN", // not a registered suite
    "TLSv1.2, TLS_AES_128_GCM_SHA256,                  UNKNOWN", // TLS 1.3 suite with TLS 1.2
    "TLSv1.3, TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,   UNKNOWN",
    "TLSv1.2, TLS_MADE_UP_3DES_SUITE,                  UNKNOWN",
    "TLSv1.2, TLS_RSA_WITH_3DES_EDE_CBC_SHA,           WEAK",
  })
  void classifiesTlsSessions(String version, String suite, Risk expected) {
    assertThat(policy.assess(CryptoAsset.tls(version, suite)).risk()).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
    "KEM,            ML-KEM-512,  1",
    "KEM,            ML-KEM-768,  3",
    "KEM,            ML-KEM-1024, 5",
    "MESSAGE_DIGEST, SHA-256,     2",
    "MESSAGE_DIGEST, SHA-224,     -1",
  })
  void reportsNistQuantumLevelOnlyWhereEstablished(
      Primitive primitive, String algorithm, int level) {
    assertThat(
            policy.assess(CryptoAsset.of(primitive, algorithm, 0, null)).nistQuantumSecurityLevel())
        .isEqualTo(level);
  }

  @ParameterizedTest
  @CsvSource({"RSA", "EC", "DSA", "DH"})
  void unobservedPublicKeySizeIsUnknown(String algorithm) {
    assertThat(
            policy.assess(CryptoAsset.of(Primitive.KEY_PAIR_GENERATION, algorithm, 0, null)).risk())
        .isEqualTo(Risk.UNKNOWN);
  }

  @ParameterizedTest
  @CsvSource({"RSA", "EC", "X25519"})
  void quantumVulnerableAssetsHaveNistLevelZero(String algorithm) {
    Assessment verdict =
        policy.assess(CryptoAsset.of(Primitive.KEY_PAIR_GENERATION, algorithm, 2048, null));
    assertThat(verdict.nistQuantumSecurityLevel()).isZero();
  }
}
