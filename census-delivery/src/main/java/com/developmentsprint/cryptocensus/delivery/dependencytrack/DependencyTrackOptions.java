package com.developmentsprint.cryptocensus.delivery.dependencytrack;

import com.developmentsprint.cryptocensus.delivery.profile.AdapterOptions;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Explicitly provisioned runtime inventory scope; never creates a destination project. */
public record DependencyTrackOptions(
    UUID projectUuid, String ownerId, ReplacementPolicy replacementPolicy)
    implements AdapterOptions {
  /** Requires a dedicated runtime writer identity and explicit replacement policy. */
  public DependencyTrackOptions {
    Objects.requireNonNull(projectUuid, "Dependency-Track project UUID is required");
    if (ownerId == null || !ownerId.matches("[A-Za-z0-9._:-]{1,128}")) {
      throw new IllegalArgumentException("Dependency-Track ownerId is missing or invalid");
    }
    if (replacementPolicy != ReplacementPolicy.OWNED_RUNTIME_SNAPSHOT) {
      throw new IllegalArgumentException("Dependency-Track requires owned runtime snapshot policy");
    }
  }

  @Override
  public AuthenticationScheme authenticationScheme() {
    return AuthenticationScheme.API_KEY;
  }

  @Override
  public Map<String, Object> sanitized() {
    return Map.of(
        "projectUuid", projectUuid.toString(),
        "ownerId", ownerId,
        "replacementPolicy", replacementPolicy.name());
  }

  /** Shared software inventory and combined snapshot replacement are not supported. */
  public enum ReplacementPolicy {
    OWNED_RUNTIME_SNAPSHOT
  }
}
