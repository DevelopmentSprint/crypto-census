package it.cryptocensus;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

class ModernIT {

    @Test
    void modernCipher() throws Exception {
        KeyGenerator aes = KeyGenerator.getInstance("AES");
        aes.init(256);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, aes.generateKey(), new GCMParameterSpec(128, new byte[12]));
        gcm.doFinal(new byte[16]);
    }
}
