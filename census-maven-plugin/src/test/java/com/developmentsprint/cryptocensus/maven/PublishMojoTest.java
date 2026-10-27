package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpsServer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishMojoTest {
  @TempDir static Path tlsDirectory;
  @TempDir Path directory;
  private static TlsFixture tls;

  @BeforeAll
  static void certificate() throws Exception {
    tls = new TlsFixture(tlsDirectory);
  }

  @Test
  void publishesExactEvidenceAndUsesIndependentReceiptFiles() throws Exception {
    AtomicReference<byte[]> body = new AtomicReference<>();
    HttpsServer server = tls.server();
    server.createContext(
        "/ingest",
        exchange -> {
          body.set(exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    try {
      PublishMojo mojo = mojo(server);
      mojo.execute();
      assertThat(body.get()).isEqualTo(Files.readAllBytes(directory.resolve("cbom.json")));
      mojo.execute();
      try (var receipts = Files.list(directory.resolve("receipts"))) {
        assertThat(receipts.toList()).hasSize(2);
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void receiptCannotOverwriteCaThroughFilesystemAliases() throws Exception {
    HttpsServer server = tls.server();
    var requests = new java.util.concurrent.atomic.AtomicInteger();
    server.createContext(
        "/ingest",
        exchange -> {
          requests.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    try {
      Path roots = Files.createDirectory(directory.resolve("roots"));
      Path ca = Files.copy(tls.caBundle(), roots.resolve("ca.pem"));
      byte[] original = Files.readAllBytes(ca);
      Path hardlink = Files.createLink(directory.resolve("ca-hardlink.pem"), ca);
      Path child = Files.createDirectory(roots.resolve("child"));
      Path parentLink = Files.createSymbolicLink(directory.resolve("roots-link"), roots);
      Path childLink = Files.createSymbolicLink(directory.resolve("child-link"), child);
      for (Path receiptPath :
          new Path[] {
            ca, hardlink, parentLink.resolve("ca.pem"), parentLink.resolve("child/../ca.pem")
          }) {
        assertThat(Files.isSameFile(receiptPath, ca))
            .as("Fixture must resolve to the CA: %s", receiptPath)
            .isTrue();
        PublishMojo publish = mojo(server);
        set(publish, "caBundle", ca.toFile());
        set(publish, "receipt", receiptPath.toFile());
        assertThatThrownBy(publish::execute)
            .as("Receipt aliases the selected CA: %s", receiptPath)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("must not overwrite the selected CA bundle");
        assertThat(Files.readAllBytes(ca)).isEqualTo(original);
        assertThat(requests.get()).isZero();
      }
      // Win32 removes link/.. lexically; Unix resolves the link before traversing its parent.
      // Establish both possible targets independently, then verify the platform's fixture.
      Path sibling = Files.writeString(directory.resolve("ca.pem"), "independent receipt target");
      Path divergent = childLink.resolve("../ca.pem");
      boolean windows = java.io.File.separatorChar == '\\';
      assertThat(Files.isSameFile(divergent, windows ? sibling : ca))
          .as("Platform-dependent parent resolution: %s", divergent)
          .isTrue();
      assertThat(Files.isSameFile(sibling, ca)).isFalse();
      PublishMojo publish = mojo(server);
      set(publish, "caBundle", ca.toFile());
      set(publish, "receipt", divergent.toFile());
      if (windows) {
        publish.execute();
        assertThat(Files.readString(sibling)).contains("ACCEPTED");
        assertThat(requests.get()).isEqualTo(1);
      } else {
        assertThatThrownBy(publish::execute)
            .hasMessageContaining("must not overwrite the selected CA bundle");
        assertThat(Files.readString(sibling)).isEqualTo("independent receipt target");
        assertThat(requests.get()).isZero();
      }
      assertThat(Files.readAllBytes(ca)).isEqualTo(original);
      assertThat(directory.resolve("receipts")).doesNotExist();
    } finally {
      server.stop(0);
    }
  }

  @Test
  void failureGateDoesNotMaskInvalidInputOrLeakResponses() throws Exception {
    HttpsServer server = tls.server();
    server.createContext(
        "/ingest",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(403, -1);
          exchange.close();
        });
    server.start();
    try {
      PublishMojo mojo = mojo(server);
      set(mojo, "receipt", directory.resolve("receipt.json").toFile());
      assertThatThrownBy(mojo::execute)
          .isInstanceOf(MojoFailureException.class)
          .hasMessageContaining("acknowledgment");
      assertThat(Files.readString(directory.resolve("receipt.json"))).contains("REJECTED");
      set(mojo, "failOnError", false);
      assertThatCode(mojo::execute).doesNotThrowAnyException();
      set(mojo, "sha256", "0".repeat(64));
      assertThatThrownBy(mojo::execute)
          .isInstanceOf(MojoExecutionException.class)
          .hasMessageContaining("ARTIFACT_DIGEST_MISMATCH");
      set(mojo, "endpoint", "https://private-token@localhost/secret?token=private-token");
      assertThatThrownBy(mojo::execute).hasMessageNotContaining("private-token");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void resolvesEnvironmentReferenceWithoutPuttingTokenInConfiguration() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    HttpsServer server = tls.server();
    server.createContext(
        "/ingest",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      PublishMojo mojo =
          configured(
              new PublishMojo() {
                @Override
                String environment(String name) {
                  return "private-test-token";
                }
              },
              server);
      set(mojo, "auth", "bearer");
      set(mojo, "tokenEnv", "UPLOAD_TOKEN");
      mojo.execute();
      assertThat(authorization.get()).isEqualTo("Bearer private-test-token");
      try (var receipts = Files.list(directory.resolve("receipts"))) {
        assertThat(Files.readString(receipts.findFirst().orElseThrow()))
            .doesNotContain("private-test-token");
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void configurationErrorsAreActionableAndFailBeforeTransport() throws Exception {
    HttpsServer server = tls.server();
    try {
      for (String parameter :
          new String[] {"sha256", "destinationId", "application", "module", "version", "runId"}) {
        PublishMojo mojo = mojo(server);
        set(mojo, parameter, null);
        assertThatThrownBy(mojo::execute).hasMessage("Missing census.delivery." + parameter);
      }
      PublishMojo mojo = mojo(server);
      set(mojo, "auth", "bearer");
      assertThatThrownBy(mojo::execute).hasMessageContaining("exactly one");
      set(mojo, "tokenEnv", "INVALID-REF");
      assertThatThrownBy(mojo::execute).hasMessageContaining("must name an environment");
      set(mojo, "tokenEnv", "CENSUS_TEST_ABSENT_CREDENTIAL_6215");
      assertThatThrownBy(mojo::execute).hasMessageContaining("absent or empty");
      set(mojo, "serverId", "inventory");
      assertThatThrownBy(mojo::execute).hasMessageContaining("exactly one");
      set(mojo, "auth", "none");
      assertThatThrownBy(mojo::execute).hasMessageContaining("cannot specify");
    } finally {
      server.stop(0);
    }
  }

  @Test
  @SuppressWarnings(
      "deprecation") // Construct the supported Maven 3 session for settings resolution.
  void decryptsSettingsCredentialsAndRejectsMissingServers() throws Exception {
    HttpsServer server = tls.server();
    server.createContext(
        "/ingest",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(
              "Bearer settings-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                  ? 204
                  : 403,
              -1);
          exchange.close();
        });
    server.start();
    try {
      PublishMojo mojo = mojo(server);
      set(mojo, "auth", "bearer");
      set(mojo, "serverId", "inventory");
      var request = new DefaultMavenExecutionRequest();
      MavenSession session =
          new MavenSession(null, null, request, new DefaultMavenExecutionResult());
      set(mojo, "session", session);
      assertThatThrownBy(mojo::execute).hasMessageContaining("absent from Maven settings");
      Server serverCredential = new Server();
      serverCredential.setId("inventory");
      serverCredential.setPassword("{encrypted-test-value}");
      session.getSettings().addServer(serverCredential);
      Server decrypted = new Server();
      decrypted.setPassword("settings-token");
      set(mojo, "settingsDecrypter", (SettingsDecrypter) ignored -> result(decrypted));
      mojo.execute();
      set(mojo, "settingsDecrypter", (SettingsDecrypter) ignored -> result(null));
      assertThatThrownBy(mojo::execute).hasMessageContaining("could not decrypt");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void sealedRunPublishesExactBytesAndRejectsTamperingAndReceiptAliasesBeforeNetwork()
      throws Exception {
    java.util.concurrent.atomic.AtomicInteger requests =
        new java.util.concurrent.atomic.AtomicInteger();
    AtomicReference<byte[]> uploaded = new AtomicReference<>();
    HttpsServer server = tls.server();
    server.createContext(
        "/ingest",
        exchange -> {
          requests.incrementAndGet();
          uploaded.set(exchange.getRequestBody().readAllBytes());
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    try {
      final PublishMojo publish = mojo(server);
      String runId = java.util.UUID.randomUUID().toString();
      Path run = RunEvidence.prepare(directory, runId, "example:payments", "1.0");
      Files.copy(directory.resolve("cbom.json"), run.resolve("cbom-1.json"));
      final ReportMojo report = new ReportMojo();
      var project = new org.apache.maven.project.MavenProject();
      project.setGroupId("example");
      project.setArtifactId("payments");
      project.setVersion("1.0");
      for (var entry :
          Map.<String, Object>of(
                  "project",
                  project,
                  "destDir",
                  directory.toFile(),
                  "runId",
                  runId,
                  "revision",
                  "1",
                  "testOutcome",
                  "failed",
                  "failOn",
                  "broken")
              .entrySet()) {
        Field field = CheckMojo.class.getDeclaredField(entry.getKey());
        field.setAccessible(true);
        field.set(report, entry.getValue());
      }
      report.execute();
      Path manifest = RunEvidence.sealedDirectory(run, "1").resolve(RunEvidence.MANIFEST);
      Path artifact = manifest.resolveSibling(RunEvidence.ARTIFACT);
      final byte[] original = Files.readAllBytes(artifact);
      set(publish, "manifest", manifest.toFile());
      set(publish, "preparedRunId", runId);
      set(publish, "runId", null);
      set(publish, "sha256", null);
      assertThatThrownBy(publish::execute).hasMessageContaining("input conflicts");
      set(publish, "input", null);
      for (Path protectedPath :
          new Path[] {manifest, run.resolve("run.json"), run.resolve("cbom-1.json"), artifact}) {
        set(publish, "receipt", protectedPath.toFile());
        assertThatThrownBy(publish::execute).isInstanceOf(MojoExecutionException.class);
      }
      Path alias = directory.resolve("receipt-hardlink.json");
      Files.createLink(alias, manifest);
      set(publish, "receipt", alias.toFile());
      assertThatThrownBy(publish::execute).isInstanceOf(MojoExecutionException.class);
      assertThat(requests.get()).isZero();
      set(publish, "receipt", directory.resolve("sealed-receipt.json").toFile());
      publish.execute();
      assertThat(uploaded.get()).isEqualTo(original);
      assertThat(requests.get()).isEqualTo(1);
      Files.writeString(artifact, Files.readString(artifact) + " ");
      assertThatThrownBy(publish::execute).isInstanceOf(MojoExecutionException.class);
      assertThat(requests.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  @SuppressWarnings("deprecation")
  void environmentAndDecryptedSettingsProxyCredentialsStayAtTheProxy() throws Exception {
    HttpsServer server = tls.server();
    AtomicReference<String> leaked = new AtomicReference<>();
    server.createContext(
        "/ingest",
        exchange -> {
          leaked.set(exchange.getRequestHeaders().getFirst("Proxy-Authorization"));
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    final String basic =
        "Basic "
            + java.util.Base64.getEncoder()
                .encodeToString(
                    "private-proxy-user:private-proxy-password"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    try {
      for (boolean settings : new boolean[] {false, true}) {
        try (var proxy =
            new com.developmentsprint.cryptocensus.delivery.testing.TunnelProxy(
                server.getAddress().getPort(), basic)) {
          PublishMojo publish =
              configured(
                  new PublishMojo() {
                    @Override
                    String environment(String name) {
                      return "PROXY_USER".equals(name)
                          ? "private-proxy-user"
                          : "private-proxy-password";
                    }
                  },
                  server);
          set(publish, "proxyHost", "localhost");
          set(publish, "proxyPort", proxy.port());
          if (settings) {
            var request = new DefaultMavenExecutionRequest();
            final MavenSession session =
                new MavenSession(null, null, request, new DefaultMavenExecutionResult());
            final Server credential = new Server();
            credential.setId("proxy");
            credential.setUsername("encrypted");
            credential.setPassword("encrypted");
            session.getSettings().addServer(credential);
            final Server decoded = new Server();
            decoded.setUsername("private-proxy-user");
            decoded.setPassword("private-proxy-password");
            set(publish, "session", session);
            set(publish, "proxyServerId", "proxy");
            set(publish, "settingsDecrypter", (SettingsDecrypter) ignored -> result(decoded));
          } else {
            set(publish, "proxyUserEnv", "PROXY_USER");
            set(publish, "proxyPasswordEnv", "PROXY_PASSWORD");
          }
          publish.execute();
          assertThat(proxy.connectRequest()).contains(basic);
          assertThat(leaked.get()).isNull();
        }
      }
      try (var receipts = Files.list(directory.resolve("receipts"))) {
        for (Path receipt : receipts.toList()) {
          assertThat(Files.readString(receipt)).doesNotContain("private-proxy", basic);
        }
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void proxyReferencesAreExclusiveCompleteAndRequireExplicitHost() throws Exception {
    HttpsServer server = tls.server();
    try {
      PublishMojo publish = mojo(server);
      set(publish, "proxyUserEnv", "PROXY_USER");
      assertThatThrownBy(publish::execute)
          .hasMessageContaining("requires census.delivery.proxyHost");
      set(publish, "proxyHost", "localhost");
      assertThatThrownBy(publish::execute).hasMessageContaining("both proxyUserEnv");
      set(publish, "proxyPasswordEnv", "INVALID-REF");
      assertThatThrownBy(publish::execute).hasMessageContaining("environment variable names");
      set(publish, "proxyServerId", "proxy");
      assertThatThrownBy(publish::execute).hasMessageContaining("not both");
      set(publish, "proxyUserEnv", null);
      set(publish, "proxyPasswordEnv", null);
      assertThatThrownBy(publish::execute).hasMessageContaining("resolution is unavailable");
    } finally {
      server.stop(0);
    }
  }

  private static SettingsDecryptionResult result(Server server) {
    return new SettingsDecryptionResult() {
      @Override
      public Server getServer() {
        return server;
      }

      @Override
      public java.util.List<Server> getServers() {
        return java.util.List.of();
      }

      @Override
      public org.apache.maven.settings.Proxy getProxy() {
        return null;
      }

      @Override
      public java.util.List<org.apache.maven.settings.Proxy> getProxies() {
        return java.util.List.of();
      }

      @Override
      public java.util.List<org.apache.maven.settings.building.SettingsProblem> getProblems() {
        return java.util.List.of();
      }
    };
  }

  private PublishMojo mojo(HttpsServer server) throws Exception {
    return configured(new PublishMojo(), server);
  }

  private PublishMojo configured(PublishMojo mojo, HttpsServer server) throws Exception {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    Cbom.HEALTH_PROPERTIES.forEach(name -> properties.put(name, "0"));
    Path artifact = directory.resolve("cbom.json");
    Cbom.write(new Inventory(), new Cbom.Metadata("payments", properties), artifact);
    set(mojo, "input", artifact.toFile());
    set(
        mojo,
        "sha256",
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))));
    set(mojo, "endpoint", "https://localhost:" + server.getAddress().getPort() + "/ingest");
    set(mojo, "destinationId", "inventory");
    set(mojo, "application", "payments");
    set(mojo, "module", "example:payments");
    set(mojo, "version", "1.0");
    set(mojo, "runId", "run-123");
    set(mojo, "auth", "none");
    set(mojo, "caBundle", tls.caBundle().toFile());
    set(mojo, "proxyPort", 8080);
    set(mojo, "receiptDirectory", directory.resolve("receipts").toFile());
    set(mojo, "failOnError", true);
    return mojo;
  }

  private static void set(PublishMojo mojo, String name, Object value) throws Exception {
    Field field = PublishMojo.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(mojo, value);
  }
}
