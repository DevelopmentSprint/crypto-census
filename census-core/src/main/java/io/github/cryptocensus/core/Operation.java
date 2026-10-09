package io.github.cryptocensus.core;

import java.util.Locale;

/**
 * What the application did with an algorithm, where the JCA tells us.
 *
 * <p>SP 800-131A distinguishes applying protection (encrypt, sign) from processing already-protected
 * data (decrypt, verify): the latter is often still permitted as "legacy use" after an algorithm is
 * disallowed for new protection.
 */
public enum Operation {
    ENCRYPT, DECRYPT, WRAP, UNWRAP, SIGN, VERIFY;

    /** True for operations that create new protected data. */
    public boolean protects() {
        return this == ENCRYPT || this == WRAP || this == SIGN;
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Maps {@code Cipher.ENCRYPT_MODE} (1) .. {@code Cipher.UNWRAP_MODE} (4). */
    public static Operation ofCipherMode(int opmode) {
        switch (opmode) {
            case 1: return ENCRYPT;
            case 2: return DECRYPT;
            case 3: return WRAP;
            case 4: return UNWRAP;
            default: return null;
        }
    }
}
