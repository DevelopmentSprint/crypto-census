package com.developmentsprint.cryptocensus.delivery.profile;

import java.util.Map;

/** A secret location, never its value. Resolution occurs only during execution. */
public record SecretReference(Kind kind, String name) {
  /** Supported execution-time secret providers. */
  public enum Kind {
    NONE,
    ENV,
    MAVEN_SERVER
  }

  /** Validates reference syntax without reading it. */
  public SecretReference {
    if (kind == null
        || (kind == Kind.NONE && name != null)
        || (kind != Kind.NONE
            && (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,127}")))) {
      throw new IllegalArgumentException("Invalid authentication reference");
    }
    if (kind == Kind.ENV && !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new IllegalArgumentException("Invalid environment reference");
    }
  }

  static SecretReference parse(Object input) {
    Map<String, Object> value = JsonSupport.object(input);
    JsonSupport.fields(value, "type", "name");
    Kind kind = Kind.valueOf(JsonSupport.string(value, "type"));
    if (kind == Kind.NONE && value.containsKey("name")) {
      throw new IllegalArgumentException("Anonymous authentication cannot name a secret");
    }
    return new SecretReference(kind, kind == Kind.NONE ? null : JsonSupport.string(value, "name"));
  }

  @Override
  public String toString() {
    return "SecretReference[kind=" + kind + "]";
  }

  /** Caller-owned environment or decrypted Maven settings resolver. */
  @FunctionalInterface
  public interface Resolver {
    /** Resolves the selected provider at execution time; never used during local planning. */
    String resolve(SecretReference reference);
  }
}
