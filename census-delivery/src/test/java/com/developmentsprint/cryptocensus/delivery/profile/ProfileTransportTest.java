package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.delivery.DeliveryOptions;
import com.developmentsprint.cryptocensus.delivery.TransportConfiguration;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpsServer;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileTransportTest {
  @TempDir static Path certificates;
  static TlsFixture tls;
  HttpsServer server;
  ExecutorService threads;
  URI endpoint;
  DeliveryOptions options;
  final AtomicInteger requests = new AtomicInteger();

  @BeforeAll
  static void setupCertificates() throws Exception {
    tls = new TlsFixture(certificates);
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
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, 1);
          exchange.getResponseBody().write(1);
          exchange.close();
        });
    server.start();
    endpoint = URI.create("https://localhost:" + server.getAddress().getPort() + "/");
    options = TransportConfiguration.options(tls.caBundle(), null, 0);
  }

  @AfterEach
  void cleanup() {
    server.stop(0);
    threads.shutdownNow();
    Thread.interrupted();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://other.invalid/",
        "//other.invalid/path",
        "../escape",
        "api/../escape",
        "api/%2e%2e/escape",
        "api/path#fragment",
        "api/path?secret=bad value"
      })
  void adapterCannotChangeOriginOrSmugglePath(String path) {
    assertThatThrownBy(() -> ProfileTransport.resolve(endpoint, path))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void typedRelativePathStaysWithinConfiguredPrefix() {
    assertThat(
            ProfileTransport.resolve(
                    URI.create("https://example.invalid/base"),
                    "api/v1/component?pageNumber=1&pageSize=25")
                .toString())
        .isEqualTo("https://example.invalid/base/api/v1/component?pageNumber=1&pageSize=25");
  }

  @Test
  void apiKeyAndBodyAreSentOnlyToSelectedOrigin() {
    var response =
        new ProfileTransport(options, System.nanoTime())
            .send(
                endpoint,
                new AdapterRequest(
                    "PUT", "api/v1/bom", "application/json", new byte[] {1}, true, 0),
                "key",
                AdapterOptions.AuthenticationScheme.API_KEY,
                64);
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).containsExactly(1);
  }

  @Test
  void invalidProtocolControlsNeverReachNetwork() {
    ProfileTransport transport = new ProfileTransport(options, System.nanoTime());
    for (AdapterRequest request :
        new AdapterRequest[] {
          new AdapterRequest("DELETE", "", null, new byte[0], true, 0),
          new AdapterRequest("GET", "", null, new byte[] {1}, false, 0),
          new AdapterRequest("GET", "", null, new byte[0], true, 0),
          new AdapterRequest("GET", "", null, new byte[0], false, -1),
          new AdapterRequest("GET", "", null, new byte[0], false, 1001),
          new AdapterRequest("POST", "", "bad\r\ncontent", new byte[0], true, 0),
          new AdapterRequest("POST", "", null, new byte[16 * 1024 * 1024 + 16385], true, 0),
          new AdapterRequest(
              "GET", "", null, new byte[0], false, 0, Map.of("Authorization", "secret")),
          new AdapterRequest(
              "GET", "", null, new byte[0], false, 0, Map.of("X-Tenant-Id", "bad\nvalue"))
        }) {
      assertThat(
              transport
                  .send(endpoint, request, null, AdapterOptions.AuthenticationScheme.BEARER, 64)
                  .failureCode())
          .isEqualTo("TRANSPORT_FAILURE");
    }
    assertThat(
            transport
                .send(
                    endpoint,
                    new AdapterRequest("GET", "", null, new byte[0], false, 0),
                    "secret\r\n",
                    AdapterOptions.AuthenticationScheme.API_KEY,
                    64)
                .failureCode())
        .isEqualTo("TRANSPORT_FAILURE");
    assertThat(requests.get()).isZero();
  }

  @Test
  void expiredBudgetAndInterruptedThreadNeverSubmit() {
    ProfileTransport expired =
        new ProfileTransport(options, System.nanoTime() - Duration.ofSeconds(61).toNanos());
    assertThat(
            expired
                .send(
                    endpoint,
                    new AdapterRequest("GET", "", null, new byte[0], false, 0),
                    null,
                    AdapterOptions.AuthenticationScheme.BEARER,
                    64)
                .failureCode())
        .isEqualTo("DEADLINE_BEFORE_SEND");
    Thread.currentThread().interrupt();
    assertThat(
            new ProfileTransport(options, System.nanoTime())
                .send(
                    endpoint,
                    new AdapterRequest("GET", "", null, new byte[0], false, 100),
                    null,
                    AdapterOptions.AuthenticationScheme.BEARER,
                    64)
                .failureCode())
        .isEqualTo("INTERRUPTED");
    assertThat(Thread.interrupted()).isTrue();
    assertThat(requests.get()).isZero();
  }

  @Test
  void pollingDelayCountsAgainstTotalDeadline() {
    DeliveryOptions bounded =
        new DeliveryOptions(
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(150),
            options.sslContext(),
            null);
    var response =
        new ProfileTransport(bounded, System.nanoTime())
            .send(
                endpoint,
                new AdapterRequest("GET", "", null, new byte[0], false, 500),
                null,
                AdapterOptions.AuthenticationScheme.BEARER,
                64);
    assertThat(response.failureCode()).isEqualTo("OPERATION_TIMEOUT");
    assertThat(requests.get()).isZero();
  }

  @Test
  void requestDeadlineIncludesSlowStreamingBody() {
    server.removeContext("/");
    server.createContext(
        "/",
        exchange -> {
          exchange.sendResponseHeaders(200, 2);
          exchange.getResponseBody().write(1);
          exchange.getResponseBody().flush();
          try {
            Thread.sleep(1500);
          } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
          }
          try {
            exchange.getResponseBody().write(2);
          } catch (java.io.IOException expected) {
            /* timeout cancels receiver */
          }
          exchange.close();
        });
    DeliveryOptions bounded =
        new DeliveryOptions(
            Duration.ofMillis(500),
            Duration.ofMillis(700),
            Duration.ofSeconds(3),
            options.sslContext(),
            null);
    long start = System.nanoTime();
    var response =
        new ProfileTransport(bounded, start)
            .send(
                endpoint,
                new AdapterRequest("GET", "", null, new byte[0], false, 0),
                null,
                AdapterOptions.AuthenticationScheme.BEARER,
                64);
    assertThat(response.failureCode()).isEqualTo("TIMEOUT");
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1400));
  }

  @Test
  void configuredBrokenProxyCannotFallBackToDirect() throws Exception {
    DeliveryOptions proxy = TransportConfiguration.options(tls.caBundle(), "127.0.0.1", 1);
    var response =
        new ProfileTransport(proxy, System.nanoTime())
            .send(
                endpoint,
                new AdapterRequest("GET", "", null, new byte[0], false, 0),
                null,
                AdapterOptions.AuthenticationScheme.BEARER,
                64);
    assertThat(response.failureCode()).isEqualTo("TRANSPORT_FAILURE");
    assertThat(requests.get()).isZero();
  }
}
