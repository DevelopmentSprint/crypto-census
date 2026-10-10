package it.cryptocensus;

import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

class CryptoTest {
  @Test
  void recordsTwoCallSites() throws Exception {
    first();
    second();
  }

  private void first() throws Exception {
    MessageDigest.getInstance("SHA-256").digest(new byte[] {1});
  }

  private void second() throws Exception {
    MessageDigest.getInstance("SHA-256").digest(new byte[] {2});
  }
}
