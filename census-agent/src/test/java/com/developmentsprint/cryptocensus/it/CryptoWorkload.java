package com.developmentsprint.cryptocensus.it;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A deliberately mixed bag of good, bad and post-quantum cryptography, run in a child JVM under the
 * agent by {@link AgentIT}. Package {@code com.developmentsprint.cryptocensus.it} is outside the
 * agent's own packages, so it counts as application code for call-site attribution.
 */
public final class CryptoWorkload {

  /** IBM's provider in Semeru (and WebSphere Liberty on Semeru). */
  static final String OPEN_JCE_PLUS = "OpenJCEPlus";

  public static void main(String[] args) throws Exception {
    byte[] data = "transfer 100 EUR".getBytes(StandardCharsets.UTF_8);

    // AES-256-GCM: quantum-safe symmetric
    KeyGenerator aesGen = KeyGenerator.getInstance("AES");
    aesGen.init(256);
    SecretKey aes256 = aesGen.generateKey();
    Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
    gcm.init(Cipher.ENCRYPT_MODE, aes256, new GCMParameterSpec(128, new byte[12]));
    gcm.doFinal(data);

    // AES-128-ECB: weak mode
    Cipher ecb = Cipher.getInstance("AES/ECB/PKCS5Padding");
    ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[16], "AES"));
    ecb.doFinal(data);

    // Legacy digests
    MessageDigest.getInstance("MD5").digest(data);
    MessageDigest.getInstance("SHA-1").digest(data);
    MessageDigest.getInstance("SHA-384").digest(data);

    // RSA-2048 signature: quantum-vulnerable
    KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
    rsaGen.initialize(2048);
    KeyPair rsa = rsaGen.generateKeyPair();
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(rsa.getPrivate());
    signer.update(data);
    signer.sign();

    // X25519 key agreement: quantum-vulnerable
    KeyPairGenerator x25519Gen = KeyPairGenerator.getInstance("X25519");
    KeyPair alice = x25519Gen.generateKeyPair();
    KeyPair bob = x25519Gen.generateKeyPair();
    KeyAgreement ka = KeyAgreement.getInstance("XDH");
    ka.init(alice.getPrivate());
    ka.doPhase(bob.getPublic(), true);
    ka.generateSecret();

    // HMAC-SHA256
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
    mac.doFinal(data);

    auditRegressions(data, rsa);

    if (Runtime.version().feature() >= 24) {
      postQuantum(data);
    }
    if (java.security.Security.getProvider(OPEN_JCE_PLUS) != null) {
      openJcePlus();
    }
    if (args.length > 0) {
      loopbackTls(args[0], args[1]);
    }
  }

  /** One TLS 1.3 handshake against a local server using the given PKCS#12 keystore. */
  private static void loopbackTls(String keystore, String password) throws Exception {
    java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
    try (java.io.InputStream in = new java.io.FileInputStream(keystore)) {
      ks.load(in, password.toCharArray());
    }
    javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance("PKIX");
    kmf.init(ks, password.toCharArray());
    javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance("PKIX");
    tmf.init(ks);
    javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
    ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

    try (javax.net.ssl.SSLServerSocket server =
        (javax.net.ssl.SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0)) {
      Thread acceptor =
          new Thread(
              () -> {
                try (javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) server.accept()) {
                  s.getInputStream().read();
                } catch (java.io.IOException ignored) {
                  // client closed
                }
              });
      acceptor.start();
      try (javax.net.ssl.SSLSocket client =
          (javax.net.ssl.SSLSocket)
              ctx.getSocketFactory().createSocket("localhost", server.getLocalPort())) {
        client.startHandshake();
        client.getOutputStream().write(1);
      }
      acceptor.join(5000);
    }
  }

  /** Cases reproduced by the external audit. */
  private static void auditRegressions(byte[] data, KeyPair rsa) throws Exception {
    // A third-party SecretKey whose getEncoded() returns its backing array. (SunJCE itself wipes
    // that array during init, so key non-mutation by the agent is tested directly in KeyInfoTest.)
    byte[] backing = new byte[32];
    java.util.Arrays.fill(backing, (byte) 7);
    SecretKey custom =
        new SecretKey() {
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
        };
    Cipher cbc = Cipher.getInstance("AES/CBC/PKCS5Padding");
    cbc.init(Cipher.ENCRYPT_MODE, custom, new javax.crypto.spec.IvParameterSpec(new byte[16]));
    cbc.doFinal(data);

    // Bare "AES" is AES/ECB/PKCS5Padding in SunJCE
    Cipher bare = Cipher.getInstance("AES");
    bare.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[32], "AES"));
    bare.doFinal(data);

    // MD5 requested by OID (JDK 21+ registers OID aliases; JDK 17 does not, so the bypass cannot
    // happen there)
    try {
      MessageDigest.getInstance("1.2.840.113549.2.5").digest(data);
    } catch (java.security.NoSuchAlgorithmException notOnThisJdk) {
      // nothing to observe
    }

    // Signature aliases the policy table does not list: resolved through the provider
    KeyPairGenerator dsaGen = KeyPairGenerator.getInstance("DSA");
    dsaGen.initialize(1024); // the JDK refuses SHA1withDSA (DSS) with larger keys
    Signature dss = Signature.getInstance("DSS");
    dss.initSign(dsaGen.generateKeyPair().getPrivate());
    dss.update(data);
    dss.sign();
    try {
      Signature oid = Signature.getInstance("1.3.14.3.2.29"); // sha1WithRSA (old OIW OID)
      oid.initSign(rsa.getPrivate());
      oid.update(data);
      oid.sign();
    } catch (java.security.NoSuchAlgorithmException notOnThisJdk) {
      // nothing to observe
    }

    // OAEP with SHA-512/256: the padding name itself contains '/'
    Cipher oaep = Cipher.getInstance("RSA/ECB/OAEPWithSHA-512/256AndMGF1Padding");
    oaep.init(Cipher.ENCRYPT_MODE, rsa.getPublic());
    oaep.doFinal(new byte[16]);

    // HMAC with a third-party key: its size cannot be observed, so it must not be approved
    Mac customMac = Mac.getInstance("HmacSHA256");
    customMac.init(
        new SecretKey() {
          @Override
          public String getAlgorithm() {
            return "HmacSHA256";
          }

          @Override
          public String getFormat() {
            return "RAW";
          }

          @Override
          public byte[] getEncoded() {
            return new byte[1];
          }
        });
    customMac.doFinal(data);

    // HMAC with an 8-bit key
    Mac weakMac = Mac.getInstance("HmacSHA512");
    weakMac.init(new SecretKeySpec(new byte[1], "HmacSHA512"));
    weakMac.doFinal(data);

    // SHA-1 signature: generation is disallowed, verification is legacy use
    Signature sha1 = Signature.getInstance("SHA1withRSA");
    sha1.initSign(rsa.getPrivate());
    sha1.update(data);
    byte[] sig = sha1.sign();
    Signature sha1Verify = Signature.getInstance("SHA1withRSA");
    sha1Verify.initVerify(rsa.getPublic());
    sha1Verify.update(data);
    sha1Verify.verify(sig);

    // TDEA: encryption disallowed, decryption legacy use
    SecretKey tdea = KeyGenerator.getInstance("DESede").generateKey();
    Cipher tdeaEnc = Cipher.getInstance("DESede/CBC/PKCS5Padding");
    tdeaEnc.init(Cipher.ENCRYPT_MODE, tdea);
    byte[] ct = tdeaEnc.doFinal(data);
    Cipher tdeaDec = Cipher.getInstance("DESede/CBC/PKCS5Padding");
    tdeaDec.init(Cipher.DECRYPT_MODE, tdea, new javax.crypto.spec.IvParameterSpec(tdeaEnc.getIV()));
    tdeaDec.doFinal(ct);
  }

  /**
   * DSA key generation through OpenJCEPlus (Semeru). Its DSAKeyPairGenerator extends
   * KeyPairGenerator directly and is defined by the application class loader, so its own frame is
   * on the stack when the agent records the call.
   */
  private static void openJcePlus() throws Exception {
    KeyPairGenerator dsa = KeyPairGenerator.getInstance("DSA", OPEN_JCE_PLUS);
    dsa.initialize(2048);
    dsa.generateKeyPair();
  }

  /**
   * ML-KEM and ML-DSA (JDK 24+). Uses reflection for javax.crypto.KEM because this compiles for 17.
   */
  private static void postQuantum(byte[] data) throws Exception {
    KeyPairGenerator kemGen = KeyPairGenerator.getInstance("ML-KEM");
    kemGen.initialize(new java.security.spec.NamedParameterSpec("ML-KEM-768"));
    KeyPair kemKeys = kemGen.generateKeyPair();
    Class<?> kemClass = Class.forName("javax.crypto.KEM");
    Object kem = kemClass.getMethod("getInstance", String.class).invoke(null, "ML-KEM");
    Object encapsulator =
        kemClass.getMethod("newEncapsulator", PublicKey.class).invoke(kem, kemKeys.getPublic());
    encapsulator.getClass().getMethod("encapsulate").invoke(encapsulator);

    KeyPair dsaKeys = KeyPairGenerator.getInstance("ML-DSA-65").generateKeyPair();
    Signature mldsa = Signature.getInstance("ML-DSA");
    mldsa.initSign(dsaKeys.getPrivate());
    mldsa.update(data);
    mldsa.sign();
  }
}
