package com.developmentsprint.cryptocensus.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.delivery.profile.JsonSupport;
import com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileCliTest {
  @TempDir static Path certificates;
  static TlsFixture tls;
  @TempDir Path directory;
  HttpsServer server;
  Path profile;
  final AtomicInteger status = new AtomicInteger(202);
  final AtomicInteger uploads = new AtomicInteger();
  final ByteArrayOutputStream output = new ByteArrayOutputStream();
  final ByteArrayOutputStream error = new ByteArrayOutputStream();

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
  void plansThenPublishesUsingTheSharedHashAndSafeReceipt() throws Exception {
    assertThat(run(Map.of(), "delivery", "--action", "plan", "--profile", profile.toString()))
        .isZero();
    assertThat(uploads.get()).isZero();
    String hash = planHash();
    assertThat(
            run(
                Map.of("CENSUS_TEST_TOKEN", "hidden-secret"),
                "delivery",
                "--action",
                "preflight",
                "--profile",
                profile.toString(),
                "--plan-sha256",
                hash))
        .isZero();
    assertThat(uploads.get()).isZero();
    assertThat(
            run(
                Map.of("CENSUS_TEST_TOKEN", "hidden-secret"),
                "delivery",
                "--action",
                "publish",
                "--profile",
                profile.toString(),
                "--plan-sha256",
                hash))
        .isZero();
    assertThat(uploads.get()).isEqualTo(1);
    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("ACCEPTED", "NOT_APPLICABLE")
        .doesNotContain("hidden-secret");
    assertThat(Files.readString(directory.resolve("receipt.json"))).doesNotContain("hidden-secret");
  }

  @Test
  void reportingOnlyDoesNotHideMissingCredentialsOrInvalidPlan() throws Exception {
    assertThat(run(Map.of(), "delivery", "--action", "plan", "--profile", profile.toString()))
        .isZero();
    String hash = planHash();
    assertThat(
            run(
                Map.of(),
                "delivery",
                "--action",
                "publish",
                "--profile",
                profile.toString(),
                "--plan-sha256",
                hash,
                "--report-only"))
        .isEqualTo(2);
    status.set(500);
    assertThat(
            run(
                Map.of("CENSUS_TEST_TOKEN", "secret"),
                "delivery",
                "--action",
                "publish",
                "--profile",
                profile.toString(),
                "--plan-sha256",
                hash))
        .isEqualTo(4);
    assertThat(
            run(
                Map.of("CENSUS_TEST_TOKEN", "secret"),
                "delivery",
                "--action",
                "publish",
                "--profile",
                profile.toString(),
                "--plan-sha256",
                hash,
                "--report-only"))
        .isZero();
    assertThat(Files.readString(directory.resolve("receipt.json"))).contains("UNKNOWN");
  }

  @Test
  void malformedCommandsAreActionableAndDoNotPrintValues() {
    for (String[] args :
        new String[][] {
          {"delivery"},
          {"delivery", "--action", "plan"},
          {"delivery", "--action", "publish", "--profile", profile.toString()},
          {"delivery", "--action", "plan", "--action", "plan"},
          {"delivery", "unexpected-secret"},
          {"delivery", "--token", "unexpected-secret"},
          {"delivery", "--action"}
        }) {
      assertThat(run(Map.of(), args)).isEqualTo(2);
    }
    assertThat(error.toString(StandardCharsets.UTF_8))
        .contains("Missing required")
        .doesNotContain("unexpected-secret");
  }

  @Test
  void unsupportedProfilesRemainSafeAndMavenSecretsAreNotResolvedByCli() throws Exception {
    Map<String, Object> values = JsonSupport.object(JsonSupport.parse(Files.readAllBytes(profile)));
    values.put("authentication", Map.of("type", "MAVEN_SERVER", "name", "inventory"));
    Files.write(profile, JsonSupport.write(values));
    assertThat(run(Map.of(), "delivery", "--action", "plan", "--profile", profile.toString()))
        .isZero();
    assertThat(
            run(
                Map.of(),
                "delivery",
                "--action",
                "publish",
                "--profile",
                profile.toString(),
                "--plan-sha256",
                planHash()))
        .isEqualTo(2);
    assertThat(error.toString(StandardCharsets.UTF_8)).contains("Maven");
    Files.writeString(profile, "{secret-value-invalid-json");
    assertThat(run(Map.of(), "delivery", "--action", "plan", "--profile", profile.toString()))
        .isEqualTo(2);
    assertThat(error.toString(StandardCharsets.UTF_8)).doesNotContain("secret-value");
  }

  private int run(Map<String, String> environment, String... args) {
    return CensusCli.run(
        args,
        environment,
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8));
  }

  private String planHash() throws Exception {
    return JsonSupport.string(
        JsonSupport.object(JsonSupport.parse(Files.readAllBytes(directory.resolve("plan.json")))),
        "planSha256");
  }
}
