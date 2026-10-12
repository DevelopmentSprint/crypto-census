package com.developmentsprint.cryptocensus.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TransportLoggingTest {
  @Test
  void jdkSensitiveLogModesFailClosedWithoutChangingTheProperty() {
    String previous = System.getProperty("jdk.httpclient.HttpClient.log");
    try {
      for (String mode : new String[] {"headers", "content", "all", "errors, headers"}) {
        System.setProperty("jdk.httpclient.HttpClient.log", mode);
        assertThat(TransportLogging.safe(false)).isFalse();
        assertThat(System.getProperty("jdk.httpclient.HttpClient.log")).isEqualTo(mode);
      }
      System.setProperty("jdk.httpclient.HttpClient.log", "errors");
      assertThat(TransportLogging.safe(false)).isTrue();
    } finally {
      if (previous == null) {
        System.clearProperty("jdk.httpclient.HttpClient.log");
      } else {
        System.setProperty("jdk.httpclient.HttpClient.log", previous);
      }
    }
  }

  @Test
  void apacheHeaderAndWireDebugRefuseTransportBeforeAnyConnection() throws Exception {
    for (String logger :
        new String[] {
          "headers", "wire", "impl.auth.AuthenticationHandler", "impl.auth.BasicScheme"
        }) {
      String java =
          Path.of(
                  System.getProperty("java.home"),
                  "bin",
                  System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
              .toString();
      Process process =
          new ProcessBuilder(
                  java,
                  "-Dorg.slf4j.simpleLogger.log.org.apache.hc.client5.http." + logger + "=debug",
                  "-cp",
                  System.getProperty(
                      "surefire.test.class.path", System.getProperty("java.class.path")),
                  LoggingProbe.class.getName())
              .redirectErrorStream(true)
              .start();
      try {
        assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output)
            .contains("UNSAFE_TRANSPORT_LOGGING")
            .doesNotContain("test-proxy-secret", "test-origin-secret");
      } finally {
        process.destroyForcibly();
      }
    }
  }

  public static final class LoggingProbe {
    private LoggingProbe() {}

    public static void main(String[] args) throws Exception {
      var options =
          TransportConfiguration.options(
              null, "127.0.0.1", 1, ProxyCredentials.basic("user", "test-proxy-secret"));
      var request =
          java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://localhost:1/"))
              .header("Authorization", "Bearer test-origin-secret")
              .GET()
              .build();
      var response =
          AuthenticatedProxyTransport.send(
              options, request, new byte[0], 64, java.time.Duration.ofSeconds(1));
      System.out.println(response.failureCode());
    }
  }
}
