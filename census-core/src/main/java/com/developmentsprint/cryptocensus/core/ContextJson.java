package com.developmentsprint.cryptocensus.core;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** The single wire mapping for shared declaration types; consumers must not redefine it. */
public final class ContextJson {
  private ContextJson() {}

  /** Builds the shared wire representation of a subject. */
  public static Map<String, Object> subject(ContextModel.Subject value) {
    return Map.of("applicationId", value.applicationId(), "flowId", value.flowId());
  }

  /** Decodes a strict subject object, rejecting missing or unknown fields. */
  public static ContextModel.Subject subject(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "applicationId", "flowId");
    return new ContextModel.Subject(
        CanonicalJson.text(object, "applicationId"), CanonicalJson.text(object, "flowId"));
  }

  /** Builds the shared wire representation of a source. */
  public static Map<String, Object> source(ContextModel.Source value) {
    return Map.of(
        "kind",
        value.kind().name(),
        "id",
        value.id(),
        "revision",
        value.revision(),
        "reviewer",
        value.reviewer(),
        "reviewedAt",
        value.reviewedAt());
  }

  /** Decodes a strict source object, rejecting missing or unknown fields. */
  public static ContextModel.Source source(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "kind", "id", "revision", "reviewer", "reviewedAt");
    return new ContextModel.Source(
        ContextModel.SourceKind.valueOf(CanonicalJson.text(object, "kind")),
        CanonicalJson.text(object, "id"),
        CanonicalJson.text(object, "revision"),
        CanonicalJson.text(object, "reviewer"),
        CanonicalJson.text(object, "reviewedAt"));
  }

  /** Builds the shared wire representation of a declaration. */
  public static Map<String, Object> declaration(ContextModel.Declaration value) {
    return Map.of(
        "subject",
        subject(value.subject()),
        "field",
        value.field().name(),
        "value",
        value.value(),
        "source",
        source(value.source()));
  }

  /** Decodes a strict declaration object, rejecting missing or unknown fields. */
  public static ContextModel.Declaration declaration(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "subject", "field", "value", "source");
    return new ContextModel.Declaration(
        subject(object.get("subject")),
        ContextModel.Field.valueOf(CanonicalJson.text(object, "field")),
        CanonicalJson.text(object, "value"),
        source(object.get("source")));
  }

  /** Builds the shared wire representation of a binding. */
  public static Map<String, Object> binding(ContextModel.Binding value) {
    return Map.of(
        "className",
        value.className(),
        "method",
        value.method(),
        "descriptor",
        value.descriptor(),
        "sourceArtifact",
        value.sourceArtifact(),
        "origin",
        value.origin().name());
  }

  /** Decodes a strict binding object, rejecting missing or unknown fields. */
  public static ContextModel.Binding binding(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(
        object, "className", "method", "descriptor", "sourceArtifact", "origin");
    return new ContextModel.Binding(
        CanonicalJson.text(object, "className"),
        CanonicalJson.text(object, "method"),
        CanonicalJson.text(object, "descriptor"),
        CanonicalJson.text(object, "sourceArtifact"),
        ContextModel.Origin.valueOf(CanonicalJson.text(object, "origin")));
  }

  /** Builds the shared wire representation of a flow. */
  public static Map<String, Object> flow(ContextModel.FlowDefinition value) {
    return Map.of(
        "subject",
        subject(value.subject()),
        "label",
        value.label(),
        "declarations",
        ordered(value.declarations().stream().map(ContextJson::declaration).toList()),
        "bindings",
        ordered(value.bindings().stream().map(ContextJson::binding).toList()));
  }

  /** Decodes a strict flow object, rejecting missing or unknown fields. */
  public static ContextModel.FlowDefinition flow(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "subject", "label", "declarations", "bindings");
    return new ContextModel.FlowDefinition(
        subject(object.get("subject")),
        CanonicalJson.text(object, "label"),
        CanonicalJson.array(object, "declarations").stream().map(ContextJson::declaration).toList(),
        CanonicalJson.array(object, "bindings").stream().map(ContextJson::binding).toList());
  }

  /** Stable total ordering for unordered schema arrays; callers retain order for scope paths. */
  public static List<Map<String, Object>> ordered(List<Map<String, Object>> values) {
    return values.stream().sorted(Comparator.comparing(ContextJson::sortKey)).toList();
  }

  private static String sortKey(Map<String, Object> value) {
    return new String(CanonicalJson.encode(value), StandardCharsets.UTF_8);
  }
}
