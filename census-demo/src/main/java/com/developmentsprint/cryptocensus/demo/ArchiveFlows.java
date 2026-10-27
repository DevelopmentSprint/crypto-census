package com.developmentsprint.cryptocensus.demo;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.annotations.CryptoPurpose;
import com.developmentsprint.cryptocensus.annotations.DataProtection;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import javax.crypto.Cipher;

/** Synthetic key-protection workloads; this is an inventory demonstration, not a storage design. */
public final class ArchiveFlows {
  private final KeyPair keyPair;

  /** Creates an ephemeral RSA key pair for the demonstration. */
  public ArchiveFlows() throws GeneralSecurityException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keyPair = generator.generateKeyPair();
  }

  /** A long-lived confidential flow calling the same helper as a short-lived export. */
  @CryptoFlow(value = "records-archive", purpose = CryptoPurpose.KEY_PROTECTION)
  @DataProtection(classification = "sensitive", confidentialityYears = 20)
  public byte[] archiveKey(byte[] syntheticKey) throws GeneralSecurityException {
    return protect(syntheticKey);
  }

  /** A specifically declared short-lived, public export. */
  @CryptoFlow(value = "daily-export", purpose = CryptoPurpose.KEY_PROTECTION)
  @DataProtection(classification = "public", confidentialityYears = 1)
  public byte[] exportKey(byte[] syntheticKey) throws GeneralSecurityException {
    return protect(syntheticKey);
  }

  /** Unmodified-style call site for guided binding and no-code context entry. */
  public byte[] protectWithoutContext(byte[] syntheticKey) throws GeneralSecurityException {
    return protect(syntheticKey);
  }

  /** The annotation covers submission, not execution on the supplied worker thread. */
  @CryptoFlow(value = "async-archive", purpose = CryptoPurpose.KEY_PROTECTION)
  @DataProtection(classification = "sensitive", confidentialityYears = 20)
  public Future<byte[]> archiveLater(byte[] syntheticKey, ExecutorService worker) {
    byte[] owned = syntheticKey.clone();
    return worker.submit(() -> protect(owned));
  }

  private byte[] protect(byte[] syntheticKey) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
    cipher.init(Cipher.ENCRYPT_MODE, keyPair.getPublic());
    return cipher.doFinal(syntheticKey);
  }
}
