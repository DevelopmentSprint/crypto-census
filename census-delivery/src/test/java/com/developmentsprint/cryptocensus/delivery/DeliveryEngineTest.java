package com.developmentsprint.cryptocensus.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Primitive;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DeliveryEngineTest {
  private static final String SECRET = "do-not-log-this-token";
  @TempDir static Path certificates;
  private static TlsFixture tls;
  @TempDir Path directory;
  private HttpsServer server;
  private ExecutorService executor;
  private final AtomicInteger attempts = new AtomicInteger();
  private final AtomicReference<byte[]> received = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
  private Path artifact;
  private DeliveryOptions options;

  @BeforeAll
  static void certificates() throws Exception {
    tls = new TlsFixture(certificates);
  }

  @BeforeEach
  void setup() throws Exception {
    artifact = directory.resolve("cbom.json");
    Inventory inventory = new Inventory();
    inventory.record(
        CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, "SUN"),
        new Occurrence("example.Workload", "digest", 1, null));
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    for (String health : Cbom.HEALTH_PROPERTIES) {
      metadata.put(health, "0");
    }
    Cbom.write(inventory, new Cbom.Metadata("module", metadata), artifact);
    server = tls.server();
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    handler.set(exchange -> respond(exchange, 202, "accepted".getBytes(StandardCharsets.UTF_8)));
    server.createContext(
        "/cboms",
        exchange -> {
          attempts.incrementAndGet();
          received.set(exchange.getRequestBody().readAllBytes());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          assertThat(exchange.getRequestHeaders().getFirst("Content-Type"))
              .isEqualTo("application/vnd.cyclonedx+json");
          assertThat(exchange.getRequestHeaders().getFirst("X-Census-Artifact-Sha256")).hasSize(64);
          handler.get().handle(exchange);
        });
    server.start();
    options = TransportConfiguration.options(tls.caBundle(), null, 0);
  }

  @AfterEach
  void cleanup() {
    server.stop(0);
    executor.shutdownNow();
    Thread.interrupted();
  }

  @Test
  void deliversOriginalBytesAndWritesAnAtomicPrivateNonsecretReceipt() throws Exception {
    byte[] original = Files.readAllBytes(artifact);
    DeliveryRequest request = request(options);
    DeliveryReceipt receipt = new DeliveryEngine().deliver(request);
    assertThat(receipt.successful()).isTrue();
    assertThat(receipt.outcome()).isEqualTo(DeliveryReceipt.Outcome.HTTP_ACCEPTED);
    assertThat(receipt.httpStatus()).isEqualTo(202);
    assertThat(receipt.artifactSha256()).isEqualTo(hash(original));
    assertThat(received.get()).isEqualTo(original);
    assertThat(Files.readAllBytes(artifact)).isEqualTo(original);
    assertThat(authorization.get()).isEqualTo("Bearer " + SECRET);
    assertThat(Files.readString(request.receipt()))
        .contains("\"outcome\": \"HTTP_ACCEPTED\"", receipt.deliveryId())
        .doesNotContain(SECRET, "/cboms", "Authorization", "accepted");
    if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertThat(Files.getPosixFilePermissions(request.receipt()))
          .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "301,REJECTED",
    "400,REJECTED",
    "401,REJECTED",
    "403,REJECTED",
    "429,REJECTED",
    "500,UNKNOWN",
    "503,UNKNOWN"
  })
  void refusesRedirectsAndNeverRetriesOrLeaksResponseBodies(int status, String outcome)
      throws Exception {
    handler.set(
        exchange -> {
          exchange.getResponseHeaders().add("Location", endpoint() + "?token=" + SECRET);
          respond(exchange, status, SECRET.getBytes(StandardCharsets.UTF_8));
        });
    DeliveryRequest request = request(options);
    DeliveryReceipt receipt = new DeliveryEngine().deliver(request);
    assertThat(receipt.outcome().name()).isEqualTo(outcome);
    assertThat(receipt.httpStatus()).isEqualTo(status);
    assertThat(attempts).hasValue(1);
    assertThat(Files.readString(request.receipt())).doesNotContain(SECRET, "Location");
    assertThat(receipt.toString()).doesNotContain(SECRET);
  }

  @Test
  void boundsResponseSizeWithoutRetainingUntrustedText() throws Exception {
    handler.set(exchange -> respond(exchange, 202, new byte[65537]));
    DeliveryReceipt receipt = new DeliveryEngine().deliver(request(options));
    assertThat(receipt.outcome()).isEqualTo(DeliveryReceipt.Outcome.UNKNOWN);
    assertThat(receipt.reasonCode()).isEqualTo("RESPONSE_TOO_LARGE");
    assertThat(attempts).hasValue(1);
  }

  @Test
  void acceptsTheResponseLimitExactly() throws Exception {
    handler.set(exchange -> respond(exchange, 200, new byte[65536]));
    assertThat(new DeliveryEngine().deliver(request(options)).successful()).isTrue();
  }

  @Test
  void overallDeadlineCoversSlowResponseBodies() throws Exception {
    handler.set(
        exchange -> {
          exchange.sendResponseHeaders(200, 100);
          exchange.getResponseBody().write(1);
          exchange.getResponseBody().flush();
          try {
            Thread.sleep(5000);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    DeliveryOptions bounded =
        new DeliveryOptions(
            Duration.ofSeconds(1),
            Duration.ofSeconds(4),
            Duration.ofMillis(800),
            options.sslContext(),
            null);
    long start = System.nanoTime();
    DeliveryReceipt receipt = new DeliveryEngine().deliver(request(bounded));
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    assertThat(receipt.outcome()).isEqualTo(DeliveryReceipt.Outcome.UNKNOWN);
    assertThat(receipt.reasonCode()).isEqualTo("TIMEOUT");
    assertThat(attempts).hasValue(1);
  }

  @Test
  void requestDeadlineAlsoCoversSlowResponseBodies() throws Exception {
    handler.set(
        exchange -> {
          exchange.sendResponseHeaders(200, 2);
          exchange.getResponseBody().write(1);
          exchange.getResponseBody().flush();
          try {
            Thread.sleep(2500);
            exchange.getResponseBody().write(1);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    DeliveryOptions bounded =
        new DeliveryOptions(
            Duration.ofSeconds(1),
            Duration.ofMillis(800),
            Duration.ofSeconds(4),
            options.sslContext(),
            null);
    long started = System.nanoTime();
    DeliveryReceipt receipt = new DeliveryEngine().deliver(request(bounded));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(receipt.outcome()).isEqualTo(DeliveryReceipt.Outcome.UNKNOWN);
    assertThat(receipt.reasonCode()).isEqualTo("TIMEOUT");
    assertThat(attempts).hasValue(1);
  }

  @Test
  void customCaDoesNotDisableHostnameVerification() throws Exception {
    DeliveryRequest good = request(options);
    DeliveryRequest mismatch =
        copy(
            good,
            URI.create(endpoint().replace("localhost", "127.0.0.1")),
            good.expectedSha256(),
            good.receipt());
    DeliveryReceipt receipt = new DeliveryEngine().deliver(mismatch);
    assertThat(receipt.reasonCode()).isEqualTo("TLS_FAILURE");
    assertThat(attempts).hasValue(0);
  }

  @Test
  void defaultTrustRejectsUntrustedCertificates() throws Exception {
    DeliveryReceipt receipt = new DeliveryEngine().deliver(request(DeliveryOptions.defaults()));
    assertThat(receipt.reasonCode()).isEqualTo("TLS_FAILURE");
    assertThat(attempts).hasValue(0);
  }

  @Test
  void changedEvidenceFailsBeforeNetworkWithActionableSafeReason() throws Exception {
    DeliveryRequest good = request(options);
    DeliveryRequest wrongHash = copy(good, good.endpoint(), "0".repeat(64), good.receipt());
    assertThatThrownBy(() -> new DeliveryEngine().deliver(wrongHash))
        .isInstanceOf(DeliveryException.class)
        .hasMessageContaining("digest")
        .satisfies(
            error ->
                assertThat(((DeliveryException) error).reasonCode())
                    .isEqualTo("ARTIFACT_DIGEST_MISMATCH"));
    assertThat(attempts).hasValue(0);
    assertThat(good.receipt()).doesNotExist();
  }

  @Test
  void invalidEvidenceDoesNotEchoUntrustedContentOrTouchTheNetwork() throws Exception {
    Files.writeString(artifact, "{\"token\":\"" + SECRET + "\"}");
    assertThatThrownBy(() -> new DeliveryEngine().deliver(request(options)))
        .isInstanceOf(DeliveryException.class)
        .hasMessageNotContaining(SECRET)
        .hasNoCause();
    assertThat(attempts).hasValue(0);
  }

  @Test
  void refusesReceiptOverwritingEvidenceIncludingHardLinks() throws Exception {
    DeliveryRequest good = request(options);
    Path hardLink = Files.createLink(directory.resolve("alias.json"), artifact);
    for (Path target : new Path[] {artifact, hardLink}) {
      assertThatThrownBy(
              () ->
                  new DeliveryEngine()
                      .deliver(copy(good, good.endpoint(), good.expectedSha256(), target)))
          .isInstanceOf(DeliveryException.class)
          .hasMessageContaining("distinct");
    }
    assertThat(attempts).hasValue(0);
  }

  @Test
  void intentMustPersistBeforeDelivery() throws Exception {
    DeliveryRequest good = request(options);
    Path parentFile = Files.writeString(directory.resolve("not-a-directory"), "existing");
    assertThatThrownBy(
            () ->
                new DeliveryEngine()
                    .deliver(
                        copy(
                            good,
                            good.endpoint(),
                            good.expectedSha256(),
                            parentFile.resolve("receipt.json"))))
        .isInstanceOf(DeliveryException.class)
        .hasMessageContaining("no delivery attempted");
    assertThat(attempts).hasValue(0);
  }

  @Test
  void reportsRemoteAcceptanceWhenFinalReceiptCannotBePersisted() throws Exception {
    DeliveryRequest request = request(options);
    handler.set(
        exchange -> {
          Files.delete(request.receipt());
          Files.createDirectory(request.receipt());
          respond(exchange, 202, new byte[0]);
        });
    assertThatThrownBy(() -> new DeliveryEngine().deliver(request))
        .isInstanceOf(ReceiptWriteException.class)
        .satisfies(error -> assertThat(((ReceiptWriteException) error).remoteAccepted()).isTrue());
    assertThat(attempts).hasValue(1);
  }

  @Test
  void identitySeparatesModulesRunsAndTargetsAndRemainsStableForSameEvidence() throws Exception {
    DeliveryRequest request = request(options);
    DeliveryEngine engine = new DeliveryEngine();
    String first = engine.deliver(request).deliveryId();
    assertThat(engine.deliver(request).deliveryId()).isEqualTo(first);
    DeliveryRequest different =
        new DeliveryRequest(
            request.artifact(),
            request.receipt(),
            request.expectedSha256(),
            request.destinationId(),
            request.endpoint(),
            request.application(),
            "different-module",
            request.version(),
            request.runId(),
            request.credentials(),
            request.options());
    assertThat(engine.deliver(different).deliveryId()).isNotEqualTo(first);
    assertThat(attempts).hasValue(3);
  }

  @Test
  void acceptsCurrentMavenMergedEvidenceAndRetainsIncompleteHealth() throws Exception {
    Cbom.Document document = Cbom.read(artifact);
    Map<String, String> properties = new LinkedHashMap<>(document.properties());
    properties.remove(Cbom.SCHEMA_PROPERTY);
    properties.remove("cryptocensus:untrackedTypes");
    properties.put("cryptocensus:project", "example:module:1.0");
    properties.put("cryptocensus:input:cbom-123.json", "sha256:" + "a".repeat(64));
    properties.put("cryptocensus:transformErrors", "3");
    Cbom.write(document.inventory(), new Cbom.Metadata("module", properties), artifact);
    byte[] original = Files.readAllBytes(artifact);
    DeliveryRequest request = request(options);
    assertThat(new DeliveryEngine().deliver(request).successful()).isTrue();
    assertThat(received.get()).isEqualTo(original);
    assertThat(Cbom.read(artifact).properties()).doesNotContainKey("cryptocensus:untrackedTypes");
    assertThat(Files.readString(request.receipt()))
        .contains("ORIGINAL_ARTIFACT")
        .doesNotContain("complete");
  }

  @Test
  void rejectsMalformedMergedComponentsBeforeDelivery() throws Exception {
    Cbom.Document document = Cbom.read(artifact);
    Map<String, String> properties = new LinkedHashMap<>(document.properties());
    properties.remove(Cbom.SCHEMA_PROPERTY);
    properties.remove("cryptocensus:untrackedTypes");
    properties.put("cryptocensus:project", "example:module:1.0");
    properties.put("cryptocensus:input:cbom-123.json", "sha256:" + "a".repeat(64));
    Cbom.write(document.inventory(), new Cbom.Metadata("module", properties), artifact);
    String valid = Files.readString(artifact);
    for (String malformed :
        new String[] {
          valid.replace("\"cryptographic-asset\"", "\"library\""),
          valid.replace("\"assetType\": \"algorithm\"", "\"assetType\": \"protocol\""),
          valid.replace("cryptocensus:primitive", "other:primitive")
        }) {
      assertThat(malformed).isNotEqualTo(valid);
      Files.writeString(artifact, malformed);
      DeliveryRequest request = request(options);
      assertThatThrownBy(() -> new DeliveryEngine().deliver(request))
          .isInstanceOf(DeliveryException.class)
          .satisfies(
              error ->
                  assertThat(((DeliveryException) error).reasonCode()).isEqualTo("INVALID_CBOM"));
      assertThat(Files.readString(artifact)).isEqualTo(malformed);
      assertThat(request.receipt()).doesNotExist();
    }
    assertThat(attempts).hasValue(0);
  }

  @Test
  void interruptedCallerRetainsInterruptAndDoesNotSend() throws Exception {
    DeliveryRequest request = request(options);
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> new DeliveryEngine().deliver(request))
          .isInstanceOf(DeliveryException.class)
          .hasMessageContaining("interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
    assertThat(attempts).hasValue(0);
  }

  @Test
  void explicitProxyUsesConnectAndKeepsBearerInsideTls() throws Exception {
    try (TunnelProxy proxy = new TunnelProxy(server.getAddress().getPort())) {
      DeliveryOptions proxied =
          TransportConfiguration.options(tls.caBundle(), "127.0.0.1", proxy.port());
      assertThat(new DeliveryEngine().deliver(request(proxied)).successful()).isTrue();
      assertThat(proxy.connectRequest())
          .startsWith("CONNECT localhost:")
          .doesNotContain(SECRET, "Authorization");
      assertThat(authorization.get()).isEqualTo("Bearer " + SECRET);
      assertThat(attempts).hasValue(1);
    }
  }

  private DeliveryRequest request(DeliveryOptions transport) throws Exception {
    return new DeliveryRequest(
        artifact,
        directory.resolve("receipt.json"),
        hash(Files.readAllBytes(artifact)),
        "company",
        URI.create(endpoint()),
        "app",
        "module",
        "1.0",
        "build-42",
        DeliveryCredentials.bearer(SECRET),
        transport);
  }

  private String endpoint() {
    return "https://localhost:" + server.getAddress().getPort() + "/cboms";
  }

  private static DeliveryRequest copy(
      DeliveryRequest request, URI endpoint, String hash, Path receipt) {
    return new DeliveryRequest(
        request.artifact(),
        receipt,
        hash,
        request.destinationId(),
        endpoint,
        request.application(),
        request.module(),
        request.version(),
        request.runId(),
        request.credentials(),
        request.options());
  }

  private static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
    try (exchange) {
      exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
      if (body.length > 0) {
        exchange.getResponseBody().write(body);
      }
    }
  }
}
