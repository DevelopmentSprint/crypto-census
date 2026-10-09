package it.cryptocensus;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;

class LegacyTest {

    @Test
    void legacyChecksum() throws Exception {
        MessageDigest.getInstance("MD5").digest(new byte[16]);
    }
}
