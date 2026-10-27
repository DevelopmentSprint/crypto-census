package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.ContextModel;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Durable declarations and explicit review decisions, independent of a collector run. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "All lists are immutable List.copyOf snapshots through ContextModel.bounded; elements are"
            + " immutable records")
public record ContextDocument(
    int schemaVersion,
    List<Flow> flows,
    List<ContextModel.Declaration> declarations,
    List<Resolution> resolutions,
    List<Readiness> readiness) {
  public static final int VERSION = 1;

  /** Snapshots bounded values and rejects ambiguous duplicate identities. */
  public ContextDocument {
    if (schemaVersion != VERSION) {
      throw new IllegalArgumentException("Unsupported context schema " + schemaVersion);
    }
    flows = ContextModel.bounded(flows, ContextModel.MAX_FLOWS, "context flows");
    declarations =
        ContextModel.bounded(declarations, ContextModel.MAX_DECLARATIONS, "context declarations");
    resolutions = ContextModel.bounded(resolutions, ContextModel.MAX_DECLARATIONS, "resolutions");
    readiness = ContextModel.bounded(readiness, ContextModel.MAX_FLOWS, "readiness assertions");
    unique(flows.stream().map(flow -> flow.subject().ref()).toList(), "flow");
    unique(declarations.stream().map(ContextDocument::declarationId).toList(), "declaration");
    unique(
        resolutions.stream().map(value -> fieldId(value.subject(), value.field())).toList(),
        "resolution");
    unique(readiness.stream().map(value -> value.subject().ref()).toList(), "readiness");
  }

  /** Empty context is valid and does not imply public data or migration readiness. */
  public static ContextDocument empty() {
    return new ContextDocument(VERSION, List.of(), List.of(), List.of(), List.of());
  }

  /** Stable edit identity excludes the declaration value and source revision. */
  public static String declarationId(ContextModel.Declaration value) {
    return CanonicalJson.revision(
        List.of(
            value.subject().ref(),
            value.field().name(),
            value.source().kind().name(),
            value.source().id()));
  }

  /** Stable identity for a subject's field. */
  public static String fieldId(ContextModel.Subject subject, ContextModel.Field field) {
    return CanonicalJson.revision(List.of(subject.ref(), field.name()));
  }

  private static void unique(List<String> ids, String name) {
    if (new HashSet<>(ids).size() != ids.size()) {
      throw new IllegalArgumentException("Duplicate context " + name);
    }
  }

  /** Retirement retains identity and context; it is not deletion or lack of observations. */
  public enum State {
    ACTIVE,
    RETIRED
  }

  /** Saved business identity; label changes do not change its subject. */
  public record Flow(
      ContextModel.Subject subject,
      String label,
      State state,
      List<ContextModel.Binding> bindings) {
    /** Validates a flow identity and snapshots exact method/artifact bindings. */
    public Flow {
      Objects.requireNonNull(subject, "subject");
      if (subject.applicationDefault()) {
        throw new IllegalArgumentException("Saved flow requires flow id");
      }
      label = ContextModel.text(label, "flow label", 256, false);
      Objects.requireNonNull(state, "state");
      bindings = ContextModel.bounded(bindings, ContextModel.MAX_BINDINGS, "flow bindings");
    }
  }

  /** Exact source revision acknowledged by a reviewed field resolution. */
  public record SourceRef(
      ContextModel.SourceKind kind, String id, String revision, String declarationHash) {
    /** Validates revision identity without authenticating the reviewer. */
    public SourceRef {
      Objects.requireNonNull(kind, "kind");
      id = ContextModel.text(id, "source id", 512, false);
      revision = ContextModel.hash(revision, "source revision");
      declarationHash = ContextModel.hash(declarationHash, "declaration hash");
    }

    /** Extracts the exact identity of a preserved declaration source. */
    public static SourceRef of(ContextModel.Declaration value) {
      return new SourceRef(
          value.source().kind(),
          value.source().id(),
          value.source().revision(),
          CanonicalJson.revision(
              com.developmentsprint.cryptocensus.core.ContextJson.declaration(value)));
    }
  }

  /** A reviewed selection is valid only for the complete current set of applicable sources. */
  public record Resolution(
      ContextModel.Subject subject,
      ContextModel.Field field,
      String value,
      List<SourceRef> sources,
      String reviewer,
      String reviewedAt) {
    /** Validates the selected value and exact source set; empty resolutions are invalid. */
    public Resolution {
      Objects.requireNonNull(subject, "subject");
      Objects.requireNonNull(field, "field");
      value = field.validate(value);
      sources = ContextModel.bounded(sources, 256, "resolution sources");
      if (sources.isEmpty() || new HashSet<>(sources).size() != sources.size()) {
        throw new IllegalArgumentException("Resolution requires unique source revisions");
      }
      reviewer = ContextModel.text(reviewer, "reviewer", 256, false);
      reviewedAt = ContextModel.text(reviewedAt, "review time", 64, false);
      try {
        Instant.parse(reviewedAt);
      } catch (java.time.DateTimeException invalid) {
        throw new IllegalArgumentException("Invalid resolution review timestamp", invalid);
      }
    }
  }

  /** Readiness remains independent of observed algorithm support and business priority. */
  public enum ReadinessState {
    UNASSESSED,
    BLOCKED,
    CANDIDATE,
    VALIDATED_IN_TESTED_SCOPE
  }

  /** User assertion; evidence references need independent validation before a validated result. */
  public record Readiness(
      ContextModel.Subject subject,
      ReadinessState state,
      String note,
      ContextModel.Source source,
      List<String> validationRefs) {
    /** Retains asserted provenance without treating references as proof of interoperability. */
    public Readiness {
      Objects.requireNonNull(subject, "subject");
      Objects.requireNonNull(state, "state");
      note = ContextModel.text(note, "readiness note", 2048, true);
      Objects.requireNonNull(source, "source");
      validationRefs = ContextModel.bounded(validationRefs, 64, "validation references");
      for (String reference : validationRefs) {
        ContextModel.text(reference, "validation reference", 512, false);
      }
    }
  }
}
