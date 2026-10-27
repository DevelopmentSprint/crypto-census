package com.developmentsprint.cryptocensus.delivery.profile;

import java.util.Map;

/** A secret location, never its value. Resolution occurs only during execution. */
public record SecretReference(Kind kind, String name, String usernameName) {
  /** Supported execution-time secret providers. */
  public enum Kind {
    NONE,
    ENV,
    MAVEN_SERVER,
    ENV_BASIC,
    MAVEN_SERVER_BASIC
  }

  /** Validates reference syntax without reading it. */
  public SecretReference {
    if (kind == null
        || (kind == Kind.NONE && name != null)
        || (kind != Kind.NONE
            && (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")))) {
      throw new IllegalArgumentException("Invalid authentication reference");
    }
    if ((kind == Kind.ENV || kind == Kind.ENV_BASIC) && !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("Invalid environment reference");
    }
    if (kind == Kind.ENV_BASIC
        ? usernameName == null || !usernameName.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
        : usernameName != null) {
      throw new IllegalArgumentException("Invalid Basic username reference");
    }
  }

  /** Constructs an existing single-secret reference. */
  public SecretReference(Kind kind, String name) {
    this(kind, name, null);
  }

  /** Whether the resolver supplies an encoded username and password pair. */
  public boolean basic() {
    return kind == Kind.ENV_BASIC || kind == Kind.MAVEN_SERVER_BASIC;
  }

  /** Encodes a validated Basic pair without exposing either value in diagnostics. */
  public static String encodeBasic(String username, String password) {
    if (username == null
        || password == null
        || !username.matches("[\\x21-\\x39\\x3b-\\x7e]{1,1024}")
        || !password.matches("[\\x20-\\x7e]{1,4096}")) {
      throw new InvalidBasicCredentialsException();
    }
    return java.util.Base64.getEncoder()
        .encodeToString(
            (username + ":" + password).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  /** Rejects malformed, noncanonical or unsupported pairs from caller-owned resolvers. */
  @SuppressWarnings(
      "PMD.PreserveStackTrace") // Decoder messages must never expose credential bytes.
  static void validateBasic(String value) {
    if (value == null || value.isEmpty() || value.length() > 8192) {
      throw new InvalidBasicCredentialsException();
    }
    final byte[] bytes;
    try {
      bytes = java.util.Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException error) {
      throw new InvalidBasicCredentialsException();
    }
    String pair = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    int separator = pair.indexOf(':');
    if (separator < 0
        || !encodeBasic(pair.substring(0, separator), pair.substring(separator + 1))
            .equals(value)) {
      throw new InvalidBasicCredentialsException();
    }
  }

  /** Fixed, redacted diagnostic for missing or invalid resolved Basic credentials. */
  public static final class InvalidBasicCredentialsException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    private InvalidBasicCredentialsException() {
      super(
          "BASIC_CREDENTIALS_INVALID: Basic credentials are missing or invalid; check the selected"
              + " username and password sources.");
    }
  }

  static SecretReference parse(Object input) {
    Map<String, Object> value = JsonSupport.object(input);
    JsonSupport.fields(value, "type", "name", "usernameName");
    Kind kind = Kind.valueOf(JsonSupport.string(value, "type"));
    if (kind == Kind.NONE && value.containsKey("name")) {
      throw new IllegalArgumentException("Anonymous authentication cannot name a secret");
    }
    return new SecretReference(
        kind,
        kind == Kind.NONE ? null : JsonSupport.string(value, "name"),
        value.containsKey("usernameName") ? JsonSupport.string(value, "usernameName") : null);
  }

  @Override
  public String toString() {
    return "SecretReference[kind=" + kind + "]";
  }

  /** Caller-owned environment or decrypted Maven settings resolver. */
  @FunctionalInterface
  public interface Resolver {
    /**
     * Resolves the selected provider at execution time; never used during local planning. Basic
     * references must return the canonical Base64 pair produced by {@link
     * SecretReference#encodeBasic(String, String)}.
     */
    String resolve(SecretReference reference);
  }
}
