package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.Occurrence;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CallsitesTest {

  private static Callsites.Frame frame(String cls, String method, int line) {
    return new Callsites.Frame(cls, method, line);
  }

  @Test
  void skipsJcaAndProviderFramesToFindApplicationCode() {
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                frame("com.developmentsprint.cryptocensus.agent.Recorder", "exit", 1),
                frame("javax.crypto.Cipher", "init", 2),
                frame("com.sun.crypto.provider.AESCipher", "engineInit", 3),
                frame("com.acme.payments.Vault", "seal", 42),
                frame("com.acme.payments.Api", "pay", 7)));

    assertThat(o).isEqualTo(new Occurrence("com.acme.payments.Vault", "seal", 42, null));
  }

  @Test
  void reportsTlsAsTheSubsystemWhenJsseIsInBetween() {
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                frame("javax.crypto.KeyAgreement", "init", 1),
                frame("sun.security.ssl.XDHKeyExchange$XDHEKAGenerator", "createPossession", 2),
                frame("sun.security.ssl.ClientHello$ClientHelloKickstartProducer", "produce", 3),
                frame("java.net.http.HttpClient", "send", 4),
                frame("com.acme.fx.RatesClient", "fetch", 99)));

    assertThat(o.location()).isEqualTo("com.acme.fx.RatesClient");
    assertThat(o.via()).isEqualTo("jsse");
  }

  @Test
  void ignoresReflectionWhenNamingTheSubsystem() {
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                frame("java.security.MessageDigest", "getInstance", 1),
                frame("jdk.internal.reflect.DirectMethodHandleAccessor", "invoke", 2),
                frame("java.lang.reflect.Method", "invoke", 3),
                frame("com.acme.Legacy", "hash", 5)));

    assertThat(o.via()).isNull();
  }

  @Test
  void skipsOpenJcePlusKeyPairGeneratorFrames() {
    // Reproduced on IBM Semeru 21: OpenJCEPlus's DSAKeyPairGenerator extends KeyPairGenerator, so
    // its own generateKeyPair is the instrumented frame, and OpenJCEPlus is defined by the
    // application class loader. It used to be reported as the application call site.
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                frame("com.developmentsprint.cryptocensus.agent.Recorder", "exit", 1),
                frame("com.ibm.crypto.plus.provider.DSAKeyPairGenerator", "generateKeyPair", 160),
                frame("com.acme.payments.Keys", "rotate", 12)));

    assertThat(o).isEqualTo(new Occurrence("com.acme.payments.Keys", "rotate", 12, null));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "com.ibm.crypto.provider.AESCipher", // IBMJCE (WebSphere, IBM SDK)
        "com.ibm.crypto.fips.provider.AESCipher", // IBMJCEFIPS
        "com.ibm.crypto.plus.provider.AESCipher", // OpenJCEPlus / OpenJCEPlusFIPS
        "com.ibm.crypto.pkcs11impl.provider.Cipher", // IBMPKCS11Impl
        "sun.security.pkcs11.P11Cipher", // SunPKCS11, NSS FIPS mode
        "sun.security.mscapi.CSignature$RSA", // SunMSCAPI
        "apple.security.KeychainStore",
        "org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider$CoreSecureRandom", // BC-FIPS
        "com.amazon.corretto.crypto.provider.AesGcmSpi",
        "com.amazonaws.cloudhsm.jce.provider.CloudHsmCipher",
        "com.safenetinc.luna.provider.cipher.LunaCipher",
        "com.ncipher.provider.km.nCipherKM",
        "com.wolfssl.provider.jce.WolfCryptCipher",
        "org.conscrypt.OpenSSLCipher$EVP_AEAD$AES$GCM",
      })
  void neverReportsProviderFramesAsTheCallSite(String providerClass) {
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                frame("javax.crypto.Cipher", "init", 1),
                frame(providerClass, "engineInit", 2),
                frame("com.acme.payments.Vault", "seal", 42)));

    assertThat(o).isEqualTo(new Occurrence("com.acme.payments.Vault", "seal", 42, null));
  }

  @Test
  void ibmApplicationCodeIsStillApplicationCode() {
    // only provider packages are skipped: WebSphere/Liberty runtime classes are callers
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                frame("javax.crypto.Cipher", "init", 1),
                frame("com.ibm.ws.crypto.util.AESKeyManager", "encrypt", 7),
                frame("com.ibm.cryptography.Demo", "run", 3)));

    assertThat(o.location()).isEqualTo("com.ibm.ws.crypto.util.AESKeyManager");
  }

  @Test
  void attributesBouncyCastleTlsLikeJsse() {
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                frame("javax.crypto.Cipher", "init", 1),
                frame("org.bouncycastle.tls.crypto.impl.jcajce.JceAEADCipherImpl", "init", 2),
                frame("org.bouncycastle.tls.TlsProtocol", "safeReadRecord", 3),
                frame("org.bouncycastle.jsse.provider.ProvSSLSocketWrap", "startHandshake", 4),
                frame("com.acme.fx.RatesClient", "fetch", 99)));

    assertThat(o).isEqualTo(new Occurrence("com.acme.fx.RatesClient", "fetch", 99, "jsse"));
  }

  @Test
  void fallsBackToJdkLocationWhenNoApplicationFrame() {
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                frame("java.security.MessageDigest", "getInstance", 1),
                frame("sun.security.util.SignatureFileVerifier", "process", 2),
                frame("java.util.jar.JarFile", "getInputStream", 3)));

    assertThat(o.location()).isEqualTo(Occurrence.JDK_LOCATION);
    assertThat(o.via()).isEqualTo("jar-verification");
  }
}
