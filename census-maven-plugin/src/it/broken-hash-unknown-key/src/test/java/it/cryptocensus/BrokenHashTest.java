package it.cryptocensus;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPairGenerator;
import java.security.Signature;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

class BrokenHashTest {
    @Test
    void unknownKeyTypeDoesNotHideBrokenSignatureHash() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var key = generator.generateKeyPair();
        var provider = new BouncyCastleProvider();
        Signature signer = Signature.getInstance("MD5withRSA/ISO9796-2", provider);
        signer.initSign(key.getPrivate());
        signer.update(new byte[32]);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("MD5withRSA/ISO9796-2", provider);
        verifier.initVerify(key.getPublic());
        verifier.update(new byte[32]);
        assertTrue(verifier.verify(signature));
    }
}
