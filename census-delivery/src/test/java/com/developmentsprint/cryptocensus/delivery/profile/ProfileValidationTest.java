package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileValidationTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknown",
        "schema",
        "adapter",
        "endpoint",
        "destinationId",
        "inputEmpty",
        "inputKind",
        "missingMapping",
        "correlation",
        "prepMode",
        "authType",
        "authUnknown",
        "authNoneName",
        "authEnv",
        "authMissing",
        "options",
        "headerValue",
        "headerAuth",
        "policyRevision",
        "policyLoss",
        "policyField",
        "policyCompletion",
        "retentionRequired",
        "retentionBad",
        "proxyPort",
        "proxyRange",
        "proxyType",
        "requiredFields",
        "duplicateFields",
        "duplicateLosses",
        "outputs",
        "boolean"
      })
  void rejectsUnsafeOrUnsupportedProfilesBeforeAnyNetwork(String scenario) throws Exception {
    Map<String, Object> config =
        ProfileFixture.configuration(directory, "https://example.invalid/upload", null);
    switch (scenario) {
      case "unknown" -> config.put("insecure", true);
      case "schema" -> config.put("schema", "census.delivery-profile/2");
      case "adapter" -> config.put("adapter", "untrusted-class-name");
      case "endpoint" ->
          config.put("endpoint", "https://user:secret@example.invalid/upload?token=secret");
      case "destinationId" -> config.put("destinationId", "invalid destination");
      case "inputEmpty" -> config.put("inputs", List.of());
      case "inputKind", "missingMapping", "correlation" -> {
        Map<String, Object> input =
            new java.util.LinkedHashMap<>(
                JsonSupport.object(((List<?>) config.get("inputs")).get(0)));
        if (scenario.equals("inputKind")) {
          input.put("kind", "FORGED");
        } else if (scenario.equals("missingMapping")) {
          input.remove("correlation");
        } else {
          input.put(
              "correlation",
              Map.of("application", "with space", "module", "m", "version", "v", "runId", "r"));
        }
        config.put("inputs", List.of(input));
      }
      case "prepMode" ->
          config.put("preparation", Map.of("mode", "SCRIPT", "outputDirectory", "out"));
      case "authType" -> config.put("authentication", Map.of("type", "LITERAL", "name", "secret"));
      case "authUnknown" ->
          config.put("authentication", Map.of("type", "ENV", "name", "TOKEN", "value", "secret"));
      case "authNoneName" -> config.put("authentication", Map.of("type", "NONE", "name", "TOKEN"));
      case "authEnv" -> config.put("authentication", Map.of("type", "ENV", "name", "TOKEN-NAME"));
      case "authMissing" -> config.put("authentication", Map.of("type", "ENV"));
      case "options" -> config.put("options", Map.of("script", "executable"));
      case "headerValue" ->
          config.put(
              "options",
              Map.of("headers", Map.of("X-Tenant-Id", "tenant\r\nAuthorization:secret")));
      case "headerAuth" ->
          config.put("options", Map.of("headers", Map.of("Authorization", "Bearer secret")));
      case "policyRevision" ->
          config.put("policy", policy("capabilityRevision", "generic-https/2"));
      case "policyLoss" ->
          config.put("policy", policy("acceptedLossCodes", List.of("UNVERIFIED_LOSS")));
      case "policyField" ->
          config.put("policy", policy("requiredFields", List.of("cryptoProperties")));
      case "policyCompletion" -> config.put("policy", policy("completion", "INGESTION_COMPLETED"));
      case "retentionRequired" -> config.put("policy", policy("requireRemoteRetention", true));
      case "retentionBad" ->
          config.put(
              "retention",
              Map.of(
                  "endpoint", "http://example.invalid", "authentication", Map.of("type", "NONE")));
      case "proxyPort" -> config.put("transport", Map.of("proxyPort", 8080));
      case "proxyRange" ->
          config.put("transport", Map.of("proxyHost", "localhost", "proxyPort", 70000));
      case "proxyType" ->
          config.put("transport", Map.of("proxyHost", "localhost", "proxyPort", "8080"));
      case "requiredFields" -> config.put("policy", policy("requiredFields", "cryptoTypes"));
      case "duplicateFields" ->
          config.put("policy", policy("requiredFields", List.of("cryptoTypes", "cryptoTypes")));
      case "duplicateLosses" ->
          config.put("policy", policy("acceptedLossCodes", List.of("LOSS", "LOSS")));
      case "outputs" ->
          config.put(
              "outputs", Map.of("plan", "plan.json", "receipt", "receipt.json", "script", "x"));
      case "boolean" -> config.put("policy", policy("requireRemoteRetention", "yes"));
      default -> throw new AssertionError(scenario);
    }
    Path profile = ProfileFixture.write(directory, config);
    assertThatThrownBy(
            () ->
                ProfileCommand.run(
                    "plan",
                    profile,
                    null,
                    reference -> {
                      throw new AssertionError("No credential lookup");
                    }))
        .isInstanceOf(Exception.class);
  }

  @Test
  void jsonIsStrictBoundedAndRedacted() {
    assertThatThrownBy(
            () -> JsonSupport.parse("{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JsonSupport.parse(new byte[] {(byte) 0xc3, (byte) 0x28}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JsonSupport.parse(new byte[16 * 1024 * 1024 + 1]))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JsonSupport.write(new Object()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JsonSupport.object(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JsonSupport.object(Map.of(1, "value")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JsonSupport.string(Map.of("field", 5), "field"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void incompatibleCommandsAndRegistriesFailClosed() throws Exception {
    Map<String, Object> config =
        ProfileFixture.configuration(directory, "https://example.invalid/upload", null);
    Path profile = ProfileFixture.write(directory, config);
    assertThatThrownBy(() -> ProfileCommand.run("executeScript", profile, null, reference -> null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ProfileCommand.run("plan", profile, "0".repeat(64), reference -> null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ProfileReader(
                    List.of(new GenericProfileAdapter(), new GenericProfileAdapter())))
        .isInstanceOf(IllegalArgumentException.class);
    Files.writeString(profile, " ".repeat(65537));
    assertThatThrownBy(() -> new ProfileReader(List.of()).read(profile))
        .isInstanceOf(java.io.IOException.class);
  }

  @Test
  void mutableProtocolBuffersCannotChangeStoredRequestsOrLeakResponses() {
    byte[] bytes = {1};
    AdapterRequest request =
        new AdapterRequest("POST", "api/v1/bom", "application/json", bytes, true, 0);
    bytes[0] = 9;
    assertThat(request.body()).containsExactly(1);
    request.body()[0] = 5;
    assertThat(request.body()).containsExactly(1);
    ProtocolResponse response = new ProtocolResponse(200, bytes, null);
    bytes[0] = 3;
    assertThat(response.body()).containsExactly(9);
    response.body()[0] = 0;
    assertThat(response.body()).containsExactly(9);
    assertThat(response.toString()).doesNotContain("body");
    assertThat(new SecretReference(SecretReference.Kind.ENV, "TOKEN").toString())
        .doesNotContain("TOKEN");
    assertThatThrownBy(() -> new SecretReference(null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AdapterResult(
                    AdapterResult.Upload.ACCEPTED,
                    AdapterResult.Ingestion.COMPLETED,
                    "private token",
                    200,
                    null,
                    null,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AdapterResult(
                    AdapterResult.Upload.ACCEPTED,
                    AdapterResult.Ingestion.COMPLETED,
                    "OK",
                    200,
                    "token/url",
                    null,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AdapterResult(
                    AdapterResult.Upload.ACCEPTED,
                    AdapterResult.Ingestion.COMPLETED,
                    "OK",
                    200,
                    null,
                    "v\r\n",
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static Map<String, Object> policy(String key, Object value) {
    Map<String, Object> policy =
        new java.util.LinkedHashMap<>(
            Map.of(
                "capabilityRevision",
                "generic-https/1",
                "acceptedLossCodes",
                List.of(),
                "completion",
                "UPLOAD_ACCEPTED"));
    policy.put(key, value);
    return policy;
  }
}
