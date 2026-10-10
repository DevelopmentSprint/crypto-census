package it.cryptocensus;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import org.junit.jupiter.api.Test;

class RawSignatureTest {
    @Test
    void rawEcdsaHashIsNotObservable() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var key = generator.generateKeyPair();
        byte[] message = new byte[64];
        Signature signer = Signature.getInstance("NONEwithECDSA");
        signer.initSign(key.getPrivate());
        signer.update(message);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("NONEwithECDSA");
        verifier.initVerify(key.getPublic());
        verifier.update(message);
        assertTrue(verifier.verify(signature));
    }
}
