package com.developmentsprint.cryptocensus.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable shared vocabulary for declarations, annotation evidence and guided enrichment. */
public final class ContextModel {
  public static final int MAX_DECLARATIONS = 16384;
  public static final int MAX_FLOWS = 4096;
  public static final int MAX_BINDINGS = 256;
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
  private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");

  private ContextModel() {}

  /** An empty flowId explicitly denotes an application default, never an inferred flow. */
  public record Subject(String applicationId, String flowId) {
    /** Validates stable identity; empty flow denotes application-wide context. */
    public Subject {
      applicationId = text(applicationId, "applicationId", 256, false);
      flowId = text(flowId, "flowId", 64, true);
      if (!flowId.isEmpty() && !ID.matcher(flowId).matches()) {
        throw new IllegalArgumentException("Invalid flow identifier");
      }
    }

    /** Returns whether this subject names an application default. */
    public boolean applicationDefault() {
      return flowId.isEmpty();
    }

    /** Returns a stable escaped reference independent of display labels. */
    public String ref() {
      return "flow:" + Escapes.encode(applicationId) + ":" + Escapes.encode(flowId);
    }
  }

  /** Schema-owned declaration fields; unknown fields require a new schema version. */
  public enum Field {
    CLASSIFICATION,
    CONFIDENTIALITY_YEARS,
    AUTHENTICITY_YEARS,
    OWNER,
    EXPOSURE,
    IMPACT,
    PURPOSE;

    /**
     * Validates one scalar. Explicit unknown/UNSPECIFIED values are non-substantive declarations.
     */
    public String validate(String input) {
      String value = text(input, "declaration value", 256, false);
      switch (this) {
        case CLASSIFICATION -> {
          if (!ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid classification identifier");
          }
        }
        case CONFIDENTIALITY_YEARS, AUTHENTICITY_YEARS -> {
          int years;
          try {
            years = Integer.parseInt(value);
          } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Expected integral protection years", e);
          }
          if (years < 1 || years > 1000 || !Integer.toString(years).equals(value)) {
            throw new IllegalArgumentException(
                "Protection years must be canonical integers 1–1000");
          }
        }
        case EXPOSURE ->
            choice(
                value,
                Set.of(
                    "public-network", "external-network", "internal-network", "local", "unknown"));
        case IMPACT -> choice(value, Set.of("low", "medium", "high", "unknown"));
        case PURPOSE ->
            choice(
                value,
                Set.of(
                    "UNSPECIFIED",
                    "TRANSPORT",
                    "STORAGE",
                    "KEY_PROTECTION",
                    "SIGNING",
                    "VERIFICATION"));
        case OWNER -> {}
        default -> throw new IllegalArgumentException("Unsupported declaration field");
      }
      return value;
    }

    /** Whether a present value conveys a requirement rather than an explicit unknown answer. */
    public boolean substantive(String value) {
      validate(value);
      return !(this == PURPOSE && "UNSPECIFIED".equals(value))
          && !((this == EXPOSURE || this == IMPACT) && "unknown".equals(value));
    }
  }

  /** Origin of a declared business requirement. */
  public enum SourceKind {
    ANNOTATION,
    USER,
    CATALOG,
    APPLICATION_DEFAULT
  }

  /** Review identity and time are assertions, not an authenticated audit trail. */
  public record Source(
      SourceKind kind, String id, String revision, String reviewer, String reviewedAt) {
    /** Validates source revision and optional asserted review metadata. */
    public Source {
      Objects.requireNonNull(kind, "kind");
      id = text(id, "source id", 512, false);
      revision = hash(revision, "source revision");
      reviewer = text(reviewer, "reviewer", 256, true);
      reviewedAt = text(reviewedAt, "reviewedAt", 64, true);
      if (!reviewedAt.isEmpty()) {
        try {
          Instant.parse(reviewedAt);
        } catch (java.time.DateTimeException invalid) {
          throw new IllegalArgumentException("Invalid source review timestamp", invalid);
        }
      }
    }
  }

  /** Validates field-specific scalar semantics and declaration applicability. */
  public record Declaration(Subject subject, Field field, String value, Source source) {
    /** Validates field-specific scalar semantics and declaration applicability. */
    public Declaration {
      Objects.requireNonNull(subject, "subject");
      Objects.requireNonNull(field, "field");
      value = field.validate(value);
      Objects.requireNonNull(source, "source");
      if (source.kind() == SourceKind.APPLICATION_DEFAULT && !subject.applicationDefault()) {
        throw new IllegalArgumentException("Application default has a flow subject");
      }
    }
  }

  /** Classification of the declaring artifact in the exercised workload. */
  public enum Origin {
    MAIN,
    TEST,
    LIBRARY,
    UNKNOWN
  }

  /**
   * Source artifact is an identity, not an exported absolute path or a guess from package names.
   */
  public record Binding(
      String className, String method, String descriptor, String sourceArtifact, Origin origin) {
    /** Validates bounded method and artifact metadata. */
    public Binding {
      className = text(className, "className", 512, false);
      method = text(method, "method", 256, false);
      descriptor = text(descriptor, "descriptor", 1024, true);
      sourceArtifact = text(sourceArtifact, "sourceArtifact", 256, true);
      Objects.requireNonNull(origin, "origin");
    }
  }

  /**
   * Discovered declaration metadata does not establish that a flow or crypto operation executed.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "ContextModel.bounded snapshots lists with List.copyOf; all elements are immutable"
              + " values")
  public record FlowDefinition(
      Subject subject, String label, List<Declaration> declarations, List<Binding> bindings) {
    /** Snapshots declarations and bindings and checks subject consistency. */
    public FlowDefinition {
      Objects.requireNonNull(subject, "subject");
      if (subject.applicationDefault()) {
        throw new IllegalArgumentException("Flow definition requires a flow identifier");
      }
      label = text(label, "flow label", 256, false);
      declarations = bounded(declarations, MAX_DECLARATIONS, "declarations");
      bindings = bounded(bindings, MAX_BINDINGS, "bindings");
      for (Declaration declaration : declarations) {
        if (!subject.equals(declaration.subject())) {
          throw new IllegalArgumentException("Declaration belongs to another flow");
        }
      }
    }
  }

  /** Requires a lowercase SHA-256 hex digest. */
  public static String hash(String value, String name) {
    if (!HASH.matcher(Objects.requireNonNull(value, name)).matches()) {
      throw new IllegalArgumentException("Invalid SHA-256 " + name);
    }
    return value;
  }

  /** Validates a bounded string without silently truncating identity or metadata. */
  public static String text(String value, String name, int maximum, boolean emptyAllowed) {
    Objects.requireNonNull(value, name);
    if (value.length() > maximum
        || (!emptyAllowed && value.isBlank())
        || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Invalid " + name);
    }
    return value;
  }

  /** Snapshots a bounded collection and rejects null elements. */
  public static <T> List<T> bounded(List<T> values, int maximum, String name) {
    if (values.size() > maximum) {
      throw new IllegalArgumentException("Too many " + name);
    }
    List<T> snapshot = List.copyOf(values);
    if (snapshot.size() > maximum) {
      throw new IllegalArgumentException("Too many " + name);
    }
    return snapshot;
  }

  private static void choice(String value, Set<String> choices) {
    if (!choices.contains(value)) {
      throw new IllegalArgumentException("Unknown declaration value " + value);
    }
  }
}
