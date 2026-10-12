package com.developmentsprint.cryptocensus.delivery;

/** Execution-time credentials. Never include their value in diagnostics or configuration files. */
public final class DeliveryCredentials {
  private final String authorization;

  private DeliveryCredentials(String authorization) {
    this.authorization = authorization;
  }

  /** Explicitly selects delivery without authentication. */
  public static DeliveryCredentials none() {
    return new DeliveryCredentials(null);
  }

  /** Creates bearer authentication from a secret resolved by the caller. */
  public static DeliveryCredentials bearer(String token) {
    if (token == null || token.length() > 8192 || !token.matches("[A-Za-z0-9._~+/-]{1,8192}=*")) {
      throw new IllegalArgumentException("Bearer credential is missing or invalid");
    }
    return new DeliveryCredentials("Bearer " + token);
  }

  String authorization() {
    return authorization;
  }

  @Override
  public String toString() {
    return "DeliveryCredentials[REDACTED]";
  }
}
