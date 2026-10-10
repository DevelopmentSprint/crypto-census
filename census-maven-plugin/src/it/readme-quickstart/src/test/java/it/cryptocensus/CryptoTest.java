package it.cryptocensus;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyPairGenerator;
import java.security.Signature;

class CryptoTest {

    @Test
    void usesSomeCrypto() throws Exception {
        KeyGenerator aes = KeyGenerator.getInstance("AES");
        aes.init(256);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE, aes.generateKey(), new GCMParameterSpec(128, new byte[12]));
        gcm.doFinal(new byte[16]);

        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(rsa.generateKeyPair().getPrivate());
        signer.update(new byte[16]);
        signer.sign();
    }
}
