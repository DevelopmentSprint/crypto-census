package com.developmentsprint.cryptocensus.core;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A distinct cryptographic asset: an algorithm (or TLS session shape) together with the parameters
 * that matter for risk assessment. Where it was used is tracked separately as {@link Occurrence}s.
 *
 * <p>Names are canonicalized on construction (see {@link Names}), so {@code SHA}, {@code SHA1} and
 * the SHA-1 OID are one asset.
 *
 * @param primitive how the asset was reached (cipher, signature, TLS, ...)
 * @param algorithm canonical algorithm name, e.g. {@code AES}, {@code SHA256withRSA}, {@code
 *     ML-KEM-768}, or the cipher suite for {@link Primitive#TLS}
 * @param mode block cipher mode as requested ({@code GCM}, {@code CBC}, ...), or {@code null} when
 *     the caller relied on the provider default
 * @param padding padding scheme as requested, or {@code null} for the provider default
 * @param keySize key size in bits, or {@code 0} when unknown
 * @param provider JCA provider name, or {@code null}
 * @param protocolVersion TLS protocol version for {@link Primitive#TLS}, otherwise {@code null}
 * @param operation encrypt/decrypt/sign/verify where known, otherwise {@code null}
 */
public record CryptoAsset(
    Primitive primitive,
    String algorithm,
    String mode,
    String padding,
    int keySize,
    String provider,
    String protocolVersion,
    Operation operation) {

  private static final Pattern TRAILING_PARAMETER = Pattern.compile(".*[-_/](\\d{2,})$");

  /** Validates and canonicalizes: algorithm names via {@link Names}, blank strings to null. */
  public CryptoAsset {
    Objects.requireNonNull(primitive, "primitive");
    Objects.requireNonNull(algorithm, "algorithm");
    algorithm =
        primitive == Primitive.TLS
            ? Names.truncate(algorithm.trim())
            : Names.canonical(primitive, algorithm);
    mode = clean(mode);
    padding = clean(padding);
    provider = clean(provider);
    protocolVersion = clean(protocolVersion);
    if (keySize < 0) {
      keySize = 0;
    }
  }

  /**
   * An asset without mode, padding or operation.
   *
   * @param primitive how it was reached
   * @param algorithm algorithm name (canonicalized)
   * @param keySize key size in bits, or 0 when unknown
   * @param provider JCA provider name, or null
   * @return the asset
   */
  public static CryptoAsset of(
      Primitive primitive, String algorithm, int keySize, String provider) {
    return new CryptoAsset(primitive, algorithm, null, null, keySize, provider, null, null);
  }

  /**
   * An asset without mode or padding.
   *
   * @param primitive how it was reached
   * @param algorithm algorithm name (canonicalized)
   * @param keySize key size in bits, or 0 when unknown
   * @param provider JCA provider name, or null
   * @param operation what was done with it, or null
   * @return the asset
   */
  public static CryptoAsset of(
      Primitive primitive, String algorithm, int keySize, String provider, Operation operation) {
    return new CryptoAsset(primitive, algorithm, null, null, keySize, provider, null, operation);
  }

  /**
   * A cipher asset from a JCA transformation, without an operation.
   *
   * @param transformation e.g. {@code AES/GCM/NoPadding}
   * @param keySize key size in bits, or 0 when unknown
   * @param provider JCA provider name, or null
   * @return the asset
   */
  public static CryptoAsset cipher(String transformation, int keySize, String provider) {
    return cipher(transformation, keySize, provider, null);
  }

  /**
   * Builds a cipher asset from a JCA transformation such as {@code AES/GCM/NoPadding}. A bare
   * algorithm ({@code "AES"}) leaves mode and padding {@code null}: the provider default applies.
   */
  public static CryptoAsset cipher(
      String transformation, int keySize, String provider, Operation operation) {
    String[] parts = Names.transformation(transformation).split("/", 3); // padding may contain '/'

    String algorithm = parts[0];
    String mode = parts.length > 1 ? parts[1] : null;
    String padding = parts.length > 2 ? parts[2] : null;
    // AES_256/GCM/NoPadding style names carry the key size in the algorithm
    int underscore = algorithm.indexOf('_');
    if (underscore > 0) {
      try {
        int named = Integer.parseInt(algorithm.substring(underscore + 1));
        algorithm = algorithm.substring(0, underscore);
        if (keySize == 0) {
          keySize = named;
        }
      } catch (NumberFormatException ignored) {
        // not a size suffix
      }
    }
    return new CryptoAsset(
        Primitive.CIPHER, algorithm, mode, padding, keySize, provider, null, operation);
  }

  /**
   * A negotiated TLS session shape.
   *
   * @param protocolVersion e.g. {@code TLSv1.3}
   * @param cipherSuite e.g. {@code TLS_AES_256_GCM_SHA384}
   * @return the asset
   */
  public static CryptoAsset tls(String protocolVersion, String cipherSuite) {
    return new CryptoAsset(Primitive.TLS, cipherSuite, null, null, 0, null, protocolVersion, null);
  }

  /** A short human-readable name, e.g. {@code AES-256-GCM} or {@code RSA-2048}. */
  public String displayName() {
    if (primitive == Primitive.TLS) {
      return protocolVersion + " " + algorithm;
    }
    StringBuilder sb = new StringBuilder(algorithm);
    if (keySize > 0 && !algorithm.toUpperCase(Locale.ROOT).contains(String.valueOf(keySize))) {
      sb.append('-').append(keySize);
    }
    if (mode != null && !"NONE".equalsIgnoreCase(mode)) {
      sb.append('-').append(mode);
    }
    return sb.toString();
  }

  /**
   * The CycloneDX {@code parameterSetIdentifier}: the key size when observed, otherwise the
   * parameter set carried in the name ({@code ML-KEM-768 -> 768}, {@code SHA-384 -> 384}).
   */
  public String parameterSet() {
    if (keySize > 0) {
      return String.valueOf(keySize);
    }
    Matcher m = TRAILING_PARAMETER.matcher(algorithm);
    return m.matches() ? m.group(1) : null;
  }

  /**
   * A stable identifier usable as a CycloneDX {@code bom-ref}. Every segment is escaped, so two
   * different assets can never produce the same reference.
   */
  public String ref() {
    return "crypto:"
        + String.join(
            ":",
            primitive.name().toLowerCase(Locale.ROOT),
            Escapes.encode(algorithm),
            Escapes.encode(nullToEmpty(protocolVersion)),
            Escapes.encode(nullToEmpty(mode)),
            Escapes.encode(nullToEmpty(padding)),
            String.valueOf(keySize),
            operation == null ? "" : operation.label())
        + "@"
        + Escapes.encode(nullToEmpty(provider));
  }

  private static String clean(String s) {
    if (s == null || s.isBlank()) {
      return null;
    }
    return Names.truncate(s.trim());
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
