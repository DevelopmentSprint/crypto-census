package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.delivery.preparation.PreparationRequest;
import java.net.URI;
import java.nio.file.Path;

/** Strictly decoded version-one profile; secret references have not been resolved. */
public record DeliveryProfile(
    Path source,
    String sourceSha256,
    String destinationId,
    DestinationAdapter adapter,
    URI endpoint,
    AdapterOptions options,
    PreparationRequest preparation,
    SecretReference authentication,
    Transport transport,
    DeliveryPolicy policy,
    Retention retention,
    Path planPath,
    Path receiptPath) {
  /** Explicit trust/proxy configuration used only at execution. */
  public record Transport(Path caBundle, String proxyHost, int proxyPort) {}

  /** Independently authenticated original-evidence retrieval endpoint. */
  public record Retention(URI endpoint, SecretReference authentication) {
    @Override
    public String toString() {
      return "Retention[endpoint=REDACTED]";
    }
  }

  @Override
  public String toString() {
    return "DeliveryProfile[destinationId=" + destinationId + "]";
  }
}
