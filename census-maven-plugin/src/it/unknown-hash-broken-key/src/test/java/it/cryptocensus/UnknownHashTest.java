package it.cryptocensus;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPairGenerator;
import java.security.Signature;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

class UnknownHashTest {
    @Test
    void unknownHashDoesNotHideBrokenSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        var key = generator.generateKeyPair();
        var provider = new BouncyCastleProvider();
        Signature signer = Signature.getInstance("RIPEMD160withRSA", provider);
        signer.initSign(key.getPrivate());
        signer.update(new byte[32]);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("RIPEMD160withRSA", provider);
        verifier.initVerify(key.getPublic());
        verifier.update(new byte[32]);
        assertTrue(verifier.verify(signature));
    }
}
