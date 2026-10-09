package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.Occurrence;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CallsitesTest {

  private static Callsites.Frame f(String cls, String method, int line) {
    return new Callsites.Frame(cls, method, line);
  }

  @Test
  void skipsJcaAndProviderFramesToFindApplicationCode() {
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                f("com.developmentsprint.cryptocensus.agent.Recorder", "exit", 1),
                f("javax.crypto.Cipher", "init", 2),
                f("com.sun.crypto.provider.AESCipher", "engineInit", 3),
                f("com.acme.payments.Vault", "seal", 42),
                f("com.acme.payments.Api", "pay", 7)));

    assertThat(o).isEqualTo(new Occurrence("com.acme.payments.Vault", "seal", 42, null));
  }

  @Test
  void reportsTlsAsTheSubsystemWhenJsseIsInBetween() {
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                f("javax.crypto.KeyAgreement", "init", 1),
                f("sun.security.ssl.XDHKeyExchange$XDHEKAGenerator", "createPossession", 2),
                f("sun.security.ssl.ClientHello$ClientHelloKickstartProducer", "produce", 3),
                f("java.net.http.HttpClient", "send", 4),
                f("com.acme.fx.RatesClient", "fetch", 99)));

    assertThat(o.location()).isEqualTo("com.acme.fx.RatesClient");
    assertThat(o.via()).isEqualTo("jsse");
  }

  @Test
  void ignoresReflectionWhenNamingTheSubsystem() {
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                f("java.security.MessageDigest", "getInstance", 1),
                f("jdk.internal.reflect.DirectMethodHandleAccessor", "invoke", 2),
                f("java.lang.reflect.Method", "invoke", 3),
                f("com.acme.Legacy", "hash", 5)));

    assertThat(o.via()).isNull();
  }

  @Test
  void fallsBackToJdkLocationWhenNoApplicationFrame() {
    Occurrence o =
        Callsites.attribute(
            Stream.of(
                f("java.security.MessageDigest", "getInstance", 1),
                f("sun.security.util.SignatureFileVerifier", "process", 2),
                f("java.util.jar.JarFile", "getInputStream", 3)));

    assertThat(o.location()).isEqualTo(Occurrence.JDK_LOCATION);
    assertThat(o.via()).isEqualTo("jar-verification");
  }
}
