package com.developmentsprint.cryptocensus.delivery.artifactory;

import com.developmentsprint.cryptocensus.delivery.profile.AdapterOptions;
import java.util.Map;

/** Explicit repository scope and operator-confirmed deployment permissions. */
public record ArtifactoryOptions(String repository, String pathPrefix, String deploymentPermission)
    implements AdapterOptions {
  /** Rejects path tricks and requires a principal that cannot overwrite an existing object. */
  public ArtifactoryOptions {
    if (repository == null
        || !repository.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        || "api".equals(repository)
        || pathPrefix == null
        || pathPrefix.length() > 256
        || !pathPrefix.matches("[A-Za-z0-9_/-]+")
        || pathPrefix.startsWith("/")
        || pathPrefix.endsWith("/")
        || pathPrefix.contains("//")
        || !"READ_DEPLOY_NO_OVERWRITE".equals(deploymentPermission)) {
      throw new IllegalArgumentException("ARTIFACTORY_CONFIGURATION_INVALID");
    }
  }

  @Override
  public AuthenticationScheme authenticationScheme() {
    return AuthenticationScheme.BEARER;
  }

  @Override
  public Map<String, Object> sanitized() {
    return Map.of(
        "repository",
        repository,
        "pathPrefix",
        pathPrefix,
        "deploymentPermission",
        deploymentPermission);
  }
}
