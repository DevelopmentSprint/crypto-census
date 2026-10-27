package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.ContextModel;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Resolves one subject field while retaining every declaration and its provenance. */
public final class Reconciliation {
  private Reconciliation() {}

  /** Distinguishes evidence-backed agreement, broad assumptions, conflicts and absent answers. */
  public enum Status {
    AGREED,
    REVIEWED,
    APPLICATION_DEFAULT,
    UNKNOWN,
    CONFLICT,
    STALE_RESOLUTION
  }

  /** Effective value is empty when no single substantive value can be established. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Constructor snapshots immutable declarations with ContextModel.bounded")
  public record Result(
      ContextModel.Subject subject,
      ContextModel.Field field,
      Status status,
      String value,
      List<ContextModel.Declaration> sources) {
    /** Retains original sources without allowing mutation through result accessors. */
    public Result {
      Objects.requireNonNull(subject, "subject");
      Objects.requireNonNull(field, "field");
      Objects.requireNonNull(status, "status");
      value = ContextModel.text(value, "effective value", 256, true);
      sources = ContextModel.bounded(sources, ContextModel.MAX_DECLARATIONS, "field sources");
    }

    /** Only flow-specific agreement or reviewed resolution supplies specific business context. */
    public boolean specific() {
      return !subject.applicationDefault()
          && (status == Status.AGREED || status == Status.REVIEWED);
    }

    /** Conflicting or stale decisions require review even if an independent risk remains known. */
    public boolean conflict() {
      return status == Status.CONFLICT || status == Status.STALE_RESOLUTION;
    }
  }

  private record SourceValueKey(
      ContextModel.Subject subject, ContextModel.SourceKind kind, String id, String revision) {}

  /** Resolves a field against preserved observed declarations and durable context. */
  public static Result resolve(
      ContextModel.Subject subject,
      ContextModel.Field field,
      List<ContextModel.Declaration> observed,
      ContextDocument context) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(field, "field");
    List<ContextModel.Declaration> all =
        new ArrayList<>(
            ContextModel.bounded(observed, ContextModel.MAX_DECLARATIONS, "observed declarations"));
    all.addAll(context.declarations());
    if (all.size() > ContextModel.MAX_DECLARATIONS) {
      throw new IllegalArgumentException("Combined declaration budget exceeded");
    }
    List<ContextModel.Declaration> applicable =
        all.stream()
            .filter(
                value ->
                    value.field() == field
                        && value.subject().applicationId().equals(subject.applicationId())
                        && (value.subject().equals(subject)
                            || value.subject().applicationDefault()))
            .distinct()
            .sorted(
                Comparator.comparing(
                    value ->
                        com.developmentsprint.cryptocensus.core.CanonicalJson.revision(
                            com.developmentsprint.cryptocensus.core.ContextJson.declaration(
                                value))))
            .toList();
    Map<SourceValueKey, String> valuesByRevision = new java.util.HashMap<>();
    for (ContextModel.Declaration declaration : applicable) {
      SourceValueKey key =
          new SourceValueKey(
              declaration.subject(),
              declaration.source().kind(),
              declaration.source().id(),
              declaration.source().revision());
      String previous = valuesByRevision.putIfAbsent(key, declaration.value());
      if (previous != null && !previous.equals(declaration.value())) {
        return new Result(subject, field, Status.CONFLICT, "", applicable);
      }
    }
    List<ContextModel.Declaration> specific =
        applicable.stream()
            .filter(value -> value.subject().equals(subject) && field.substantive(value.value()))
            .toList();
    boolean assumed = specific.isEmpty() && !subject.applicationDefault();
    List<ContextModel.Declaration> candidates =
        assumed
            ? applicable.stream()
                .filter(
                    value ->
                        value.subject().applicationDefault() && field.substantive(value.value()))
                .toList()
            : specific;
    ContextDocument.Resolution resolution =
        context.resolutions().stream()
            .filter(value -> value.subject().equals(subject) && value.field() == field)
            .findFirst()
            .orElse(null);
    if (resolution != null) {
      Set<ContextDocument.SourceRef> current = new HashSet<>();
      applicable.forEach(value -> current.add(ContextDocument.SourceRef.of(value)));
      if (!current.equals(new HashSet<>(resolution.sources()))) {
        return new Result(subject, field, Status.STALE_RESOLUTION, "", applicable);
      }
      if (field.substantive(resolution.value())) {
        return new Result(
            subject,
            field,
            assumed ? Status.APPLICATION_DEFAULT : Status.REVIEWED,
            resolution.value(),
            applicable);
      }
      return new Result(subject, field, Status.UNKNOWN, "", applicable);
    }
    Set<String> values = new HashSet<>();
    candidates.forEach(value -> values.add(value.value()));
    if (values.size() > 1) {
      return new Result(subject, field, Status.CONFLICT, "", applicable);
    }
    if (values.isEmpty()) {
      return new Result(subject, field, Status.UNKNOWN, "", applicable);
    }
    return new Result(
        subject,
        field,
        assumed || subject.applicationDefault() ? Status.APPLICATION_DEFAULT : Status.AGREED,
        values.iterator().next(),
        applicable);
  }
}
