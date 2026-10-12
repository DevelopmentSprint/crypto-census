package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.delivery.profile.JsonSupport;
import com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpsServer;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeliveryMojoTest {
  @TempDir static Path certificates;
  static TlsFixture tls;
  @TempDir Path directory;
  HttpsServer server;
  Path profile;
  final AtomicInteger status = new AtomicInteger(202);
  final AtomicInteger uploads = new AtomicInteger();

  @BeforeAll
  static void certificates() throws Exception {
    tls = new TlsFixture(certificates);
  }

  @BeforeEach
  void setup() throws Exception {
    server = tls.server();
    server.createContext(
        "/upload",
        exchange -> {
          uploads.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.start();
    profile =
        ProfileFixture.write(
            directory,
            ProfileFixture.configuration(
                directory,
                "https://localhost:" + server.getAddress().getPort() + "/upload",
                tls.caBundle()));
  }

  @AfterEach
  void cleanup() {
    server.stop(0);
  }

  @Test
  void actualMojoUsesSharedPlanPreflightAndPublisher() throws Exception {
    DeliveryMojo mojo = mojo();
    set(mojo, "action", "plan");
    mojo.execute();
    assertThat(uploads.get()).isZero();
    set(mojo, "planSha256", planHash());
    set(mojo, "action", "preflight");
    mojo.execute();
    assertThat(uploads.get()).isZero();
    set(mojo, "action", "publish");
    mojo.execute();
    assertThat(uploads.get()).isEqualTo(1);
    assertThat(Files.readString(directory.resolve("receipt.json")))
        .contains("ACCEPTED", "census.delivery-receipt/2")
        .doesNotContain("private-maven-token");
  }

  @Test
  void failOnErrorOnlyChangesRecordedOutcomeGating() throws Exception {
    DeliveryMojo mojo = mojo();
    set(mojo, "action", "plan");
    mojo.execute();
    set(mojo, "planSha256", planHash());
    set(mojo, "action", "publish");
    status.set(503);
    assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
    set(mojo, "failOnError", false);
    assertThatCode(mojo::execute).doesNotThrowAnyException();
    set(mojo, "planSha256", "0".repeat(64));
    assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class);
    assertThat(uploads.get()).isEqualTo(2);
  }

  @Test
  void configurationAndCredentialFailuresAreSafe() throws Exception {
    DeliveryMojo mojo = mojo();
    set(mojo, "profile", null);
    assertThatThrownBy(mojo::execute).hasMessageContaining("Missing census.delivery.profile");
    set(mojo, "profile", profile.toFile());
    set(mojo, "action", null);
    assertThatThrownBy(mojo::execute).hasMessageContaining("Missing census.delivery.action");
    set(mojo, "action", "publish");
    assertThatThrownBy(mojo::execute).hasMessageContaining("planSha256");
    DeliveryMojo noSecret = new DeliveryMojo();
    set(noSecret, "profile", profile.toFile());
    set(noSecret, "action", "plan");
    noSecret.execute();
    set(noSecret, "action", "publish");
    set(noSecret, "planSha256", planHash());
    assertThatThrownBy(noSecret::execute).hasMessageContaining("absent or empty");
    assertThat(uploads.get()).isZero();
  }

  @Test
  @SuppressWarnings(
      "deprecation") // Maven 3 session constructor used only by this isolated fixture.
  void decryptsMavenServerAndRedactsUncheckedProviderFailures() throws Exception {
    var config =
        ProfileFixture.configuration(
            directory,
            "https://localhost:" + server.getAddress().getPort() + "/upload",
            tls.caBundle());
    config.put("authentication", java.util.Map.of("type", "MAVEN_SERVER", "name", "inventory"));
    ProfileFixture.write(directory, config);
    DeliveryMojo mojo = mojo();
    set(mojo, "action", "plan");
    mojo.execute();
    set(mojo, "action", "publish");
    set(mojo, "planSha256", planHash());
    assertThatThrownBy(mojo::execute).hasMessageContaining("resolution is unavailable");
    var session =
        new org.apache.maven.execution.MavenSession(
            null,
            null,
            new org.apache.maven.execution.DefaultMavenExecutionRequest(),
            new org.apache.maven.execution.DefaultMavenExecutionResult());
    set(mojo, "session", session);
    org.apache.maven.settings.Server decrypted = new org.apache.maven.settings.Server();
    decrypted.setPassword("private-decrypted-token");
    org.apache.maven.settings.crypto.SettingsDecryptionResult result =
        (org.apache.maven.settings.crypto.SettingsDecryptionResult)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {org.apache.maven.settings.crypto.SettingsDecryptionResult.class},
                (proxy, method, arguments) ->
                    "getServer".equals(method.getName()) ? decrypted : java.util.List.of());
    set(
        mojo,
        "settingsDecrypter",
        (org.apache.maven.settings.crypto.SettingsDecrypter) request -> result);
    assertThatThrownBy(mojo::execute).hasMessageContaining("server is missing");
    org.apache.maven.settings.Server configured = new org.apache.maven.settings.Server();
    configured.setId("inventory");
    configured.setPassword("{encrypted-value}");
    session.getSettings().addServer(configured);
    mojo.execute();
    assertThat(uploads.get()).isEqualTo(1);
    decrypted.setPassword("");
    assertThatThrownBy(mojo::execute).hasMessageContaining("decryption failed");
    set(
        mojo,
        "settingsDecrypter",
        (org.apache.maven.settings.crypto.SettingsDecrypter)
            request -> {
              throw new IllegalStateException("private-provider-secret");
            });
    assertThatThrownBy(mojo::execute)
        .isInstanceOf(MojoExecutionException.class)
        .hasMessageNotContaining("private-provider-secret")
        .hasNoCause();
    assertThat(uploads.get()).isEqualTo(1);
  }

  @Test
  void manifestBoundProfileRejectsWrongRunMappingAndProtectedOutputsBeforeEffects()
      throws Exception {
    String runId = java.util.UUID.randomUUID().toString();
    Path run = RunEvidence.prepare(directory, runId, "example:module", "1.0");
    Files.copy(directory.resolve("cbom.json"), run.resolve("cbom-1.json"));
    var project = new org.apache.maven.project.MavenProject();
    project.setGroupId("example");
    project.setArtifactId("module");
    project.setVersion("1.0");
    ReportMojo report = new ReportMojo();
    for (var entry :
        java.util.Map.<String, Object>of(
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
    var config = JsonSupport.object(JsonSupport.parse(Files.readAllBytes(profile)));
    var correlation =
        new java.util.LinkedHashMap<String, Object>(
            java.util.Map.of(
                "application",
                "app",
                "module",
                "example:module",
                "version",
                "1.0",
                "runId",
                runId));
    var input =
        new java.util.LinkedHashMap<String, Object>(
            java.util.Map.of(
                "path",
                artifact.toString(),
                "sha256",
                ProfileFixture.hash(Files.readAllBytes(artifact)),
                "kind",
                "CENSUS_MERGED_CBOM",
                "correlation",
                correlation));
    config.put("inputs", java.util.List.of(input));
    DeliveryMojo mojo = mojo();
    set(mojo, "manifest", manifest.toFile());
    set(mojo, "runId", runId);
    set(mojo, "application", "app");
    set(mojo, "module", "example:module");
    set(mojo, "version", "1.0");
    set(mojo, "action", "plan");
    for (String key : java.util.List.of("runId", "module", "version", "application")) {
      Object original = correlation.put(key, "wrong");
      ProfileFixture.write(directory, config);
      assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class);
      correlation.put(key, original);
      assertThat(Files.exists(directory.resolve("plan.json"))).isFalse();
      assertThat(Files.exists(directory.resolve("prepared"))).isFalse();
    }
    Object outputs =
        config.put(
            "outputs",
            java.util.Map.of(
                "plan",
                manifest.toString(),
                "receipt",
                directory.resolve("receipt.json").toString()));
    ProfileFixture.write(directory, config);
    assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class);
    config.put("outputs", outputs);
    ProfileFixture.write(directory, config);
    set(mojo, "runId", java.util.UUID.randomUUID().toString());
    assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class);
    set(mojo, "runId", runId);
    assertThat(uploads.get()).isZero();
    mojo.execute();
    set(mojo, "action", "publish");
    set(mojo, "planSha256", planHash());
    mojo.execute();
    assertThat(uploads.get()).isEqualTo(1);
    Files.writeString(artifact, Files.readString(artifact) + " ");
    assertThatThrownBy(mojo::execute).isInstanceOf(MojoExecutionException.class);
    assertThat(uploads.get()).isEqualTo(1);
  }

  private DeliveryMojo mojo() throws Exception {
    DeliveryMojo mojo =
        new DeliveryMojo() {
          @Override
          String environment(String name) {
            return "private-maven-token";
          }
        };
    set(mojo, "profile", profile.toFile());
    set(mojo, "failOnError", true);
    return mojo;
  }

  private String planHash() throws Exception {
    return JsonSupport.string(
        JsonSupport.object(JsonSupport.parse(Files.readAllBytes(directory.resolve("plan.json")))),
        "planSha256");
  }

  private static void set(DeliveryMojo mojo, String name, Object value) throws Exception {
    Field field = DeliveryMojo.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(mojo, value);
  }
}
