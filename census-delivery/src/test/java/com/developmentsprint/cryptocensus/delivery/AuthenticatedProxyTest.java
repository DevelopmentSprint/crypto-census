package com.developmentsprint.cryptocensus.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.developmentsprint.cryptocensus.delivery.testing.TunnelProxy;
import com.sun.net.httpserver.HttpsServer;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthenticatedProxyTest {
  @TempDir static Path directory;
  static TlsFixture tls;
  HttpsServer server;
  ExecutorService threads;
  final AtomicInteger requests = new AtomicInteger();
  final AtomicReference<String> originProxyHeader = new AtomicReference<>();
  final AtomicReference<String> originAuthorization = new AtomicReference<>();
  String endpoint;
  static final String USER = "proxy-test-user";
  static final String PASSWORD = "proxy-test-password";
  static final String BASIC =
      "Basic "
          + Base64.getEncoder()
              .encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.US_ASCII));

  @BeforeAll
  static void certificates() throws Exception {
    tls = new TlsFixture(directory);
  }

  @BeforeEach
  void setup() throws Exception {
    server = tls.server();
    threads = Executors.newCachedThreadPool();
    server.setExecutor(threads);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          originProxyHeader.set(exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
          originAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"origin\"");
          exchange.sendResponseHeaders(401, 1);
          exchange.getResponseBody().write(1);
          exchange.close();
        });
    server.start();
    endpoint = "https://localhost:" + server.getAddress().getPort() + "/";
  }

  @AfterEach
  void close() {
    server.stop(0);
    threads.shutdownNow();
    Thread.interrupted();
  }

  private DeliveryOptions options(int port, Path ca) throws Exception {
    return TransportConfiguration.options(
        ca, "127.0.0.1", port, ProxyCredentials.basic(USER, PASSWORD));
  }

  private HttpRequest request(String uri) {
    return HttpRequest.newBuilder(URI.create(uri))
        .header("Authorization", "Bearer origin-test-token")
        .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {1}))
        .build();
  }

  @Test
  void credentialsOnlyReachTheirIntendedRecipientAndNoGlobalSettingsChange() throws Exception {
    String before = System.getProperty("jdk.http.auth.tunneling.disabledSchemes");
    var authenticator = java.net.Authenticator.getDefault();
    try (TunnelProxy proxy = new TunnelProxy(server.getAddress().getPort(), BASIC)) {
      var response =
          AuthenticatedProxyTransport.send(
              options(proxy.port(), tls.caBundle()),
              request(endpoint),
              new byte[] {1},
              64,
              Duration.ofSeconds(5));
      assertThat(response.statusCode()).isEqualTo(401);
      assertThat(proxy.connectRequest()).contains(BASIC).doesNotContain("origin-test-token");
      assertThat(originProxyHeader.get()).isNull();
      assertThat(originAuthorization.get()).isEqualTo("Bearer origin-test-token");
      assertThat(requests.get()).isEqualTo(1);
    }
    assertThat(System.getProperty("jdk.http.auth.tunneling.disabledSchemes")).isEqualTo(before);
    assertThat(java.net.Authenticator.getDefault()).isSameAs(authenticator);
  }

  @Test
  void foundationAndProfileResolveScopedProxyCredentials(@TempDir Path evidence) throws Exception {
    var config =
        com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture.configuration(
            evidence, endpoint, tls.caBundle());
    Path artifact = evidence.resolve("cbom.json");
    String digest =
        com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture.hash(
            java.nio.file.Files.readAllBytes(artifact));
    try (TunnelProxy proxy = new TunnelProxy(server.getAddress().getPort(), BASIC)) {
      var request =
          new DeliveryRequest(
              artifact,
              evidence.resolve("legacy-receipt.json"),
              digest,
              "test",
              URI.create(endpoint),
              "app",
              "module",
              "1.0",
              "run",
              DeliveryCredentials.bearer("origin-test-token"),
              options(proxy.port(), tls.caBundle()));
      assertThat(new DeliveryEngine().deliver(request).outcome())
          .isEqualTo(DeliveryReceipt.Outcome.REJECTED);
    }
    try (TunnelProxy proxy = new TunnelProxy(server.getAddress().getPort(), BASIC)) {
      config.put(
          "transport",
          java.util.Map.of(
              "caBundle",
              tls.caBundle().toString(),
              "proxyHost",
              "127.0.0.1",
              "proxyPort",
              proxy.port(),
              "proxyUsername",
              java.util.Map.of("type", "ENV", "name", "PROXY_USER"),
              "proxyPassword",
              java.util.Map.of("type", "ENV", "name", "PROXY_PASSWORD")));
      Path profile =
          com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture.write(
              evidence, config);
      var plan =
          com.developmentsprint.cryptocensus.delivery.profile.ProfileCommand.run(
              "plan",
              profile,
              null,
              reference -> {
                throw new AssertionError("Planning must never read secrets");
              });
      var result =
          com.developmentsprint.cryptocensus.delivery.profile.ProfileCommand.run(
              "publish",
              profile,
              plan.planSha256(),
              reference ->
                  switch (reference.name()) {
                    case "PROXY_USER" -> USER;
                    case "PROXY_PASSWORD" -> PASSWORD;
                    default -> "origin-test-token";
                  });
      assertThat(result.receipt().observation().upload())
          .isEqualTo(
              com.developmentsprint.cryptocensus.delivery.profile.AdapterResult.Upload.REJECTED);
      assertThat(java.nio.file.Files.readString(evidence.resolve("plan.json")))
          .doesNotContain(USER, PASSWORD);
      assertThat(java.nio.file.Files.readString(evidence.resolve("receipt.json")))
          .doesNotContain(USER, PASSWORD);
    }
    assertThat(requests.get()).isEqualTo(2);
    assertThat(originProxyHeader.get()).isNull();
  }

  @Test
  void untrustedCaAndWrongHostnameStillFailBeforeOriginRequest() throws Exception {
    try (TunnelProxy proxy = new TunnelProxy(server.getAddress().getPort(), BASIC)) {
      assertThat(
              AuthenticatedProxyTransport.send(
                      options(proxy.port(), null),
                      request(endpoint),
                      new byte[] {1},
                      64,
                      Duration.ofSeconds(5))
                  .failureCode())
          .isEqualTo("TLS_FAILURE");
    }
    try (TunnelProxy proxy = new TunnelProxy(server.getAddress().getPort(), BASIC)) {
      assertThat(
              AuthenticatedProxyTransport.send(
                      options(proxy.port(), tls.caBundle()),
                      request(endpoint.replace("localhost", "127.0.0.1")),
                      new byte[] {1},
                      64,
                      Duration.ofSeconds(5))
                  .failureCode())
          .isEqualTo("TLS_FAILURE");
    }
    assertThat(requests.get()).isZero();
  }

  @Test
  void exhaustedInitializationBudgetNeverReachesProxyOrOrigin() throws Exception {
    try (TunnelProxy proxy = new TunnelProxy(server.getAddress().getPort(), BASIC)) {
      assertThat(
              AuthenticatedProxyTransport.send(
                      options(proxy.port(), tls.caBundle()),
                      request(endpoint),
                      new byte[] {1},
                      64,
                      Duration.ofNanos(1))
                  .failureCode())
          .isEqualTo("DEADLINE_BEFORE_SEND");
      assertThat(proxy.connectRequest()).isNull();
      assertThat(requests.get()).isZero();
    }
  }

  @Test
  void unavailableProxyNeverFallsBackToOrigin() throws Exception {
    assertThat(
            AuthenticatedProxyTransport.send(
                    options(1, tls.caBundle()),
                    request(endpoint),
                    new byte[] {1},
                    64,
                    Duration.ofSeconds(2))
                .failureCode())
        .isEqualTo("TRANSPORT_FAILURE");
    assertThat(requests.get()).isZero();
  }

  @Test
  void stalledConnectHandshakeHonorsTheSameBodyInclusiveDeadline() throws Exception {
    try (java.net.ServerSocket proxy =
        new java.net.ServerSocket(
            0, 1, java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
      threads.execute(
          () -> {
            try (var socket = proxy.accept()) {
              socket.getInputStream().readNBytes(8192);
            } catch (java.io.IOException expected) {
              /* cancellation closes tunnel */
            }
          });
      long started = System.nanoTime();
      var response =
          AuthenticatedProxyTransport.send(
              options(proxy.getLocalPort(), tls.caBundle()),
              request(endpoint),
              new byte[] {1},
              64,
              Duration.ofMillis(700));
      assertThat(response.failureCode()).isEqualTo("TIMEOUT");
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1600));
      assertThat(requests.get()).isZero();
    }
  }

  @Test
  void redirectsAndOversizedBodiesAreNotFollowedOrRetried() throws Exception {
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange
              .getResponseHeaders()
              .add("Location", "https://user:private-redirect-secret@localhost/");
          exchange.sendResponseHeaders(302, 100);
          exchange.getResponseBody().write(new byte[100]);
          exchange.close();
        });
    try (TunnelProxy proxy = new TunnelProxy(server.getAddress().getPort(), BASIC)) {
      assertThat(
              AuthenticatedProxyTransport.send(
                      options(proxy.port(), tls.caBundle()),
                      request(endpoint),
                      new byte[] {1},
                      64,
                      Duration.ofSeconds(5))
                  .failureCode())
          .isEqualTo("RESPONSE_TOO_LARGE");
    }
    try (TunnelProxy proxy = new TunnelProxy(server.getAddress().getPort(), BASIC)) {
      var response =
          AuthenticatedProxyTransport.send(
              options(proxy.port(), tls.caBundle()),
              request(endpoint),
              new byte[] {1},
              128,
              Duration.ofSeconds(5));
      assertThat(response.statusCode()).isEqualTo(302);
      assertThat(response.toString()).doesNotContain("private-redirect-secret");
    }
    assertThat(requests.get()).isEqualTo(2);
  }

  @Test
  void invalidProxyCredentialsAndDiagnosticsNeverExposeValues() {
    assertThatThrownBy(() -> ProxyCredentials.basic("bad:user", PASSWORD))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(PASSWORD);
    assertThatThrownBy(() -> ProxyCredentials.basic(USER, "bad\r\npassword"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ProxyCredentials.basic(USER, PASSWORD).toString()).doesNotContain(USER, PASSWORD);
    assertThat(
            new AuthenticatedProxy("localhost", 8080, ProxyCredentials.basic(USER, PASSWORD))
                .toString())
        .doesNotContain(USER, PASSWORD);
  }

  @Test
  void oversizedStreamingBodyCancelsWithoutDrainingTheRemainder() throws Exception {
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 100000);
          exchange.getResponseBody().write(new byte[65]);
          exchange.getResponseBody().flush();
          try {
            Thread.sleep(3000);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    try (TunnelProxy proxy = new TunnelProxy(server.getAddress().getPort(), BASIC)) {
      long started = System.nanoTime();
      var response =
          AuthenticatedProxyTransport.send(
              options(proxy.port(), tls.caBundle()),
              request(endpoint),
              new byte[] {1},
              64,
              Duration.ofSeconds(5));
      assertThat(response.failureCode()).isEqualTo("RESPONSE_TOO_LARGE");
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
  }

  @Test
  void slowBodyIsCancelledWithinAttemptBudgetAndNeverRetried() throws Exception {
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 2);
          exchange.getResponseBody().write(1);
          exchange.getResponseBody().flush();
          try {
            Thread.sleep(2000);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          exchange.close();
        });
    try (TunnelProxy proxy = new TunnelProxy(server.getAddress().getPort(), BASIC)) {
      long started = System.nanoTime();
      var response =
          AuthenticatedProxyTransport.send(
              options(proxy.port(), tls.caBundle()),
              request(endpoint),
              new byte[] {1},
              64,
              Duration.ofMillis(800));
      assertThat(response.failureCode()).isEqualTo("TIMEOUT");
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1700));
      assertThat(requests.get()).isEqualTo(1);
    }
  }
}
