package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.Occurrence;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

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
