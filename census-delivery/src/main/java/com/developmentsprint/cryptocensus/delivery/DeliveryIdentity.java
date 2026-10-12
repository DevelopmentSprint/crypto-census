package com.developmentsprint.cryptocensus.delivery;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical submission identity shared by foundation publication and profile retries. */
public final class DeliveryIdentity {
  private DeliveryIdentity() {}

  /** Length-delimited identity; credentials and output paths never participate. */
  public static String of(
      URI endpoint,
      String destinationId,
      String application,
      String module,
      String version,
      String runId,
      String artifactSha256) {
    return of(
        endpoint,
        destinationId,
        application,
        module,
        version,
        runId,
        artifactSha256,
        java.util.Map.of());
  }

  /**
   * Profile variant binds explicit nonsecret routing headers, sorted independently of map order.
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "URI ASCII serialization defines the exact normalized endpoint identity")
  public static String of(
      URI endpoint,
      String destinationId,
      String application,
      String module,
      String version,
      String runId,
      String artifactSha256,
      java.util.Map<String, String> headers) {
    if (!java.util.Set.of("X-Tenant-Id", "X-Correlation-Id").containsAll(headers.keySet())) {
      throw new IllegalArgumentException(
          "Only explicit nonsecret routing headers participate in identity");
    }
    StringBuilder identity =
        new StringBuilder(headers.isEmpty() ? "census-delivery/1:" : "census-delivery/2:");
    for (String field :
        new String[] {
          endpoint.toASCIIString(),
          destinationId,
          application,
          module,
          version,
          runId,
          artifactSha256
        }) {
      identity.append(field.length()).append(':').append(field);
    }
    new java.util.TreeMap<>(headers)
        .forEach(
            (name, value) ->
                identity
                    .append(name.length())
                    .append(':')
                    .append(name)
                    .append(value.length())
                    .append(':')
                    .append(value));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(identity.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }
}
