package com.developmentsprint.cryptocensus.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeliveryIdentityTest {
  private String identity(Map<String, String> headers) {
    return DeliveryIdentity.of(
        URI.create("https://example.invalid/"),
        "destination",
        "app",
        "module",
        "1.0",
        "run",
        "0".repeat(64),
        headers);
  }

  @Test
  void routingContextChangesIdentityButMapOrderDoesNot() {
    var headers = new LinkedHashMap<String, String>();
    headers.put("X-Tenant-Id", "tenant-a");
    headers.put("X-Correlation-Id", "build");
    var reversed = new LinkedHashMap<String, String>();
    reversed.put("X-Correlation-Id", "build");
    reversed.put("X-Tenant-Id", "tenant-a");
    assertThat(identity(headers)).isEqualTo(identity(reversed));
    reversed.put("X-Tenant-Id", "tenant-b");
    assertThat(identity(headers)).isNotEqualTo(identity(reversed));
    assertThat(identity(Map.of()))
        .isEqualTo(
            DeliveryIdentity.of(
                URI.create("https://example.invalid/"),
                "destination",
                "app",
                "module",
                "1.0",
                "run",
                "0".repeat(64)));
    assertThatThrownBy(() -> identity(Map.of("Authorization", "Bearer secret")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("Bearer secret");
  }
}
