package com.developmentsprint.cryptocensus.delivery.profile;

import java.util.Map;

/** Explicitly registered, version-one protocol adapter. No dynamic classpath discovery. */
public interface DestinationAdapter {
  /** Stable adapter identifier. */
  String id();

  /** Strictly decode known options into the adapter's typed configuration. */
  AdapterOptions parseOptions(Map<String, Object> raw);

  /** Evidence-backed capability revision. */
  CapabilityDeclaration capabilities();

  /** Validate local options and input suitability without networking or credential resolution. */
  void validate(AdapterContext context);

  /** Whether this profile requires independent verified original-evidence retrieval. */
  default boolean requiresOriginalRetention() {
    return false;
  }

  /** Whether successful delivery also requires an exact-byte download from the destination. */
  default boolean requiresPublicationReadback() {
    return false;
  }

  /** Whether anonymous execution is prohibited by this destination contract. */
  default boolean requiresAuthentication() {
    return false;
  }

  /** Open a pure state machine over verified publication bytes. */
  AdapterSession open(AdapterContext context, byte[] publicationBytes);
}
