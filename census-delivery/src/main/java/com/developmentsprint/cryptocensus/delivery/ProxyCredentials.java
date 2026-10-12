package com.developmentsprint.cryptocensus.delivery;

/** Execution-scoped CONNECT credentials; never serialized into plans or receipts. */
public final class ProxyCredentials {
  private final String username;
  private final String password;

  private ProxyCredentials(String username, String password) {
    this.username = username;
    this.password = password;
  }

  /** Creates explicit Basic proxy credentials; callers resolve secret references first. */
  public static ProxyCredentials basic(String username, String password) {
    if (username == null
        || !username.matches("[\\x21-\\x7e]{1,256}")
        || username.contains(":")
        || password == null
        || !password.matches("[\\x21-\\x7e]{1,8192}")) {
      throw new IllegalArgumentException("Proxy credentials are absent or invalid");
    }
    return new ProxyCredentials(username, password);
  }

  String username() {
    return username;
  }

  char[] password() {
    return password.toCharArray();
  }

  @Override
  public String toString() {
    return "ProxyCredentials[REDACTED]";
  }
}
