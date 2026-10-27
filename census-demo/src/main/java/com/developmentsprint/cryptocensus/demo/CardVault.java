package com.developmentsprint.cryptocensus.demo;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Demonstrates AES-256-GCM with identity-only context; this does not establish system readiness.
 */
public final class CardVault {

  private static final int IV_BYTES = 12;
  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  /**
   * Generates a fresh AES-256 key.
   *
   * @throws GeneralSecurityException if AES is unavailable
   */
  public CardVault() throws GeneralSecurityException {
    KeyGenerator generator = KeyGenerator.getInstance("AES");
    generator.init(256);
    this.key = generator.generateKey();
  }

  /**
   * Encrypts a card number.
   *
   * @param pan the primary account number
   * @return IV followed by AES-256-GCM ciphertext
   * @throws GeneralSecurityException on encryption failure
   */
  @CryptoFlow("card-vault")
  public byte[] seal(String pan) throws GeneralSecurityException {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
    byte[] ct = cipher.doFinal(pan.getBytes(StandardCharsets.UTF_8));
    return ByteBuffer.allocate(IV_BYTES + ct.length).put(iv).put(ct).array();
  }

  /**
   * Decrypts a value produced by {@link #seal(String)}.
   *
   * @param sealed IV followed by ciphertext
   * @return the card number
   * @throws GeneralSecurityException if decryption or authentication fails
   */
  public String open(byte[] sealed) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, sealed, 0, IV_BYTES));
    return new String(
        cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES), StandardCharsets.UTF_8);
  }
}
