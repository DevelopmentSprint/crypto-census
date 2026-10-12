package com.developmentsprint.cryptocensus.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CensusCliTest {
  @TempDir static Path tlsDirectory;
  @TempDir Path directory;
  private static TlsFixture tls;
  private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

  @BeforeAll
  static void certificate() throws Exception {
    tls = new TlsFixture(tlsDirectory);
  }

  @Test
  void helpAndArgumentErrorsAreActionableAndNeverEchoValues() {
    assertThat(run(List.of("--help"), Map.of())).isZero();
    assertThat(stdout.toString(StandardCharsets.UTF_8))
        .contains("HTTP_ACCEPTED", "--token-env", "caller supplied");
    assertThat(run(List.of("publish"), Map.of())).isEqualTo(2);
    assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("Missing required --input");
    for (List<String> args :
        List.of(
            List.<String>of(),
            List.of("secret-value"),
            List.of("publish", "secret-value"),
            List.of("publish", "--token", "secret-value"),
            List.of("publish", "--input"),
            List.of("publish", "--auth", "none", "--auth", "secret-value"))) {
      assertThat(run(args, Map.of())).isEqualTo(2);
    }
    assertThat(stderr.toString(StandardCharsets.UTF_8)).doesNotContain("secret-value");
  }

  @Test
  void publishesExactEvidenceWithEnvironmentBearerAndRetainsSafeReceipt() throws Exception {
    Path artifact = artifact();
    AtomicReference<byte[]> body = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    HttpsServer server = tls.server();
    server.createContext(
        "/ingest",
        exchange -> {
          body.set(exchange.getRequestBody().readAllBytes());
          auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(202, 0);
          exchange.getResponseBody().write("accepted, not JSON".getBytes(StandardCharsets.UTF_8));
          exchange.close();
        });
    server.start();
    try {
      List<String> args = args(artifact, server);
      args.addAll(List.of("--token-env", "UPLOAD_TOKEN"));
      assertThat(run(args, Map.of("UPLOAD_TOKEN", "private-test-token"))).isZero();
      assertThat(body.get()).isEqualTo(Files.readAllBytes(artifact));
      assertThat(auth.get()).isEqualTo("Bearer private-test-token");
      assertThat(Files.readString(directory.resolve("receipt.json")))
          .contains("HTTP_ACCEPTED")
          .doesNotContain("private-test-token", "localhost");
      assertThat(stdout.toString(StandardCharsets.UTF_8) + stderr.toString(StandardCharsets.UTF_8))
          .doesNotContain("private-test-token");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectedDeliveryFailsUnlessExplicitlyReportingOnlyAndNeverMasksBadDigest() throws Exception {
    Path artifact = artifact();
    AtomicInteger calls = new AtomicInteger();
    HttpsServer server = tls.server();
    server.createContext(
        "/ingest",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(403, -1);
          exchange.close();
        });
    server.start();
    try {
      List<String> args = args(artifact, server);
      args.addAll(List.of("--auth", "none"));
      assertThat(run(args, Map.of())).isEqualTo(3);
      args.add("--report-only");
      assertThat(run(args, Map.of())).isZero();
      args.set(args.indexOf("--sha256") + 1, "0".repeat(64));
      assertThat(run(args, Map.of())).isEqualTo(2);
      assertThat(calls.get()).isEqualTo(2);
      assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("ARTIFACT_DIGEST_MISMATCH");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void credentialAndProxyConfigurationErrorsNeverAttemptDelivery() throws Exception {
    Path artifact = artifact();
    HttpsServer server = tls.server();
    try {
      for (List<String> options :
          List.of(
              List.of("--auth", "none", "--token-env", "TOKEN"),
              List.of("--auth", "unsupported"),
              List.of("--token-env", "BAD-REFERENCE"),
              List.of("--token-env", "ABSENT"),
              List.of("--proxy-port", "1234"),
              List.of("--auth", "none", "--proxy-user-env", "USER"),
              List.of("--auth", "none", "--proxy-host", "localhost", "--proxy-user-env", "USER"),
              List.of(
                  "--auth",
                  "none",
                  "--proxy-host",
                  "localhost",
                  "--proxy-user-env",
                  "BAD-REF",
                  "--proxy-password-env",
                  "PASS"),
              List.of(
                  "--auth",
                  "none",
                  "--proxy-host",
                  "localhost",
                  "--proxy-user-env",
                  "USER",
                  "--proxy-password-env",
                  "PASS"))) {
        List<String> args = args(artifact, server);
        args.addAll(options);
        assertThat(run(args, Map.of())).isEqualTo(2);
      }
      assertThat(stderr.toString(StandardCharsets.UTF_8))
          .contains("--token-env", "--auth", "--proxy-host");
      assertThat(Files.exists(directory.resolve("receipt.json"))).isFalse();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void distinguishesAmbiguousDeliveryFromReceiptFailureAfterAcceptance() throws Exception {
    Path artifact = artifact();
    AtomicInteger calls = new AtomicInteger();
    HttpsServer server = tls.server();
    server.createContext(
        "/ingest",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          if (calls.incrementAndGet() == 1) {
            exchange.sendResponseHeaders(503, -1);
          } else {
            Path receipt = directory.resolve("receipt.json");
            Files.delete(receipt);
            Files.createDirectory(receipt);
            exchange.sendResponseHeaders(204, -1);
          }
          exchange.close();
        });
    server.start();
    try {
      List<String> args = args(artifact, server);
      args.addAll(List.of("--auth", "none"));
      assertThat(run(args, Map.of())).isEqualTo(4);
      assertThat(Files.readString(directory.resolve("receipt.json"))).contains("UNKNOWN");
      assertThat(run(args, Map.of())).isEqualTo(5);
      assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("remote HTTP acceptance: true");
      assertThat(calls.get()).isEqualTo(2);
    } finally {
      server.stop(0);
    }
  }

  private int run(List<String> args, Map<String, String> environment) {
    return CensusCli.run(
        args.toArray(String[]::new),
        environment,
        new PrintStream(stdout, true, StandardCharsets.UTF_8),
        new PrintStream(stderr, true, StandardCharsets.UTF_8));
  }

  private List<String> args(Path artifact, HttpsServer server) throws Exception {
    String hash =
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
    return new ArrayList<>(
        List.of(
            "publish",
            "--input",
            artifact.toString(),
            "--sha256",
            hash,
            "--receipt",
            directory.resolve("receipt.json").toString(),
            "--endpoint",
            "https://localhost:" + server.getAddress().getPort() + "/ingest",
            "--destination-id",
            "inventory",
            "--application",
            "payments",
            "--module",
            "example:payments",
            "--version",
            "1.0",
            "--run-id",
            "run-123",
            "--ca-bundle",
            tls.caBundle().toString()));
  }

  private Path artifact() throws Exception {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    Cbom.HEALTH_PROPERTIES.forEach(name -> properties.put(name, "0"));
    Path artifact = directory.resolve("cbom.json");
    Cbom.write(new Inventory(), new Cbom.Metadata("payments", properties), artifact);
    return artifact;
  }
}
