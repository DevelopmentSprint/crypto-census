package com.developmentsprint.cryptocensus.delivery.anchore;

import com.developmentsprint.cryptocensus.delivery.profile.AdapterOptions;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Explicit application and dedicated version mapping for experimental Anchore delivery. */
public record AnchoreOptions(
    UUID applicationUuid,
    UUID versionUuid,
    String assetName,
    String serverVersion,
    MutationPolicy mutationPolicy)
    implements AdapterOptions {
  /** Rejects ambiguous mappings, unsafe names and implicit mutation consent. */
  public AnchoreOptions {
    Objects.requireNonNull(applicationUuid, "Anchore application UUID is required");
    Objects.requireNonNull(versionUuid, "Anchore version UUID is required");
    if (assetName == null || !assetName.matches("[A-Za-z0-9_-]{1,128}")) {
      throw new IllegalArgumentException("ANCHORE_ASSET_NAME_INVALID");
    }
    if (serverVersion == null || !serverVersion.matches("6\\.2\\.[0-9]{1,5}")) {
      throw new IllegalArgumentException("ANCHORE_SERVER_VERSION_UNSUPPORTED");
    }
    if (mutationPolicy != MutationPolicy.DEDICATED_VERSION_SINGLE_WRITER) {
      throw new IllegalArgumentException("ANCHORE_MUTATION_POLICY_REQUIRED");
    }
  }

  @Override
  public AuthenticationScheme authenticationScheme() {
    return AuthenticationScheme.BASIC;
  }

  @Override
  public Map<String, Object> sanitized() {
    return Map.of(
        "applicationUuid", applicationUuid.toString(),
        "versionUuid", versionUuid.toString(),
        "assetName", assetName,
        "serverVersion", serverVersion,
        "mutationPolicy", mutationPolicy.name());
  }

  /** Operator attestation; the remote API does not provide an atomic create-only precondition. */
  public enum MutationPolicy {
    DEDICATED_VERSION_SINGLE_WRITER
  }
}
