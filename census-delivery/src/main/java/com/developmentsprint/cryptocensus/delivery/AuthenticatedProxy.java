package com.developmentsprint.cryptocensus.delivery;

/** A required, explicitly selected proxy; failure never permits a direct connection. */
public record AuthenticatedProxy(String host, int port, ProxyCredentials credentials) {
  /** Rejects missing proxy endpoints and credentials. */
  public AuthenticatedProxy {
    if (host == null
        || !host.matches("[A-Za-z0-9.:-]{1,253}")
        || port < 1
        || port > 65535
        || credentials == null) {
      throw new IllegalArgumentException("Authenticated proxy configuration is invalid");
    }
  }

  @Override
  public String toString() {
    return "AuthenticatedProxy[REDACTED]";
  }
}
