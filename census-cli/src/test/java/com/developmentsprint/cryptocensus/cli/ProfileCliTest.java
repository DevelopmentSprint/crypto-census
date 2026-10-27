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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"valid", "missing", "empty", "invalid", "redirect"})
  void resolvesBasicEnvironmentPairsAndNeverForwardsOnRedirect(String scenario) throws Exception {
    var config = JsonSupport.object(JsonSupport.parse(Files.readAllBytes(profile)));
    String base = "https://localhost:" + server.getAddress().getPort();
    config.put(
        "retention",
        Map.of(
            "endpoint",
            base + "/original",
            "authentication",
            Map.of("type", "ENV_BASIC", "name", "PASSWORD", "usernameName", "USERNAME")));
    ProfileFixture.write(directory, config);
    AtomicInteger readbacks = new AtomicInteger();
    AtomicInteger forwarded = new AtomicInteger();
    HttpsServer other = tls.server();
    other.createContext(
        "/",
        exchange -> {
          forwarded.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    other.start();
    server.createContext(
        "/original",
        exchange -> {
          readbacks.incrementAndGet();
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Basic dXNlcjpwcml2YXRlLWJhc2ljLXBhc3N3b3Jk");
          byte[] bytes = Files.readAllBytes(directory.resolve("cbom.json"));
          exchange
              .getResponseHeaders()
              .add("Location", "https://localhost:" + other.getAddress().getPort() + "/");
          exchange.sendResponseHeaders("redirect".equals(scenario) ? 302 : 200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    try {
      assertThat(run(Map.of(), "delivery", "--action", "plan", "--profile", profile.toString()))
          .isZero();
      Map<String, String> env =
          new java.util.HashMap<>(
              Map.of("CENSUS_TEST_TOKEN", "upload-secret", "PASSWORD", "private-basic-password"));
      if (!"missing".equals(scenario)) {
        env.put(
            "USERNAME",
            "empty".equals(scenario) ? "" : "invalid".equals(scenario) ? "user:name" : "user");
      }
      int code =
          run(
              env,
              "delivery",
              "--action",
              "publish",
              "--profile",
              profile.toString(),
              "--plan-sha256",
              planHash());
      assertThat(code)
          .isEqualTo("valid".equals(scenario) ? 0 : "redirect".equals(scenario) ? 4 : 2);
      assertThat(uploads.get()).isEqualTo("valid".equals(scenario) ? 1 : 0);
      assertThat(readbacks.get())
          .isEqualTo("valid".equals(scenario) || "redirect".equals(scenario) ? 1 : 0);
      assertThat(forwarded.get()).isZero();
      assertThat(error.toString(StandardCharsets.UTF_8))
          .doesNotContain("user:name", "private-basic-password", "dXNlcjpw", "PROFILE_INVALID");
      if (!"valid".equals(scenario) && !"redirect".equals(scenario)) {
        assertThat(error.toString(StandardCharsets.UTF_8))
            .isEqualTo(
                "BASIC_CREDENTIALS_INVALID: Basic credentials are missing or invalid; check"
                    + " the selected username and password sources."
                    + System.lineSeparator());
      }
    } finally {
      other.stop(0);
    }
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
