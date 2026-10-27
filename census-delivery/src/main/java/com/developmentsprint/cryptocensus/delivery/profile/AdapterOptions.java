package com.developmentsprint.cryptocensus.delivery.profile;

/** Validated adapter-specific configuration; never a bag of unchecked request values. */
public interface AdapterOptions {
  /** Authentication header controlled by the registered adapter. */
  AuthenticationScheme authenticationScheme();

  /** Nonsecret native project and operation options shown during preflight. */
  default java.util.Map<String, Object> sanitized() {
    return java.util.Map.of();
  }

  /** Authentication schemes supported by the central transport. */
  enum AuthenticationScheme {
    BEARER,
    API_KEY,
    BASIC
  }
}
