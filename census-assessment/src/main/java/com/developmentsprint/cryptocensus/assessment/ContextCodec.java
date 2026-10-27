package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.ContextJson;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.DocumentFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Strict canonical durable-context codec, shared by report generation and managed edits. */
public final class ContextCodec {
  private ContextCodec() {}

  /** Encodes unordered collections deterministically; no runtime identifiers enter the revision. */
  public static byte[] encode(ContextDocument value) {
    return CanonicalJson.encode(
        Map.of(
            "schemaVersion",
            value.schemaVersion(),
            "flows",
            ContextJson.ordered(value.flows().stream().map(ContextCodec::flow).toList()),
            "declarations",
            ContextJson.ordered(
                value.declarations().stream().map(ContextJson::declaration).toList()),
            "resolutions",
            ContextJson.ordered(
                value.resolutions().stream().map(ContextCodec::resolution).toList()),
            "readiness",
            ContextJson.ordered(value.readiness().stream().map(ContextCodec::readiness).toList())));
  }

  /** Content hash of canonical context, not a mutable revision counter. */
  public static String revision(ContextDocument value) {
    return CanonicalJson.hash(encode(value));
  }

  /** Parses only the supported complete context schema. */
  public static ContextDocument parse(byte[] bytes) {
    Map<String, Object> value = CanonicalJson.object(CanonicalJson.decode(bytes));
    CanonicalJson.requireKeys(
        value, "schemaVersion", "flows", "declarations", "resolutions", "readiness");
    if (CanonicalJson.integer(value, "schemaVersion") != ContextDocument.VERSION) {
      throw new IllegalArgumentException("Unsupported context schema");
    }
    return new ContextDocument(
        ContextDocument.VERSION,
        CanonicalJson.array(value, "flows").stream().map(ContextCodec::flow).toList(),
        CanonicalJson.array(value, "declarations").stream().map(ContextJson::declaration).toList(),
        CanonicalJson.array(value, "resolutions").stream().map(ContextCodec::resolution).toList(),
        CanonicalJson.array(value, "readiness").stream().map(ContextCodec::readiness).toList());
  }

  /** Reads bounded local context without following a symbolic link. */
  public static ContextDocument read(Path path) throws IOException {
    return parse(DocumentFiles.read(path));
  }

  /** Produces a saved-flow wire value for context and change-set export. */
  public static Map<String, Object> flow(ContextDocument.Flow value) {
    return Map.of(
        "subject",
        ContextJson.subject(value.subject()),
        "label",
        value.label(),
        "state",
        value.state().name(),
        "bindings",
        ContextJson.ordered(value.bindings().stream().map(ContextJson::binding).toList()));
  }

  /** Validates a saved-flow wire value. */
  public static ContextDocument.Flow flow(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "subject", "label", "state", "bindings");
    return new ContextDocument.Flow(
        ContextJson.subject(object.get("subject")),
        CanonicalJson.text(object, "label"),
        ContextDocument.State.valueOf(CanonicalJson.text(object, "state")),
        CanonicalJson.array(object, "bindings").stream().map(ContextJson::binding).toList());
  }

  /** Produces a source reference wire value. */
  public static Map<String, Object> sourceRef(ContextDocument.SourceRef value) {
    return Map.of(
        "kind",
        value.kind().name(),
        "id",
        value.id(),
        "revision",
        value.revision(),
        "declarationHash",
        value.declarationHash());
  }

  /** Validates a source reference wire value. */
  public static ContextDocument.SourceRef sourceRef(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "kind", "id", "revision", "declarationHash");
    return new ContextDocument.SourceRef(
        ContextModel.SourceKind.valueOf(CanonicalJson.text(object, "kind")),
        CanonicalJson.text(object, "id"),
        CanonicalJson.text(object, "revision"),
        CanonicalJson.text(object, "declarationHash"));
  }

  /** Produces a reviewed resolution wire value. */
  public static Map<String, Object> resolution(ContextDocument.Resolution value) {
    return Map.of(
        "subject",
        ContextJson.subject(value.subject()),
        "field",
        value.field().name(),
        "value",
        value.value(),
        "sources",
        ContextJson.ordered(value.sources().stream().map(ContextCodec::sourceRef).toList()),
        "reviewer",
        value.reviewer(),
        "reviewedAt",
        value.reviewedAt());
  }

  /** Validates a reviewed resolution wire value. */
  public static ContextDocument.Resolution resolution(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(
        object, "subject", "field", "value", "sources", "reviewer", "reviewedAt");
    return new ContextDocument.Resolution(
        ContextJson.subject(object.get("subject")),
        ContextModel.Field.valueOf(CanonicalJson.text(object, "field")),
        CanonicalJson.text(object, "value"),
        CanonicalJson.array(object, "sources").stream().map(ContextCodec::sourceRef).toList(),
        CanonicalJson.text(object, "reviewer"),
        CanonicalJson.text(object, "reviewedAt"));
  }

  /** Produces an explicitly asserted readiness wire value. */
  public static Map<String, Object> readiness(ContextDocument.Readiness value) {
    return Map.of(
        "subject",
        ContextJson.subject(value.subject()),
        "state",
        value.state().name(),
        "note",
        value.note(),
        "source",
        ContextJson.source(value.source()),
        "validationRefs",
        value.validationRefs().stream().sorted().toList());
  }

  /** Validates an explicitly asserted readiness wire value. */
  public static ContextDocument.Readiness readiness(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "subject", "state", "note", "source", "validationRefs");
    List<String> refs =
        CanonicalJson.array(object, "validationRefs").stream()
            .map(
                item -> {
                  if (!(item instanceof String text)) {
                    throw new IllegalArgumentException("Expected validation reference string");
                  }
                  return text;
                })
            .toList();
    return new ContextDocument.Readiness(
        ContextJson.subject(object.get("subject")),
        ContextDocument.ReadinessState.valueOf(CanonicalJson.text(object, "state")),
        CanonicalJson.text(object, "note"),
        ContextJson.source(object.get("source")),
        refs);
  }
}
