package com.developmentsprint.cryptocensus.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default risk policy.
 *
 * <p>Sources: NIST SP 800-131A Rev. 2 (transitions), FIPS 203/204/205 (ML-KEM, ML-DSA, SLH-DSA),
 * NIST SP 800-208 (LMS/XMSS), NSA CNSA 2.0 (algorithm list), RFC 8996 (TLS 1.0/1.1).
 *
 * <p>Design rules:
 *
 * <ul>
 *   <li><b>Allow-lists, not block-lists.</b> Names are canonicalized first ({@link Names}, plus
 *       provider alias resolution in the agent); then every algorithm, mode, padding, signature key
 *       type and TLS suite must be on an explicit list to earn a verdict. Anything else is {@link
 *       Risk#UNKNOWN}.
 *   <li><b>Missing evidence is not approval.</b> A cipher without a mode gets the provider default
 *       (ECB). A MAC whose key length was not observed is unknown, because a short MAC key is a
 *       real weakness. (An AES key of unobserved size stays acceptable: AES only has
 *       128/192/256-bit keys, so the missing size cannot hide a weak key.)
 *   <li><b>Operation-aware.</b> Algorithms and key sizes disallowed for new protection (TDEA
 *       encryption, SHA-1 signing, RSA-1024 signing) are still permitted as legacy use for
 *       decrypt/verify. Hash and key-size rules are evaluated independently and the more severe
 *       wins.
 *   <li>This profile is <b>stricter than SP 800-131A</b> in one respect: all classical public-key
 *       cryptography is reported as quantum-vulnerable.
 * </ul>
 */
public final class Policy {

  /**
   * Identifies this rule set in CBOM metadata; bump {@link #VERSION} whenever a verdict changes.
   */
  public static final String ID = "cryptocensus-default";

  public static final String VERSION = "5";

  private static final int MIN_RSA_DH_DSA_BITS = 2048;

  /**
   * SP 800-131A Rev. 2 Table 2: RSA signature verification with 1024 <= len(n) < 2048 is legacy
   * use.
   */
  private static final int MIN_LEGACY_RSA_VERIFY_BITS = 1024;

  /** SP 800-131A Rev. 2 Table 2: DSA signature verification with 512 <= L < 2048 is legacy use. */
  private static final int MIN_LEGACY_DSA_VERIFY_BITS = 512;

  private static final int MIN_EC_BITS = 224;
  private static final int MIN_LEGACY_EC_BITS = 160;
  private static final int MIN_MAC_KEY_BITS = 112;

  /** HMAC keys from 256 bits keep at least 128-bit strength against Grover-style search. */
  private static final int MIN_QUANTUM_SAFE_MAC_KEY_BITS = 256;

  private static final Set<String> RSA = Set.of("RSA", "RSASSA-PSS", "RSA-PSS");
  private static final Set<String> EC = Set.of("EC", "ECDH", "ECDSA", "ECMQV");
  private static final Set<String> FFDH = Set.of("DH", "DIFFIEHELLMAN");
  private static final Set<String> CURVES =
      Set.of("XDH", "X25519", "X448", "EDDSA", "ED25519", "ED448");

  /** Canonical (dash-free, upper-case) hash names allowed in {@code <hash>with<key>} signatures. */
  private static final Set<String> SIGNATURE_HASHES =
      Set.of(
          "SHA224",
          "SHA256",
          "SHA384",
          "SHA512",
          "SHA512/224",
          "SHA512/256",
          "SHA3224",
          "SHA3256",
          "SHA3384",
          "SHA3512",
          "NONE");

  private static final Set<String> RSA_SIGNATURE_KEYS =
      Set.of("RSA", "RSA/PSS", "RSAANDMGF1", "RSASSA-PSS");
  private static final Set<String> EC_SIGNATURE_KEYS =
      Set.of("ECDSA", "ECDSAINP1363FORMAT", "PLAIN-ECDSA", "CVC-ECDSA");
  private static final Set<String> DSA_SIGNATURE_KEYS = Set.of("DSA", "DSAINP1363FORMAT");

  /** Block cipher modes that earn a strength-based verdict. Anything else is unknown. */
  private static final Set<String> APPROVED_MODES =
      Set.of(
          "GCM", "CCM", "CBC", "CTR", "CTS", "KW", "KWP", "CFB", "CFB8", "CFB64", "CFB128", "OFB",
          "OFB8", "OFB64", "OFB128");

  private static final Set<String> APPROVED_PADDINGS =
      Set.of("NOPADDING", "PKCS5PADDING", "PKCS7PADDING", "ISO10126PADDING");

  /** JCA standard OAEP padding names (upper-cased). */
  private static final Set<String> OAEP_PADDINGS =
      Set.of(
          "OAEPPADDING",
          "OAEPWITHSHA-1ANDMGF1PADDING",
          "OAEPWITHSHA1ANDMGF1PADDING",
          "OAEPWITHSHA-224ANDMGF1PADDING",
          "OAEPWITHSHA-256ANDMGF1PADDING",
          "OAEPWITHSHA-384ANDMGF1PADDING",
          "OAEPWITHSHA-512ANDMGF1PADDING",
          "OAEPWITHSHA-512/224ANDMGF1PADDING",
          "OAEPWITHSHA-512/256ANDMGF1PADDING");

  private static final Set<String> TLS13_SUITES =
      Set.of(
          "TLS_AES_128_GCM_SHA256",
          "TLS_AES_256_GCM_SHA384",
          "TLS_CHACHA20_POLY1305_SHA256",
          "TLS_AES_128_CCM_SHA256",
          "TLS_AES_128_CCM_8_SHA256");

  /** TLS 1.2 suites as registered (IANA); exact names, so fabricated combinations stay unknown. */
  private static final Set<String> TLS12_AEAD =
      Set.of(
          "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_ECDSA_WITH_AES_128_CCM",
          "TLS_ECDHE_ECDSA_WITH_AES_256_CCM",
          "TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8",
          "TLS_ECDHE_ECDSA_WITH_AES_256_CCM_8",
          "TLS_DHE_RSA_WITH_AES_128_CCM",
          "TLS_DHE_RSA_WITH_AES_256_CCM");

  private static final Set<String> TLS12_CBC =
      Set.of(
          "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
          "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
          "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
          "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
          "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256", "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256",
          "TLS_DHE_RSA_WITH_AES_128_CBC_SHA", "TLS_DHE_RSA_WITH_AES_256_CBC_SHA");
  private static final Set<String> TLS_3DES =
      Set.of(
          "TLS_RSA_WITH_3DES_EDE_CBC_SHA", "SSL_RSA_WITH_3DES_EDE_CBC_SHA",
          "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA",
          "TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA", "SSL_DHE_RSA_WITH_3DES_EDE_CBC_SHA",
          "TLS_DHE_DSS_WITH_3DES_EDE_CBC_SHA", "SSL_DHE_DSS_WITH_3DES_EDE_CBC_SHA");
  private static final Set<String> TLS_STATIC_RSA =
      Set.of(
          "TLS_RSA_WITH_AES_128_GCM_SHA256", "TLS_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_RSA_WITH_AES_128_CBC_SHA256", "TLS_RSA_WITH_AES_256_CBC_SHA256",
          "TLS_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_256_CBC_SHA");

  private static final Pattern SLH_DSA =
      Pattern.compile("SLH-DSA(?:-(?:SHA2|SHAKE)-(128|192|256)[SF])?");

  private static final Map<String, Assessment> DIGESTS =
      Map.ofEntries(
          Map.entry("MD2", Assessment.of(Risk.BROKEN, "MD2 is collision-broken")),
          Map.entry("MD4", Assessment.of(Risk.BROKEN, "MD4 is collision-broken")),
          Map.entry("MD5", Assessment.of(Risk.BROKEN, "MD5 is collision-broken")),
          Map.entry(
              "SHA-1",
              Assessment.of(
                  Risk.WEAK,
                  "SHA-1 has practical collisions; only acceptable where collision resistance is"
                      + " not required (SP 800-131A)")),
          Map.entry("SHA-224", Assessment.of(Risk.ACCEPTABLE, "112-bit collision resistance")),
          Map.entry("SHA-512/224", Assessment.of(Risk.ACCEPTABLE, "112-bit collision resistance")),
          Map.entry("SHA3-224", Assessment.of(Risk.ACCEPTABLE, "112-bit collision resistance")),
          Map.entry(
              "SHA-256",
              Assessment.of(Risk.ACCEPTABLE, "Secure; CNSA 2.0 requires SHA-384 or SHA-512", 2)),
          Map.entry(
              "SHA-512/256",
              Assessment.of(Risk.ACCEPTABLE, "Secure; CNSA 2.0 requires SHA-384 or SHA-512", 2)),
          Map.entry("SHA3-256", Assessment.of(Risk.ACCEPTABLE, "Secure; not a CNSA 2.0 hash", 2)),
          Map.entry("SHA-384", Assessment.cnsa2(Risk.QUANTUM_SAFE, "SHA-384 (CNSA 2.0)", 4)),
          Map.entry("SHA-512", Assessment.cnsa2(Risk.QUANTUM_SAFE, "SHA-512 (CNSA 2.0)", 5)),
          Map.entry(
              "SHA3-384",
              Assessment.of(
                  Risk.QUANTUM_SAFE, "Quantum-resistant; not a CNSA 2.0 general-purpose hash", 4)),
          Map.entry(
              "SHA3-512",
              Assessment.of(
                  Risk.QUANTUM_SAFE, "Quantum-resistant; not a CNSA 2.0 general-purpose hash", 5)),
          Map.entry(
              "SHAKE128", Assessment.of(Risk.ACCEPTABLE, "XOF; strength depends on output length")),
          Map.entry(
              "SHAKE256",
              Assessment.of(Risk.ACCEPTABLE, "XOF; strength depends on output length")));

  /**
   * Assesses one asset.
   *
   * @param asset the asset
   * @return its risk, a one-line reason, NIST level and CNSA 2.0 eligibility
   */
  public Assessment assess(CryptoAsset asset) {
    String alg = asset.algorithm().toUpperCase(Locale.ROOT);
    return switch (asset.primitive()) {
      case TLS -> tls(asset.protocolVersion(), alg);
      case MESSAGE_DIGEST ->
          DIGESTS.getOrDefault(
              alg, Assessment.of(Risk.UNKNOWN, "Unrecognized digest " + asset.algorithm()));
      case MAC -> mac(alg, asset.keySize());
      case SIGNATURE -> signature(alg, asset.keySize(), asset.operation());
      case CIPHER ->
          cipher(
              alg, upper(asset.mode()), upper(asset.padding()), asset.keySize(), asset.operation());
      case KEY_GENERATION -> keyGeneration(alg, asset.keySize());
      case KEY_AGREEMENT, KEM, KEY_PAIR_GENERATION -> asymmetric(alg, asset.keySize(), null);
      default -> Assessment.of(Risk.UNKNOWN, "No rule for primitive " + asset.primitive());
    };
  }

  // ---------------------------------------------------------------- post-quantum

  private static Assessment postQuantum(String alg) {
    switch (alg) {
      case "ML-KEM-512" -> {
        return Assessment.of(Risk.QUANTUM_SAFE, "ML-KEM-512 (FIPS 203)", 1);
      }
      case "ML-KEM-768" -> {
        return Assessment.of(
            Risk.QUANTUM_SAFE, "ML-KEM-768 (FIPS 203); CNSA 2.0 requires ML-KEM-1024", 3);
      }
      case "ML-KEM-1024" -> {
        return Assessment.cnsa2(Risk.QUANTUM_SAFE, "ML-KEM-1024 (FIPS 203, CNSA 2.0)", 5);
      }
      case "ML-KEM" -> {
        return Assessment.of(
            Risk.QUANTUM_SAFE, "ML-KEM (FIPS 203); parameter set not observed", -1);
      }
      case "X25519MLKEM768", "SECP256R1MLKEM768" -> {
        return Assessment.of(Risk.QUANTUM_SAFE, "Hybrid ECDHE + ML-KEM-768 key establishment", 3);
      }
      case "SECP384R1MLKEM1024" -> {
        return Assessment.of(Risk.QUANTUM_SAFE, "Hybrid ECDHE + ML-KEM-1024 key establishment", 5);
      }
      case "ML-DSA-44" -> {
        return Assessment.of(
            Risk.QUANTUM_SAFE, "ML-DSA-44 (FIPS 204); CNSA 2.0 requires ML-DSA-87", 2);
      }
      case "ML-DSA-65" -> {
        return Assessment.of(
            Risk.QUANTUM_SAFE, "ML-DSA-65 (FIPS 204); CNSA 2.0 requires ML-DSA-87", 3);
      }
      case "ML-DSA-87" -> {
        return Assessment.cnsa2(Risk.QUANTUM_SAFE, "ML-DSA-87 (FIPS 204, CNSA 2.0)", 5);
      }
      case "ML-DSA" -> {
        return Assessment.of(
            Risk.QUANTUM_SAFE, "ML-DSA (FIPS 204); parameter set not observed", -1);
      }
      case "HSS/LMS", "LMS", "XMSS", "XMSSMT" -> {
        // CNSA 2.0 lists LMS/XMSS only for software/firmware signing with specific parameter
        // sets; neither is observable here, so eligibility is not claimed.
        return Assessment.of(
            Risk.QUANTUM_SAFE,
            "Stateful hash-based signature (SP 800-208); CNSA 2.0 eligibility depends on parameter"
                + " set and firmware-signing use",
            -1);
      }
      default -> {}
    }
    Matcher slh = SLH_DSA.matcher(alg);
    if (slh.matches()) {
      String bits = slh.group(1);
      int level = bits == null ? -1 : "128".equals(bits) ? 1 : "192".equals(bits) ? 3 : 5;
      return Assessment.of(Risk.QUANTUM_SAFE, "SLH-DSA (FIPS 205)", level);
    }
    if (alg.startsWith("KYBER") || alg.startsWith("DILITHIUM") || alg.startsWith("SPHINCS")) {
      // Deliberately not approved: pre-standard round-3 variants differ from FIPS 203/204/205
      return Assessment.of(
          Risk.UNKNOWN, "Pre-standard " + alg + "; not the FIPS 203/204/205 algorithm");
    }
    return null;
  }

  // ---------------------------------------------------------------- asymmetric

  private static Assessment asymmetric(String alg, int keySize, Operation op) {
    Assessment pq = postQuantum(alg);
    if (pq != null) {
      return pq;
    }

    if (RSA.contains(alg)) {
      return rsaSignatureSize(keySize, op);
    }
    if ("DSA".equals(alg)) {
      return dsaSize(keySize, op);
    }
    if (FFDH.contains(alg)) {
      if (keySize == 0) {
        return unobserved("Finite-field Diffie-Hellman");
      }
      if (keySize < MIN_RSA_DH_DSA_BITS) {
        return Assessment.of(
            Risk.BROKEN,
            "Finite-field DH-" + keySize + " is below the 2048-bit minimum (SP 800-131A)");
      }
      return Assessment.of(
          Risk.QUANTUM_VULNERABLE,
          "Finite-field Diffie-Hellman is broken by Shor's algorithm; migrate to ML-KEM");
    }
    if (EC.contains(alg) || "DHKEM".equals(alg)) {
      if (keySize == 0) {
        return unobserved("Elliptic-curve cryptography");
      }
      boolean legacy = op == Operation.VERIFY;
      int minimum = legacy ? MIN_LEGACY_EC_BITS : MIN_EC_BITS;
      if (keySize > 0 && keySize < minimum) {
        return Assessment.of(Risk.BROKEN, "EC-" + keySize + " is below " + minimum + " bits");
      }
      if (legacy && keySize > 0 && keySize < MIN_EC_BITS) {
        return Assessment.of(
            Risk.WEAK, "EC-" + keySize + " verification is legacy use only (SP 800-131A)");
      }
      return Assessment.of(
          Risk.QUANTUM_VULNERABLE, "Elliptic-curve cryptography is broken by Shor's algorithm");
    }
    if (CURVES.contains(alg)) {
      return Assessment.of(
          Risk.QUANTUM_VULNERABLE, "Curve25519/448 cryptography is broken by Shor's algorithm");
    }
    return Assessment.of(Risk.UNKNOWN, "Unrecognized asymmetric algorithm " + alg);
  }

  /**
   * A public-key algorithm whose key size could not be observed (for example a PKCS#11 token key,
   * which the agent deliberately does not inspect): a short key would be a real weakness, so this
   * is not approved.
   */
  private static Assessment unobserved(String what) {
    return Assessment.of(
        Risk.UNKNOWN, what + ": key size not observed; also broken by Shor's algorithm");
  }

  /**
   * RSA signatures and RSA key generation (SP 800-131A Rev. 2 Table 2): 2048+ acceptable; for
   * verification only, 1024..2047 is legacy use.
   */
  private static Assessment rsaSignatureSize(int keySize, Operation op) {
    if (keySize == 0) {
      return unobserved("RSA");
    }
    boolean verify = op == Operation.VERIFY;
    if (keySize < MIN_LEGACY_RSA_VERIFY_BITS || (!verify && keySize < MIN_RSA_DH_DSA_BITS)) {
      return Assessment.of(
          Risk.BROKEN,
          "RSA-"
              + keySize
              + " is below the "
              + (verify ? 1024 : 2048)
              + "-bit minimum for "
              + (verify ? "legacy verification" : "this use")
              + " (SP 800-131A)");
    }
    if (keySize < MIN_RSA_DH_DSA_BITS) {
      return Assessment.of(
          Risk.WEAK, "RSA-" + keySize + " verification is legacy use only (SP 800-131A)");
    }
    return Assessment.of(
        Risk.QUANTUM_VULNERABLE, "RSA is broken by Shor's algorithm; migrate to ML-KEM / ML-DSA");
  }

  /**
   * RSA key transport (SP 800-131A Rev. 2 section 6) applies to encryption <em>and</em> decryption:
   * below 2048 bits is disallowed on both sides, with no legacy-use exception.
   */
  private static Assessment rsaTransportSize(int keySize) {
    if (keySize == 0) {
      return unobserved("RSA key transport");
    }
    if (keySize < MIN_RSA_DH_DSA_BITS) {
      return Assessment.of(
          Risk.BROKEN,
          "RSA-"
              + keySize
              + " key transport is disallowed for encryption and decryption (SP 800-131A section"
              + " 6)");
    }
    return Assessment.of(
        Risk.QUANTUM_VULNERABLE,
        "RSA key transport is broken by Shor's algorithm; migrate to ML-KEM");
  }

  /**
   * DSA (SP 800-131A Rev. 2 Table 2, FIPS 186-5): generation (and new key pairs) are no longer
   * approved; verification with 512 <= L < 2048 is legacy use. Only the modulus length L is
   * observed, not the subgroup size N, so the N conditions of Table 2 are not assessed.
   */
  private static Assessment dsaSize(int keySize, Operation op) {
    if (keySize == 0) {
      return unobserved("DSA");
    }
    if (op == Operation.VERIFY) {
      if (keySize < MIN_LEGACY_DSA_VERIFY_BITS) {
        return Assessment.of(
            Risk.BROKEN,
            "DSA-" + keySize + " is below the 512-bit floor for legacy verification (SP 800-131A)");
      }
      if (keySize < MIN_RSA_DH_DSA_BITS) {
        return Assessment.of(
            Risk.WEAK, "DSA-" + keySize + " verification is legacy use only (SP 800-131A)");
      }
      return Assessment.of(
          Risk.QUANTUM_VULNERABLE, "DSA verification; DSA is broken by Shor's algorithm");
    }
    // FIPS 186-5 no longer approves DSA signature generation at any size; verification stays legacy
    // use.
    return Assessment.of(
        Risk.BROKEN,
        "DSA signature generation is no longer approved (FIPS 186-5); use ECDSA, EdDSA or ML-DSA");
  }

  // ---------------------------------------------------------------- signatures

  private static Assessment signature(String alg, int keySize, Operation op) {
    Assessment pq = postQuantum(alg);
    if (pq != null) {
      return pq;
    }

    int with = alg.indexOf("WITH");
    if (with <= 0) {
      if (RSA.contains(alg)) {
        // Bare RSASSA-PSS: the hash lives in the PSS parameters, and SHA-1 is a valid choice. The
        // recorder names the hash (SHA256withRSASSA-PSS) when it could read them.
        return worst(
            rsaSignatureSize(keySize, op),
            Assessment.of(
                Risk.UNKNOWN, alg + " signature; its hash (PSS parameters) not observed"));
      }
      return asymmetric(alg, keySize, op);
    }
    String digestName = alg.substring(0, with);
    String key = alg.substring(with + 4);

    Assessment keyVerdict;
    if (RSA_SIGNATURE_KEYS.contains(key)) {
      keyVerdict = rsaSignatureSize(keySize, op);
    } else if (EC_SIGNATURE_KEYS.contains(key)) {
      keyVerdict = asymmetric("ECDSA", keySize, op);
    } else if (DSA_SIGNATURE_KEYS.contains(key)) {
      keyVerdict = asymmetric("DSA", keySize, op);
    } else {
      return Assessment.of(Risk.UNKNOWN, "Unrecognized signature algorithm " + alg);
    }

    Assessment hashVerdict = legacySignatureHash(digestName, op);
    if (hashVerdict == null && !SIGNATURE_HASHES.contains(digestName)) {
      return Assessment.of(Risk.UNKNOWN, "Unrecognized signature hash in " + alg);
    }
    return worst(keyVerdict, hashVerdict);
  }

  /** The verdict on a broken or legacy signature hash (MD2, MD5, SHA-1), otherwise null. */
  @SuppressFBWarnings(
      value = "UNSAFE_HASH_EQUALS",
      justification =
          "compares the hash *algorithm name* parsed from a public signature algorithm name such"
              + " as MD5withRSA, not a digest value; nothing secret is compared, so timing is moot")
  private static Assessment legacySignatureHash(String digestName, Operation op) {
    return switch (digestName) {
      case "MD2", "MD5" ->
          Assessment.of(Risk.BROKEN, digestName + " signatures are forgeable via hash collisions");
      case "SHA1" ->
          op == Operation.VERIFY
              ? Assessment.of(
                  Risk.WEAK, "SHA-1 signature verification is legacy use only (SP 800-131A)")
              : Assessment.of(
                  Risk.BROKEN,
                  "SHA-1 signature generation is disallowed (SP 800-131A); collisions are"
                      + " practical");
      default -> null;
    };
  }

  // ---------------------------------------------------------------- symmetric

  private static Assessment cipher(
      String alg, String mode, String padding, int keySize, Operation op) {
    final boolean legacyUse = op == Operation.DECRYPT || op == Operation.UNWRAP;

    if (alg.startsWith("PBEWITH")) {
      if (alg.contains("MD5")
          || alg.contains("MD2")
          || alg.contains("DES")
          || alg.contains("RC2")
          || alg.contains("RC4")) {
        return Assessment.of(Risk.BROKEN, "Legacy password-based encryption scheme " + alg);
      }
      return Assessment.of(
          Risk.UNKNOWN, "Password-based encryption; work factor (iteration count) not observed");
    }
    if ("RSA".equals(alg)) {
      if (mode != null && !"ECB".equals(mode) && !"NONE".equals(mode)) {
        return Assessment.of(Risk.UNKNOWN, "Unrecognized RSA mode " + mode);
      }
      Assessment pad;
      if ("NOPADDING".equals(padding)) {
        pad = Assessment.of(Risk.BROKEN, "Textbook RSA (no padding) is insecure");
      } else if (padding == null || "PKCS1PADDING".equals(padding)) {
        String which =
            padding == null ? "Provider default padding (PKCS#1 v1.5)" : "PKCS#1 v1.5 padding";
        pad =
            Assessment.of(
                Risk.BROKEN,
                which
                    + " key transport is disallowed after 2023, for encryption and decryption (SP"
                    + " 800-131A)");
      } else if (OAEP_PADDINGS.contains(padding)) {
        pad = null;
      } else {
        return Assessment.of(Risk.UNKNOWN, "Unrecognized RSA padding " + padding);
      }
      return worst(rsaTransportSize(keySize), pad);
    }
    if ("DES".equals(alg) || "RC2".equals(alg) || "RC4".equals(alg) || "ARCFOUR".equals(alg)) {
      return Assessment.of(Risk.BROKEN, alg + " is cryptographically broken");
    }
    if ("DESEDE".equals(alg) || "TRIPLEDES".equals(alg)) {
      return legacyUse
          ? Assessment.of(Risk.WEAK, "TDEA decryption is legacy use only (SP 800-131A)")
          : Assessment.of(Risk.BROKEN, "TDEA encryption is disallowed after 2023 (SP 800-131A)");
    }
    if ("BLOWFISH".equals(alg)) {
      return Assessment.of(Risk.WEAK, "64-bit block cipher (Sweet32); not NIST-approved");
    }
    if ("CHACHA20-POLY1305".equals(alg)) {
      return Assessment.of(
          Risk.QUANTUM_SAFE, "256-bit AEAD; quantum-resistant, not a NIST/CNSA algorithm", 5);
    }
    if ("CHACHA20".equals(alg)) {
      return Assessment.of(
          Risk.ACCEPTABLE, "Unauthenticated stream cipher; prefer ChaCha20-Poly1305");
    }
    if ("AESWRAP".equals(alg)
        || "AESWRAPPAD".equals(alg)
        || "AESKW".equals(alg)
        || "AESKWP".equals(alg)) {
      return blockCipherStrength("AES", keySize, "AES key wrap");
    }
    if ("AES".equals(alg) || "CAMELLIA".equals(alg) || "ARIA".equals(alg)) {
      if (mode == null) {
        return Assessment.of(
            Risk.WEAK,
            "No mode specified; JCA providers default to ECB (SunJCE: "
                + alg
                + "/ECB/PKCS5Padding)");
      }
      if ("ECB".equals(mode)) {
        return Assessment.of(Risk.WEAK, "ECB mode leaks plaintext patterns; use GCM");
      }
      if ("PCBC".equals(mode)) {
        return Assessment.of(Risk.WEAK, "PCBC is a legacy mode with known weaknesses; use GCM");
      }
      if (!APPROVED_MODES.contains(mode)) {
        return Assessment.of(Risk.UNKNOWN, "Unrecognized block cipher mode " + mode);
      }
      if (padding != null && !APPROVED_PADDINGS.contains(padding)) {
        return Assessment.of(Risk.UNKNOWN, "Unrecognized padding " + padding);
      }
      return blockCipherStrength(alg, keySize, alg + "-" + mode);
    }
    if (alg.startsWith("HMAC")) {
      return mac(alg, keySize);
    }
    return Assessment.of(Risk.UNKNOWN, "Unrecognized cipher " + alg);
  }

  private static Assessment blockCipherStrength(String alg, int keySize, String what) {
    // AES, Camellia and ARIA define exactly these three key sizes
    return switch (keySize) {
      case 256 ->
          "AES".equals(alg)
              ? Assessment.cnsa2(Risk.QUANTUM_SAFE, what + " with a 256-bit key (CNSA 2.0)", 5)
              : Assessment.of(
                  Risk.QUANTUM_SAFE,
                  what + " with a 256-bit key; quantum-resistant, not a CNSA 2.0 algorithm",
                  5);
      case 192 ->
          Assessment.of(Risk.ACCEPTABLE, what + "-192 is secure; CNSA 2.0 requires AES-256", 3);
      case 128 ->
          Assessment.of(Risk.ACCEPTABLE, what + "-128 is secure; CNSA 2.0 requires AES-256", 1);
      // these ciphers only accept 128/192/256-bit keys, so an unobserved size cannot hide a weak
      // key
      case 0 ->
          Assessment.of(
              Risk.ACCEPTABLE,
              what + ": key size not observed (128 bits or more); CNSA 2.0 requires AES-256");
      default ->
          Assessment.of(
              Risk.UNKNOWN, what + " with an unexpected key size of " + keySize + " bits");
    };
  }

  private static Assessment keyGeneration(String alg, int keySize) {
    return switch (alg) {
      case "AES", "CAMELLIA", "ARIA" -> blockCipherStrength(alg, keySize, alg + " key generation");
      case "DES", "RC2", "RC4", "ARCFOUR" ->
          Assessment.of(Risk.BROKEN, alg + " is cryptographically broken");
      case "DESEDE", "TRIPLEDES" ->
          Assessment.of(Risk.BROKEN, "New TDEA keys are disallowed after 2023 (SP 800-131A)");
      case "BLOWFISH" ->
          Assessment.of(Risk.WEAK, "64-bit block cipher (Sweet32); not NIST-approved");
      case "CHACHA20" -> Assessment.of(Risk.QUANTUM_SAFE, "256-bit ChaCha20 key", 5);
      default ->
          alg.startsWith("HMAC")
              ? mac(alg, keySize)
              : Assessment.of(Risk.UNKNOWN, "Unrecognized key generator " + alg);
    };
  }

  private static Assessment mac(String alg, int keySize) {
    boolean keyed = alg.startsWith("HMAC") || alg.startsWith("KMAC");
    if (keyed && keySize > 0 && keySize < MIN_MAC_KEY_BITS) {
      return Assessment.of(
          Risk.BROKEN,
          alg + " key of " + keySize + " bits is below the 112-bit minimum (SP 800-131A)");
    }
    switch (alg) {
      case "HMACMD5" -> {
        return Assessment.of(Risk.WEAK, "HMAC-MD5 is deprecated");
      }
      case "HMACSHA1",
          "HMACSHA224",
          "HMACSHA256",
          "HMACSHA512/224",
          "HMACSHA512/256",
          "HMACSHA3-224",
          "HMACSHA3-256",
          "KMAC128",
          "KMAC256" -> {
        if (keySize == 0) {
          return Assessment.of(
              Risk.UNKNOWN,
              alg + ": key length not observed; MAC keys below 112 bits are disallowed");
        }
        return Assessment.of(
            Risk.ACCEPTABLE,
            "Secure MAC with a "
                + keySize
                + "-bit key"
                + ("HMACSHA1".equals(alg)
                    ? "; HMAC-SHA-1 remains approved but is not CNSA 2.0"
                    : ""));
      }
      case "HMACSHA384", "HMACSHA512", "HMACSHA3-384", "HMACSHA3-512" -> {
        if (keySize == 0) {
          return Assessment.of(
              Risk.UNKNOWN,
              alg + ": key length not observed; MAC keys below 112 bits are disallowed");
        }
        if (keySize >= MIN_QUANTUM_SAFE_MAC_KEY_BITS) {
          return Assessment.of(Risk.QUANTUM_SAFE, "Strong MAC with a " + keySize + "-bit key");
        }
        return Assessment.of(Risk.ACCEPTABLE, "Strong hash; key length " + keySize + " bits");
      }
      case "AESCMAC", "AES-CMAC", "AES-GMAC", "GMAC" -> {
        // AES keys are at least 128 bits, so these are sound regardless of observed size
        return Assessment.of(Risk.ACCEPTABLE, "AES-based MAC");
      }
      case "POLY1305" -> {
        return Assessment.of(Risk.ACCEPTABLE, "Poly1305 (fixed 256-bit one-time key)");
      }
      default -> {
        return Assessment.of(Risk.UNKNOWN, "Unrecognized MAC " + alg);
      }
    }
  }

  // ---------------------------------------------------------------- TLS

  private static Assessment tls(String protocolVersion, String suite) {
    String version = protocolVersion == null ? "" : protocolVersion;
    switch (version) {
      case "SSLv2Hello", "SSLv3", "TLSv1", "TLSv1.1", "DTLSv1.0" -> {
        return Assessment.of(Risk.BROKEN, version + " is deprecated (RFC 8996)");
      }
      case "TLSv1.2", "TLSv1.3", "DTLSv1.2", "DTLSv1.3" -> {}
      default -> {
        return Assessment.of(Risk.UNKNOWN, "Unrecognized protocol version " + version);
      }
    }
    if (suite.contains("_NULL_")
        || suite.contains("_EXPORT")
        || suite.contains("_ANON_")
        || suite.contains("_RC4_")
        || suite.contains("_DES_")
        || suite.endsWith("_MD5")) {
      return Assessment.of(Risk.BROKEN, "Insecure cipher suite " + suite);
    }
    String note =
        "; quantum exposure depends on the key-exchange group (see key-agreement/KEM assets via"
            + " jsse)";
    boolean v13 = version.endsWith("1.3");
    if (TLS13_SUITES.contains(suite)) {
      return v13
          ? Assessment.of(Risk.ACCEPTABLE, "TLS 1.3 AEAD suite" + note)
          : Assessment.of(Risk.UNKNOWN, "TLS 1.3 suite reported with " + version);
    }
    if (v13) {
      return Assessment.of(Risk.UNKNOWN, "Suite " + suite + " is not a TLS 1.3 suite");
    }
    if (TLS12_AEAD.contains(suite)) {
      return Assessment.of(Risk.ACCEPTABLE, "Forward-secret AEAD suite" + note);
    }
    if (TLS_3DES.contains(suite)) {
      return Assessment.of(Risk.WEAK, "3DES suites are vulnerable to Sweet32");
    }
    if (TLS_STATIC_RSA.contains(suite)) {
      return Assessment.of(Risk.WEAK, "Static RSA key exchange has no forward secrecy");
    }
    if (TLS12_CBC.contains(suite)) {
      return Assessment.of(Risk.WEAK, "CBC suite (padding-oracle history); prefer AEAD suites");
    }
    return Assessment.of(Risk.UNKNOWN, "Unrecognized cipher suite " + suite);
  }

  // ---------------------------------------------------------------- helpers

  /**
   * Combines two independent findings about one asset ({@code b} may be null). Unknown is not a
   * severity: a definite BROKEN still rejects the asset, but otherwise missing evidence must not be
   * hidden behind a known, lesser finding (e.g. SHA-1 verification with an unobservable key size).
   */
  private static Assessment worst(Assessment a, Assessment b) {
    if (b == null) {
      return a;
    }
    if (a.risk() == Risk.BROKEN) {
      return a;
    }
    if (b.risk() == Risk.BROKEN) {
      return b;
    }
    if (a.risk() == Risk.UNKNOWN) {
      return a;
    }
    if (b.risk() == Risk.UNKNOWN) {
      return b;
    }
    return b.risk().severity() > a.risk().severity() ? b : a;
  }

  private static String upper(String s) {
    return s == null ? null : s.toUpperCase(Locale.ROOT);
  }
}
