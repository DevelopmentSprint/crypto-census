package io.github.cryptocensus.agent;

import javax.crypto.SecretKey;
import javax.crypto.interfaces.DHKey;
import java.lang.reflect.Method;
import java.security.cert.Certificate;
import java.security.interfaces.DSAKey;
import java.security.interfaces.ECKey;
import java.security.interfaces.EdECKey;
import java.security.interfaces.RSAKey;
import java.security.interfaces.XECKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.NamedParameterSpec;
import java.util.Arrays;
import java.util.Locale;

/**
 * Extracts key sizes and named parameter sets (X25519, ML-KEM-768, ...) from keys and certificates.
 *
 * <p><b>Rule: never touch key material we do not own.</b> The {@code Key} contract does not promise
 * that {@code getEncoded()} returns a copy, so a third-party key may hand us its backing array, and a
 * token key (PKCS#11) may perform device I/O and cache the exported secret. We therefore export only
 * keys of a few reviewed JDK classes ({@code SecretKeySpec}, SunJCE's DES/DESede keys: each returns a
 * defensive copy, which we then wipe), never touch PKCS#11 keys at all, and report every other secret
 * key's size as unknown.
 * Public-key sizes come from public parameters only.
 */
final class KeyInfo {

    /** {@code java.security.AsymmetricKey#getParams()}, JDK 22+. Used for ML-KEM / ML-DSA keys. */
    private static final Method ASYMMETRIC_GET_PARAMS = lookupAsymmetricGetParams();

    private KeyInfo() {
    }

    /** Key size in bits, or 0 when it cannot be determined safely. */
    static int size(Object keyOrCertificate) {
        Object key = keyOrCertificate instanceof Certificate
                ? ((Certificate) keyOrCertificate).getPublicKey()
                : keyOrCertificate;
        if (key == null || isTokenKey(key)) return 0;
        try {
            if (key instanceof RSAKey) return ((RSAKey) key).getModulus().bitLength();
            if (key instanceof ECKey) return ((ECKey) key).getParams().getCurve().getField().getFieldSize();
            if (key instanceof DSAKey) return ((DSAKey) key).getParams().getP().bitLength();
            if (key instanceof DHKey) return ((DHKey) key).getParams().getP().bitLength();
            String named = parameterSet(key);
            if (named != null) {
                switch (named.toUpperCase(Locale.ROOT)) {
                    case "X25519":
                    case "ED25519":
                        return 255;
                    case "X448":
                    case "ED448":
                        return 448;
                    default:
                        return 0;
                }
            }
            if (key instanceof SecretKey && isExportableSoftwareKey(key)) {
                return secretKeyBits((SecretKey) key);
            }
        } catch (RuntimeException ignored) {
            // destroyed keys, keys without parameters, provider quirks
        }
        return 0;
    }

    /**
     * Reviewed software key classes whose {@code getEncoded()} returns a fresh clone. Exact class
     * names, never subclasses (which could override getEncoded()) and never a module or class-loader
     * rule: PKCS#11 keys also live in a JDK module.
     */
    private static final java.util.Set<String> EXPORTABLE = java.util.Set.of(
            "javax.crypto.spec.SecretKeySpec",
            "com.sun.crypto.provider.DESKey",
            "com.sun.crypto.provider.DESedeKey");

    static boolean isExportableSoftwareKey(Object key) {
        Class<?> type = key.getClass();
        return EXPORTABLE.contains(type.getName()) && (type.getClassLoader() == null || type.getClassLoader() == ClassLoader.getPlatformClassLoader());
    }

    /** PKCS#11 keys: even public getters can trigger token I/O, so we do not inspect them at all. */
    static boolean isTokenKey(Object key) {
        return key.getClass().getName().startsWith("sun.security.pkcs11.");
    }

    private static int secretKeyBits(SecretKey key) {
        String algorithm = key.getAlgorithm() == null ? "" : key.getAlgorithm().toUpperCase(Locale.ROOT);
        if (algorithm.startsWith("PBE") || algorithm.startsWith("PBKDF")) {
            return 0; // encoded form is the password, not a key
        }
        if (!"RAW".equalsIgnoreCase(key.getFormat())) {
            return 0;
        }
        byte[] encoded = key.getEncoded();
        if (encoded == null) return 0;
        int bytes = encoded.length;
        Arrays.fill(encoded, (byte) 0); // our own copy: JDK keys clone on getEncoded()
        // DES keys carry one parity bit per byte
        if (algorithm.equals("DES")) return 56;
        if (algorithm.equals("DESEDE") || algorithm.equals("TRIPLEDES")) return bytes * 7;
        return bytes * 8;
    }

    /**
     * Replaces a family name with the concrete parameter set where the key reveals it:
     * {@code XDH -> X25519}, {@code ML-KEM -> ML-KEM-768}, {@code ML-DSA -> ML-DSA-65}.
     */
    static String refine(String algorithm, Object keyOrCertificate) {
        Object key = keyOrCertificate instanceof Certificate
                ? ((Certificate) keyOrCertificate).getPublicKey()
                : keyOrCertificate;
        if (key == null || isTokenKey(key)) return algorithm;
        String named = parameterSet(key);
        if (named == null || algorithm == null) return algorithm;
        String a = algorithm.toUpperCase(Locale.ROOT);
        String n = named.toUpperCase(Locale.ROOT);
        if (a.equals("XDH") || a.equals("EDDSA") || (n.startsWith(a) && n.length() > a.length())) {
            return named;
        }
        return algorithm;
    }

    static String parameterSet(Object key) {
        if (key == null) return null;
        try {
            AlgorithmParameterSpec spec = null;
            if (key instanceof XECKey) {
                spec = ((XECKey) key).getParams();
            } else if (key instanceof EdECKey) {
                spec = ((EdECKey) key).getParams();
            } else if (ASYMMETRIC_GET_PARAMS != null && ASYMMETRIC_GET_PARAMS.getDeclaringClass().isInstance(key)) {
                Object params = ASYMMETRIC_GET_PARAMS.invoke(key);
                if (params instanceof AlgorithmParameterSpec) spec = (AlgorithmParameterSpec) params;
            }
            return spec instanceof NamedParameterSpec ? ((NamedParameterSpec) spec).getName() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method lookupAsymmetricGetParams() {
        try {
            return Class.forName("java.security.AsymmetricKey").getMethod("getParams");
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
