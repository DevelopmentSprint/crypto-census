package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class KeyInfoTest {

  /** A key that, legally under the Key contract, hands out its backing array. */
  private static final class LeakyKey implements SecretKey {
    final byte[] backing;

    LeakyKey(byte[] backing) {
      this.backing = backing;
    }

    @Override
    public String getAlgorithm() {
      return "AES";
    }

    @Override
    public String getFormat() {
      return "RAW";
    }

    @Override
    public byte[] getEncoded() {
      return backing;
    }
  }

  @Test
  void neverReadsOrWipesKeysItDoesNotOwn() {
    // regression for the external audit: size() used to zero whatever getEncoded() returned
    byte[] backing = new byte[32];
    Arrays.fill(backing, (byte) 7);
    LeakyKey key = new LeakyKey(backing);

    assertThat(KeyInfo.size(key)).isZero();

    assertThat(backing).containsOnly((byte) 7);
  }

  @Test
  void doesNotTrustSubclassesOfSecretKeySpec() {
    // a subclass can override getEncoded() to hand out its backing array
    byte[] backing = new byte[32];
    Arrays.fill(backing, (byte) 7);
    SecretKeySpec sneaky =
        new SecretKeySpec(new byte[32], "AES") {
          @Override
          public byte[] getEncoded() {
            return backing;
          }
        };
    assertThat(KeyInfo.size(sneaky)).isZero();
    assertThat(backing).containsOnly((byte) 7);
  }

  @Test
  void sizesJdkSecretKeys() throws Exception {
    assertThat(KeyInfo.size(new SecretKeySpec(new byte[32], "AES"))).isEqualTo(256);
    KeyGenerator tdea = KeyGenerator.getInstance("DESede");
    assertThat(KeyInfo.size(tdea.generateKey())).isEqualTo(168);
    assertThat(KeyInfo.size(new SecretKeySpec(new byte[8], "DES"))).isEqualTo(56);
  }

  @Test
  void doesNotTreatPasswordsAsKeys() throws Exception {
    SecretKey pbe =
        javax.crypto.SecretKeyFactory.getInstance("PBEWithHmacSHA256AndAES_256")
            .generateSecret(new javax.crypto.spec.PBEKeySpec("correct horse".toCharArray()));
    assertThat(KeyInfo.size(pbe)).isZero();
  }
}
