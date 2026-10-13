package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpsServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EnterpriseTransportTest {
  @TempDir static Path certificates;
  static TlsFixture tls;
  @TempDir Path directory;

  @BeforeAll
  static void certificates() throws Exception {
    tls = new TlsFixture(certificates);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "exact",
        "different",
        "missing",
        "redirect",
        "skipped",
        "before",
        "rejected",
        "dropped"
      })
  void authenticationAndReadbackAreEnforcedByEngine(String scenario) throws Exception {
    HttpsServer server = tls.server();
    AtomicReference<byte[]> stored = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    server.createContext(
        "/",
        exchange -> {
          auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          if ("POST".equals(exchange.getRequestMethod())) {
            stored.set(exchange.getRequestBody().readAllBytes());
            if (!"dropped".equals(scenario)) {
              exchange.sendResponseHeaders("rejected".equals(scenario) ? 500 : 201, -1);
            }
          } else {
            byte[] bytes = "different".equals(scenario) ? new byte[] {1} : stored.get();
            int code = "missing".equals(scenario) ? 404 : "redirect".equals(scenario) ? 302 : 200;
            exchange.getResponseHeaders().add("Location", "https://example.invalid/stolen");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
    try {
      var config =
          ProfileFixture.configuration(
              directory,
              "https://localhost:" + server.getAddress().getPort() + "/",
              tls.caBundle());
      config.put(
          "authentication",
          Map.of("type", "ENV_BASIC", "name", "PASSWORD", "usernameName", "USERNAME"));
      config.put("options", Map.of());
      // A valid document larger than the ordinary protocol-response limit.
      Path artifact = directory.resolve("cbom.json");
      Files.writeString(artifact, Files.readString(artifact) + " ".repeat(70000));
      var input =
          new java.util.LinkedHashMap<>(
              JsonSupport.object(((List<?>) config.get("inputs")).get(0)));
      input.put("sha256", ProfileFixture.hash(Files.readAllBytes(artifact)));
      config.put("inputs", List.of(input));
      ProfilePlanner planner =
          new ProfilePlanner(
              new ProfileReader(
                  List.of(new TestAdapter("skipped".equals(scenario), "before".equals(scenario)))));
      var plan = planner.plan(ProfileFixture.write(directory, config));
      stored.set(Files.readAllBytes(artifact));
      var receipt =
          new ProfileExecutor(planner)
              .execute(
                  plan,
                  reference -> SecretReference.encodeBasic("private-user", "private-password"));
      assertThat(receipt.successful()).isEqualTo("exact".equals(scenario));
      assertThat(receipt.observation().upload())
          .isEqualTo(
              ("rejected".equals(scenario) || "dropped".equals(scenario))
                  ? AdapterResult.Upload.UNKNOWN
                  : AdapterResult.Upload.ACCEPTED);
      if ("rejected".equals(scenario)) {
        assertThat(receipt.observation().reasonCode()).isEqualTo("UPLOAD_ACCEPTANCE_UNVERIFIED");
      }
      assertThat(auth.get()).isEqualTo("Basic cHJpdmF0ZS11c2VyOnByaXZhdGUtcGFzc3dvcmQ=");
      assertThat(stored.get()).isEqualTo(Files.readAllBytes(artifact));
      assertThat(receipt.destinationReadback() != null).isEqualTo("exact".equals(scenario));
      String json = Files.readString(directory.resolve("receipt.json"));
      assertThat(json)
          .contains("destinationReadback")
          .doesNotContain(
              "private-user", "private-password", "cHJpdmF0ZS11c2Vy", "https://localhost");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void credentialKindsAndValuesFailClosed() throws Exception {
    for (Map<String, Object> invalid :
        List.of(
            Map.<String, Object>of("type", "ENV_BASIC", "name", "PASSWORD"),
            Map.<String, Object>of(
                "type", "ENV_BASIC", "name", "PASSWORD", "usernameName", "bad-name"),
            Map.<String, Object>of("type", "ENV", "name", "PASSWORD", "usernameName", "USERNAME"),
            Map.<String, Object>of("type", "NONE", "usernameName", "USERNAME"))) {
      assertThatThrownBy(() -> SecretReference.parse(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (String username : List.of("", "user:name", "user\r\n", "é")) {
      assertThatThrownBy(() -> SecretReference.encodeBasic(username, "password"))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage(
              "BASIC_CREDENTIALS_INVALID: Basic credentials are missing or invalid; check"
                  + " the selected username and password sources.")
          .hasNoCause();
    }
    assertThatThrownBy(() -> SecretReference.encodeBasic("user", "password\n"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(SecretReference.encodeBasic("user", "password:with space"))
        .isEqualTo(
            java.util.Base64.getEncoder()
                .encodeToString("user:password:with space".getBytes(StandardCharsets.US_ASCII)));
    var config = ProfileFixture.configuration(directory, "https://example.invalid", null);
    config.put(
        "authentication",
        Map.of("type", "ENV_BASIC", "name", "PASSWORD", "usernameName", "USERNAME"));
    Path profile = ProfileFixture.write(directory, config);
    assertThatThrownBy(() -> new ProfileReader(List.of(new GenericProfileAdapter())).read(profile))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AdapterRequest("POST", "", null, new byte[0], true, 0)
                    .withPublicationReadback())
        .isInstanceOf(IllegalArgumentException.class);
  }

  private record TestAdapter(boolean skipDownload, boolean readBeforeUpload)
      implements DestinationAdapter {
    @Override
    public String id() {
      return "generic-https";
    }

    @Override
    public AdapterOptions parseOptions(Map<String, Object> raw) {
      return () -> AdapterOptions.AuthenticationScheme.BASIC;
    }

    @Override
    public CapabilityDeclaration capabilities() {
      return new GenericProfileAdapter().capabilities();
    }

    @Override
    public void validate(AdapterContext context) {}

    @Override
    public boolean requiresPublicationReadback() {
      return true;
    }

    @Override
    public AdapterSession open(AdapterContext context, byte[] publicationBytes) {
      return new AdapterSession() {
        private boolean uploaded;
        private boolean readFirst;

        @Override
        public ProtocolStep first() {
          if (readBeforeUpload && !readFirst) {
            return new ProtocolStep(
                new AdapterRequest("GET", "artifact", null, new byte[0], false, 0)
                    .withPublicationReadback(),
                result(AdapterResult.Upload.NOT_ATTEMPTED));
          }
          return new ProtocolStep(
              new AdapterRequest("POST", "upload", "application/json", publicationBytes, true, 0),
              result(AdapterResult.Upload.NOT_ATTEMPTED));
        }

        @Override
        public ProtocolStep accept(ProtocolResponse response) {
          if (readBeforeUpload && !readFirst) {
            readFirst = true;
            return first();
          }
          if (!uploaded) {
            uploaded = true;
            return new ProtocolStep(
                (skipDownload || readBeforeUpload)
                    ? null
                    : new AdapterRequest("GET", "artifact", null, new byte[0], false, 0)
                        .withPublicationReadback(),
                result(AdapterResult.Upload.ACCEPTED));
          }
          return new ProtocolStep(null, result(AdapterResult.Upload.ACCEPTED));
        }
      };
    }

    private static AdapterResult result(AdapterResult.Upload upload) {
      return new AdapterResult(
          upload,
          AdapterResult.Ingestion.NOT_APPLICABLE,
          "TEST_OBSERVATION",
          200,
          null,
          null,
          List.of());
    }
  }
}
