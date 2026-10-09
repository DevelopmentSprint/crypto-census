package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Operation;
import com.developmentsprint.cryptocensus.core.Primitive;
import java.lang.reflect.Proxy;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the recorder turns an observed call into, and how it behaves when the call or the recording
 * goes wrong. Each test gets a fresh inventory.
 */
class RecorderRecordingTest {

  @BeforeEach
  void freshInventory() {
    Recorder.configure(500, 50);
    Recorder.enable();
  }

  @AfterEach
  void disable() {
    Recorder.disable();
  }

  /** Simulates one instrumented call: entry advice, the call itself, exit advice. */
  private static void observe(Object self, Object arg, Object detail) {
    Recorder.exit(Recorder.enter(), self, arg, detail, null);
  }

  private static List<CryptoAsset> assets() {
    return Recorder.inventory().entries().stream().map(Inventory.Entry::asset).toList();
  }

  @Test
  void recordsEachJcaPrimitive() throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    observe(cipher, new SecretKeySpec(new byte[32], "AES"), Cipher.ENCRYPT_MODE);
    Signature signature = Signature.getInstance("SHA256withECDSA");
    observe(signature, null, "initVerify");
    Mac mac = Mac.getInstance("HmacSHA256");
    observe(mac, new SecretKeySpec(new byte[32], "HmacSHA256"), null);
    KeyAgreement agreement = KeyAgreement.getInstance("XDH");
    observe(agreement, KeyPairGenerator.getInstance("X25519").generateKeyPair().getPrivate(), null);
    KeyGenerator generator = KeyGenerator.getInstance("AES");
    observe(generator, new SecretKeySpec(new byte[16], "AES"), null);
    KeyPairGenerator pairs = KeyPairGenerator.getInstance("X448");
    observe(pairs, pairs.generateKeyPair(), null);
    observe(MessageDigest.getInstance("SHA"), null, null);

    assertThat(assets())
        .extracting(
            CryptoAsset::primitive,
            CryptoAsset::algorithm,
            CryptoAsset::keySize,
            CryptoAsset::operation)
        .containsExactlyInAnyOrder(
            tuple(Primitive.CIPHER, "AES", 256, Operation.ENCRYPT),
            tuple(Primitive.SIGNATURE, "SHA256withECDSA", 0, Operation.VERIFY),
            tuple(Primitive.MAC, "HmacSHA256", 256, null),
            tuple(Primitive.KEY_AGREEMENT, "X25519", 255, null),
            tuple(Primitive.KEY_GENERATION, "AES", 128, null),
            tuple(Primitive.KEY_PAIR_GENERATION, "X448", 448, null),
            tuple(Primitive.MESSAGE_DIGEST, "SHA-1", 0, null));
    assertThat(assets())
        .filteredOn(a -> a.primitive() == Primitive.CIPHER)
        .singleElement()
        .satisfies(a -> assertThat(a.mode()).isEqualTo("GCM"));
  }

  @Test
  void signatureOperationFollowsTheInitMethod() throws Exception {
    observe(Signature.getInstance("SHA256withRSA"), null, "initSign");
    observe(Signature.getInstance("SHA384withRSA"), null, "update");
    assertThat(assets())
        .extracting(CryptoAsset::algorithm, CryptoAsset::operation)
        .containsExactlyInAnyOrder(
            tuple("SHA256withRSA", Operation.SIGN), tuple("SHA384withRSA", null));
  }

  @Test
  void ignoresCallsThatThrewOrHaveNothingToRecord() throws Exception {
    int depth = Recorder.enter();
    Recorder.exit(
        depth, MessageDigest.getInstance("MD5"), null, null, new IllegalStateException("boom"));
    observe(null, null, null);
    observe("not a JCA object", null, null);
    assertThat(assets()).isEmpty();
    assertThat(Recorder.inFlight()).isZero();
  }

  @Test
  void disabledRecorderAdmitsNothing() throws Exception {
    Recorder.disable();
    int depth = Recorder.enter();
    assertThat(depth).isZero();
    Recorder.exit(depth, MessageDigest.getInstance("MD5"), null, null, null);
    Recorder.constructed(session("TLSv1.3", "TLS_AES_128_GCM_SHA256"));
    assertThat(assets()).isEmpty();
    assertThat(Recorder.inFlight()).isZero();
  }

  @Test
  void failureWhileRecordingIsCountedAndSwallowed() throws Exception {
    long before = Recorder.failures();
    Certificate broken =
        new Certificate("broken") {
          private static final long serialVersionUID = 1L;

          @Override
          public PublicKey getPublicKey() {
            throw new IllegalStateException("provider bug");
          }

          @Override
          public byte[] getEncoded() {
            return new byte[0];
          }

          @Override
          public void verify(PublicKey key) {}

          @Override
          public void verify(PublicKey key, String sigProvider) {}

          @Override
          public String toString() {
            return "broken";
          }
        };
    observe(Signature.getInstance("SHA256withRSA"), broken, "initVerify");
    assertThat(Recorder.failures()).isEqualTo(before + 1);
    assertThat(Recorder.inFlight()).isZero();
    // the thread's nesting depth was restored, so the next call is outermost again
    assertThat(Recorder.enter()).isEqualTo(1);
    Recorder.exit(1, null, null, null, null);
  }

  @Test
  void recordsNegotiatedTlsSessionsOnceAndSkipsPlaceholders() {
    SSLSession session = session("TLSv1.3", "TLS_AES_256_GCM_SHA384");
    Recorder.constructed(session);
    Recorder.constructed(session); // chained constructor of the same object
    Recorder.constructed(session("NONE", "SSL_NULL_WITH_NULL_NULL"));
    Recorder.constructed(session("TLSv1.2", "SSL_NULL_WITH_NULL_NULL"));
    Recorder.constructed(session(null, null));

    assertThat(Recorder.inventory().entries())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.asset()).isEqualTo(CryptoAsset.tls("TLSv1.3", "TLS_AES_256_GCM_SHA384"));
              assertThat(e.total()).isEqualTo(1L);
              assertThat(e.occurrences().keySet())
                  .allSatisfy(o -> assertThat(o.via()).isEqualTo("jsse"));
            });
    assertThat(Recorder.inFlight()).isZero();
  }

  @Test
  void constructionInsideAnotherInstrumentedCallIsNotRecordedSeparately() {
    int depth = Recorder.enter();
    Recorder.constructed(session("TLSv1.3", "TLS_AES_128_GCM_SHA256"));
    Recorder.exit(depth, null, null, null, null);
    assertThat(assets()).isEmpty();
  }

  @Test
  void recordsKemOnJdk21() throws Exception {
    assumeTrue(Runtime.version().feature() >= 21, "javax.crypto.KEM is JDK 21+");
    Object kem =
        Class.forName("javax.crypto.KEM")
            .getMethod("getInstance", String.class)
            .invoke(null, "DHKEM");
    observe(kem, KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic(), null);
    assertThat(assets())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.primitive()).isEqualTo(Primitive.KEM);
              assertThat(a.algorithm()).isEqualTo("DHKEM");
              assertThat(a.keySize()).isEqualTo(255);
            });
  }

  /** An SSLSession that only answers the two questions the recorder asks. */
  @SuppressWarnings("ReferenceEquality") // identity is exactly Object.equals for a session
  private static SSLSession session(String protocol, String suite) {
    return (SSLSession)
        Proxy.newProxyInstance(
            SSLSession.class.getClassLoader(),
            new Class<?>[] {SSLSession.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "getProtocol" -> protocol;
                  case "getCipherSuite" -> suite;
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == args[0];
                  default -> null;
                });
  }
}
