package com.developmentsprint.cryptocensus.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical algorithm names.
 *
 * <p>The JCA accepts many spellings for one algorithm: {@code SHA}, {@code SHA1}, {@code SHA-1},
 * {@code OID.1.3.14.3.2.26}, {@code 1.3.14.3.2.26}, and even names padded with whitespace.
 * {@code getAlgorithm()} returns whatever the caller passed in. The policy must see one spelling,
 * otherwise an alias silently becomes "unknown" and passes every enforcement threshold.
 */
public final class Names {

    /** Longest name (algorithm, provider, class) we keep; longer values are truncated so caller-controlled strings cannot bloat the inventory. */
    public static final int MAX_LENGTH = 256;

    private static final Map<String, String> OIDS = new HashMap<>();
    private static final Map<String, String> DIGESTS = new HashMap<>();
    private static final Pattern SIGNATURE = Pattern.compile("(.+?)WITH(.+)", Pattern.CASE_INSENSITIVE);

    static {
        // digests (RFC 3279, RFC 5754, NIST CSOR)
        oid("1.2.840.113549.2.2", "MD2");
        oid("1.2.840.113549.2.5", "MD5");
        oid("1.3.14.3.2.26", "SHA-1");
        oid("2.16.840.1.101.3.4.2.4", "SHA-224");
        oid("2.16.840.1.101.3.4.2.1", "SHA-256");
        oid("2.16.840.1.101.3.4.2.2", "SHA-384");
        oid("2.16.840.1.101.3.4.2.3", "SHA-512");
        oid("2.16.840.1.101.3.4.2.5", "SHA-512/224");
        oid("2.16.840.1.101.3.4.2.6", "SHA-512/256");
        oid("2.16.840.1.101.3.4.2.7", "SHA3-224");
        oid("2.16.840.1.101.3.4.2.8", "SHA3-256");
        oid("2.16.840.1.101.3.4.2.9", "SHA3-384");
        oid("2.16.840.1.101.3.4.2.10", "SHA3-512");
        // HMAC (RFC 4231, RFC 8018)
        oid("1.2.840.113549.2.7", "HmacSHA1");
        oid("1.2.840.113549.2.8", "HmacSHA224");
        oid("1.2.840.113549.2.9", "HmacSHA256");
        oid("1.2.840.113549.2.10", "HmacSHA384");
        oid("1.2.840.113549.2.11", "HmacSHA512");
        // signatures (RFC 8017, RFC 5758)
        oid("1.2.840.113549.1.1.4", "MD5withRSA");
        oid("1.2.840.113549.1.1.5", "SHA1withRSA");
        oid("1.2.840.113549.1.1.14", "SHA224withRSA");
        oid("1.2.840.113549.1.1.11", "SHA256withRSA");
        oid("1.2.840.113549.1.1.12", "SHA384withRSA");
        oid("1.2.840.113549.1.1.13", "SHA512withRSA");
        oid("1.2.840.113549.1.1.10", "RSASSA-PSS");
        oid("1.2.840.10045.4.1", "SHA1withECDSA");
        oid("1.2.840.10045.4.3.1", "SHA224withECDSA");
        oid("1.2.840.10045.4.3.2", "SHA256withECDSA");
        oid("1.2.840.10045.4.3.3", "SHA384withECDSA");
        oid("1.2.840.10045.4.3.4", "SHA512withECDSA");
        oid("1.2.840.10040.4.3", "SHA1withDSA");
        oid("1.3.101.112", "Ed25519");
        oid("1.3.101.113", "Ed448");
        // key agreement / key types
        oid("1.3.101.110", "X25519");
        oid("1.3.101.111", "X448");
        oid("1.2.840.113549.1.1.1", "RSA");
        oid("1.2.840.10045.2.1", "EC");
        // FIPS 203 / 204 (NIST CSOR)
        oid("2.16.840.1.101.3.4.4.1", "ML-KEM-512");
        oid("2.16.840.1.101.3.4.4.2", "ML-KEM-768");
        oid("2.16.840.1.101.3.4.4.3", "ML-KEM-1024");
        oid("2.16.840.1.101.3.4.3.17", "ML-DSA-44");
        oid("2.16.840.1.101.3.4.3.18", "ML-DSA-65");
        oid("2.16.840.1.101.3.4.3.19", "ML-DSA-87");

        digest("SHA", "SHA-1");
        digest("SHA1", "SHA-1");
        digest("SHA224", "SHA-224");
        digest("SHA256", "SHA-256");
        digest("SHA384", "SHA-384");
        digest("SHA512", "SHA-512");
        digest("SHA512/224", "SHA-512/224");
        digest("SHA512/256", "SHA-512/256");
    }

    private Names() {
    }

    private static void oid(String oid, String name) {
        OIDS.put(oid, name);
    }

    private static void digest(String alias, String name) {
        DIGESTS.put(alias, name);
    }

    /**
     * Canonical name for an algorithm reached through {@code primitive}: OIDs resolved, whitespace
     * removed, digest aliases unified, and the hash part of {@code <hash>with<key>} signatures
     * normalized ({@code SHA-256withRSA -> SHA256withRSA}).
     */
    public static String canonical(Primitive primitive, String algorithm) {
        if (algorithm == null) return null;
        // strip whitespace from the whole string first: truncating first could cut "AES/   ...ECB" down to "AES/E"
        String a = truncate(algorithm.replaceAll("\\s+", ""));
        String oid = a.regionMatches(true, 0, "OID.", 0, 4) ? a.substring(4) : a;
        String resolved = OIDS.get(oid);
        if (resolved != null) a = resolved;

        if (primitive == Primitive.MESSAGE_DIGEST) {
            return DIGESTS.getOrDefault(a.toUpperCase(Locale.ROOT), a);
        }
        if (primitive == Primitive.SIGNATURE) {
            Matcher m = SIGNATURE.matcher(a);
            if (m.matches()) {
                String hash = m.group(1).replace("-", "").toUpperCase(Locale.ROOT);
                return hash + "with" + m.group(2);
            }
        }
        return a;
    }

    /** Applies {@link #canonical} to each part of a {@code alg/mode/padding} transformation. */
    public static String transformation(String transformation) {
        if (transformation == null) return null;
        // limit 3: the padding may itself contain '/' (OAEPWithSHA-512/256AndMGF1Padding)
        String[] parts = truncate(transformation.replaceAll("\\s+", "")).split("/", 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(i == 0 ? canonical(Primitive.CIPHER, parts[i]) : parts[i]);
        }
        return sb.toString();
    }

    /**
     * Truncates to {@link #MAX_LENGTH} chars without splitting a surrogate pair, and replaces any
     * unpaired surrogate with U+FFFD. Otherwise UTF-8 encoding would silently turn it into '?', and two
     * different names could collide in a {@code bom-ref}.
     */
    public static String truncate(String s) {
        if (s == null) return null;
        String t = s;
        if (t.length() > MAX_LENGTH) {
            int end = MAX_LENGTH;
            if (Character.isHighSurrogate(t.charAt(end - 1))) end--;
            t = t.substring(0, end);
        }
        return wellFormed(t);
    }

    static String wellFormed(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean paired = Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
            boolean lone = Character.isSurrogate(c) && !paired && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
            if (lone) {
                if (sb == null) sb = new StringBuilder(s.substring(0, i));
                sb.append('\uFFFD');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }
}
