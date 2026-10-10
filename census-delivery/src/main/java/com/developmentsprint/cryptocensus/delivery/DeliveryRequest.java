package com.developmentsprint.cryptocensus.delivery;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Explicit current-run artifact identity and operator-selected destination. */
public record DeliveryRequest(
    Path artifact,
    Path receipt,
    String expectedSha256,
    String destinationId,
    URI endpoint,
    String application,
    String module,
    String version,
    String runId,
    DeliveryCredentials credentials,
    DeliveryOptions options) {
  /** Validates explicit identity, metadata and an HTTPS destination without embedded secrets. */
  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Hash alphabet is validated ASCII hex; URI schemes are ASCII; URI serialization length is"
              + " bounded")
  public DeliveryRequest {
    Objects.requireNonNull(artifact, "Artifact path is required");
    Objects.requireNonNull(receipt, "Receipt path is required");
    Objects.requireNonNull(credentials, "Explicit credentials are required");
    Objects.requireNonNull(options, "Delivery options are required");
    if (expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}")) {
      throw new IllegalArgumentException(
          "Expected SHA-256 must contain exactly 64 hexadecimal characters");
    }
    expectedSha256 = expectedSha256.toLowerCase(Locale.ROOT);
    if (destinationId == null || !destinationId.matches("[A-Za-z0-9._-]{1,128}")) {
      throw new IllegalArgumentException(
          "Destination ID must be 1-128 letters, digits, dots, underscores or hyphens");
    }
    if (endpoint == null
        || !"https".equalsIgnoreCase(endpoint.getScheme())
        || endpoint.getHost() == null
        || endpoint.getRawUserInfo() != null
        || endpoint.getRawQuery() != null
        || endpoint.getRawFragment() != null
        || endpoint.getPort() == 0
        || endpoint.getPort() > 65535
        || endpoint.toASCIIString().length() > 2048) {
      throw new IllegalArgumentException(
          "Destination must be an HTTPS URL without credentials, query or fragment");
    }
    metadata(application);
    metadata(module);
    metadata(version);
    metadata(runId);
  }

  private static void metadata(String value) {
    if (value == null || !value.matches("[\\x21-\\x7e]{1,256}")) {
      throw new IllegalArgumentException(
          "Delivery metadata must contain 1-256 printable ASCII characters without whitespace");
    }
  }

  @Override
  public String toString() {
    return "DeliveryRequest[destinationId="
        + destinationId
        + ", endpoint=REDACTED, credentials=REDACTED]";
  }
}
