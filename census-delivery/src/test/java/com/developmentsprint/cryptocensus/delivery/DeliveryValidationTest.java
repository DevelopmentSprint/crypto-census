package com.developmentsprint.cryptocensus.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.Inventory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class DeliveryValidationTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://localhost/upload",
        "https://user:password@example.com",
        "https://example.com?token=secret",
        "https://example.com/#secret",
        "https://example.com:0",
        "https://example.com:70000",
        "https:/missing-host"
      })
  void rejectsUnsafeDestinationsWithoutEchoingValues(String endpoint) {
    assertThatThrownBy(() -> request(endpoint, "app"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(endpoint);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "secret\r\ninjected: true", "é", "has whitespace", "\u007f"})
  void rejectsHeaderInjectionAndUnsupportedMetadata(String application) {
    assertThatThrownBy(() -> request("https://example.com", application))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validatesBoundsAndRedactsCredentialAndRequestObjects() {
    assertThatThrownBy(() -> DeliveryCredentials.bearer("bad\r\nvalue"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("bad");
    assertThatThrownBy(() -> DeliveryCredentials.bearer(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DeliveryCredentials.bearer("x" + "=".repeat(8192)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(DeliveryCredentials.bearer("top-secret").toString()).doesNotContain("top-secret");
    assertThat(DeliveryCredentials.none().authorization()).isNull();
    assertThat(request("https://example.com/sensitive-path", "app").toString())
        .doesNotContain("sensitive-path");
    for (Duration bad :
        new Duration[] {null, Duration.ZERO, Duration.ofNanos(1), Duration.ofSeconds(61)}) {
      assertThatThrownBy(
              () ->
                  new DeliveryOptions(
                      bad, Duration.ofSeconds(1), Duration.ofSeconds(1), null, null))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(DeliveryOptions.defaults().toString())
        .contains("PT10S")
        .doesNotContain("SSLContext");
  }

  @Test
  void invalidCaAndProxyConfigurationFailsClosed() throws Exception {
    for (String content : new String[] {"", "not-a-cert", "x".repeat(1024 * 1024 + 1)}) {
      Path ca = Files.writeString(directory.resolve("ca.pem"), content);
      assertThatThrownBy(() -> TransportConfiguration.options(ca, null, 0))
          .isInstanceOf(DeliveryException.class)
          .hasMessageNotContaining("not-a-cert")
          .hasNoCause();
    }
    assertThatThrownBy(() -> TransportConfiguration.options(directory.resolve("missing"), null, 0))
        .isInstanceOf(DeliveryException.class);
    assertThatThrownBy(() -> TransportConfiguration.options(null, "user:secret@proxy", 80))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("secret");
    assertThatThrownBy(() -> TransportConfiguration.options(null, "proxy.example.com", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TransportConfiguration.options(null, null, 0).proxySelector()).isNull();
    assertThat(TransportConfiguration.options(null, "proxy.example.com", 8080).proxySelector())
        .isNotNull();
  }

  @Test
  void mergedEnvelopeRequiresProjectInputsAndEveryAvailableHealthCounter() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("cryptocensus:project", "example:module:1.0");
    properties.put("cryptocensus:input:one.json", "sha256:" + "a".repeat(64));
    for (String key :
        new String[] {
          "transformErrors", "recordingFailures", "drainTimedOut", "dropped", "overflowedSites"
        }) {
      properties.put("cryptocensus:" + key, "0");
    }
    EvidenceValidator.validate(new Cbom.Document(new Inventory(), properties));
    for (String key : properties.keySet()) {
      Map<String, String> missing = new LinkedHashMap<>(properties);
      missing.remove(key);
      assertThatThrownBy(
              () -> EvidenceValidator.validate(new Cbom.Document(new Inventory(), missing)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Map<String, String> invalid = new LinkedHashMap<>(properties);
    invalid.put("cryptocensus:input:one.json", "sha256:bad");
    assertThatThrownBy(
            () -> EvidenceValidator.validate(new Cbom.Document(new Inventory(), invalid)))
        .isInstanceOf(IllegalArgumentException.class);
    invalid.put("cryptocensus:input:one.json", properties.get("cryptocensus:input:one.json"));
    invalid.put("cryptocensus:overflowedSites", "1");
    assertThatThrownBy(
            () -> EvidenceValidator.validate(new Cbom.Document(new Inventory(), invalid)))
        .isInstanceOf(IllegalArgumentException.class);
    invalid.put(Cbom.SCHEMA_PROPERTY, "unknown-schema");
    assertThatThrownBy(
            () -> EvidenceValidator.validate(new Cbom.Document(new Inventory(), invalid)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private DeliveryRequest request(String endpoint, String application) {
    return new DeliveryRequest(
        directory.resolve("cbom.json"),
        directory.resolve("receipt.json"),
        "a".repeat(64),
        "company",
        URI.create(endpoint),
        application,
        "module",
        "1.0",
        "run-1",
        DeliveryCredentials.none(),
        DeliveryOptions.defaults());
  }
}
