package com.developmentsprint.cryptocensus.delivery.profile;

import java.time.Instant;

/**
 * Engine-issued proof of remote byte equality at a particular instant, not indefinite retention.
 */
public final class VerifiedRetention {
  private final String artifactSha256;
  private final String endpointSha256;
  private final Instant verifiedAt;

  VerifiedRetention(String artifactSha256, String endpointSha256, Instant verifiedAt) {
    this.artifactSha256 = artifactSha256;
    this.endpointSha256 = endpointSha256;
    this.verifiedAt = verifiedAt;
  }

  /** Digest actually retrieved through verified HTTPS. */
  public String artifactSha256() {
    return artifactSha256;
  }

  /** Nonsecret hash of the explicitly configured retrieval endpoint. */
  public String endpointSha256() {
    return endpointSha256;
  }

  /** Time of successful comparison. */
  public Instant verifiedAt() {
    return verifiedAt;
  }
}
