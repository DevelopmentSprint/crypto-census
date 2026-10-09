package com.developmentsprint.cryptocensus.demo;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;

/**
 * Signs payment instructions. RSA-2048 is today's bank default, and exactly what a
 * cryptographically relevant quantum computer breaks.
 */
public final class PaymentSigner {

  private final KeyPair keys;

  /**
   * Generates a fresh RSA-2048 key pair.
   *
   * @throws GeneralSecurityException if RSA is unavailable
   */
  public PaymentSigner() throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    this.keys = generator.generateKeyPair();
  }

  /**
   * Signs a payment instruction.
   *
   * @param instruction the instruction text
   * @return the SHA256withRSA signature
   * @throws GeneralSecurityException on signing failure
   */
  public byte[] sign(String instruction) throws GeneralSecurityException {
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keys.getPrivate());
    signature.update(instruction.getBytes(StandardCharsets.UTF_8));
    return signature.sign();
  }

  /**
   * Verifies a signature produced by {@link #sign(String)}.
   *
   * @param instruction the instruction text
   * @param sig the signature
   * @return whether it verifies
   * @throws GeneralSecurityException on verification failure
   */
  public boolean verify(String instruction, byte[] sig) throws GeneralSecurityException {
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initVerify(keys.getPublic());
    signature.update(instruction.getBytes(StandardCharsets.UTF_8));
    return signature.verify(sig);
  }
}
