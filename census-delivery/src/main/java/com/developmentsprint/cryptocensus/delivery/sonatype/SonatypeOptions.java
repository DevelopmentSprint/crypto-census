package com.developmentsprint.cryptocensus.delivery.sonatype;

import com.developmentsprint.cryptocensus.delivery.profile.AdapterOptions;
import java.util.Map;

/** Explicit existing application and non-overwrite intent. No automatic application creation. */
public record SonatypeOptions(String applicationId, String publicId, String importPolicy)
    implements AdapterOptions {
  /** Requires native identity and safe bounded mapping values. */
  public SonatypeOptions {
    if (applicationId == null
        || !applicationId.matches("[a-f0-9]{32}")
        || !safeSegment(publicId)
        || !"NEW_VERSION_ONLY".equals(importPolicy)) {
      throw new IllegalArgumentException("SONATYPE_OPTIONS_INVALID");
    }
  }

  static boolean safeSegment(String value) {
    return value != null
        && value.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")
        && !value.contains("..");
  }

  @Override
  public AuthenticationScheme authenticationScheme() {
    return AuthenticationScheme.BASIC;
  }

  @Override
  public Map<String, Object> sanitized() {
    return Map.of(
        "applicationId", applicationId, "publicId", publicId, "importPolicy", importPolicy);
  }
}
