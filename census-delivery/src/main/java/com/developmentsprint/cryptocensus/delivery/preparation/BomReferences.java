package com.developmentsprint.cryptocensus.delivery.preparation;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Visits reference-bearing fields using the pinned schema, not string-value guessing. */
final class BomReferences {
  private final Map<String, String> declarations = new LinkedHashMap<>();
  private final Map<String, String> uses = new LinkedHashMap<>();
  private final Set<String> visited = new HashSet<>();
  private int components;
  private int steps;

  private BomReferences() {}

  static BomReferences inspect(ObjectNode document) throws PreparationException {
    BomReferences result = new BomReferences();
    result.visit(document, BomSchema.DEFINITION, "", "#", 0);
    if (result.components > BomSchema.MAX_COMPONENTS) {
      throw new PreparationException("INPUT_LIMIT_EXCEEDED");
    }
    Set<String> refs = new HashSet<>();
    for (String ref : result.declarations.values()) {
      if (!refs.add(ref) || ref.startsWith("urn:cdx:")) {
        throw new PreparationException("INPUT_REFERENCE_DUPLICATE");
      }
    }
    String ownLink = ownLink(document);
    for (String ref : result.uses.values()) {
      if (document.has("serialNumber") && ref.startsWith(ownLink)) {
        if (!refs.contains(ref.substring(ownLink.length()))) {
          throw new PreparationException("INPUT_REFERENCE_DANGLING");
        }
      } else if (!ref.startsWith("urn:cdx:") && !refs.contains(ref)) {
        throw new PreparationException("INPUT_REFERENCE_DANGLING");
      }
    }
    return result;
  }

  Map<String, String> rewrite(ObjectNode document, String prefix, String derivedLink)
      throws PreparationException {
    String ownLink = ownLink(document);
    Map<String, String> mappings = new LinkedHashMap<>();
    for (String ref : declarations.values()) {
      mappings.put(
          ref, prefix + PreparationIo.hash(ref.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    for (Map.Entry<String, String> entry : declarations.entrySet()) {
      set(document, entry.getKey(), mappings.get(entry.getValue()));
    }
    for (Map.Entry<String, String> entry : uses.entrySet()) {
      String replacement = mappings.get(entry.getValue());
      if (replacement == null
          && document.has("serialNumber")
          && entry.getValue().startsWith(ownLink)) {
        replacement = derivedLink + mappings.get(entry.getValue().substring(ownLink.length()));
        mappings.put(entry.getValue(), replacement);
      }
      if (replacement != null) {
        set(document, entry.getKey(), replacement);
      }
    }
    return mappings;
  }

  static java.math.BigInteger version(ObjectNode document) throws PreparationException {
    if (!document.has("version")) {
      return java.math.BigInteger.ONE;
    }
    java.math.BigDecimal value = document.get("version").decimalValue();
    if ((long) value.precision() - value.scale() > 128) {
      throw new PreparationException("INPUT_LIMIT_EXCEEDED");
    }
    return value.toBigIntegerExact();
  }

  private static String ownLink(ObjectNode document) throws PreparationException {
    return document.path("serialNumber").asText().replace("urn:uuid:", "urn:cdx:")
        + "/"
        + version(document)
        + "#";
  }

  private static void set(ObjectNode root, String pointer, String value) {
    JsonPointer path = JsonPointer.compile(pointer);
    JsonNode parent = root.at(path.head());
    if (parent instanceof ObjectNode object) {
      object.put(path.last().getMatchingProperty(), value);
    } else {
      ((ArrayNode) parent)
          .set(path.last().getMatchingIndex(), BomSchema.JSON.getNodeFactory().textNode(value));
    }
  }

  private void visit(JsonNode data, JsonNode schema, String path, String schemaPath, int depth)
      throws PreparationException {
    if (data.isMissingNode() || schema.isMissingNode() || !schema.isObject()) {
      return;
    }
    if (++steps > 2_000_000 || depth > 256) {
      throw new PreparationException("INPUT_LIMIT_EXCEEDED");
    }
    if (!visited.add(path + "|" + schemaPath)) {
      return;
    }
    String ref = schema.path("$ref").asText();
    if ("#/definitions/component".equals(ref)) {
      components++;
    }
    if ("#/definitions/bomLinkElementType".equals(ref) && !data.asText().startsWith("urn:cdx:")) {
      return;
    }
    if (data.isTextual()
        && ("#/definitions/refType".equals(ref)
            || "#/definitions/refLinkType".equals(ref)
            || "#/definitions/bomLinkElementType".equals(ref))) {
      (path.endsWith("/bom-ref") ? declarations : uses).put(path, data.textValue());
      return;
    }
    if (ref.startsWith("#/")) {
      visit(data, BomSchema.DEFINITION.at(ref.substring(1)), path, ref, depth + 1);
    }
    // The 1.6 compositions schema uses plain string items for these two reference lists.
    if (data.isArray() && "#/definitions/compositions".equals(schemaPath)) {
      throw new PreparationException("INPUT_SCHEMA_INVALID");
    }
    if (data.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = data.properties().iterator();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        String child = path + "/" + field.getKey().replace("~", "~0").replace("/", "~1");
        if ("#/definitions/compositions".equals(schemaPath)
            && Set.of("dependencies", "vulnerabilities").contains(field.getKey())) {
          for (int i = 0; i < field.getValue().size(); i++) {
            uses.put(child + "/" + i, field.getValue().get(i).asText());
          }
        }
        visit(
            field.getValue(),
            schema.path("properties").path(field.getKey()),
            child,
            schemaPath + "/properties/" + field.getKey(),
            depth + 1);
      }
    } else if (data.isArray()) {
      JsonNode items = schema.path("items");
      for (int i = 0; i < data.size(); i++) {
        visit(
            data.get(i),
            items.isArray() ? items.path(i) : items,
            path + "/" + i,
            schemaPath + "/items" + (items.isArray() ? "/" + i : ""),
            depth + 1);
      }
    }
    for (String keyword : List.of("allOf", "anyOf", "oneOf")) {
      for (int i = 0; i < schema.path(keyword).size(); i++) {
        visit(
            data,
            schema.path(keyword).get(i),
            path,
            schemaPath + "/" + keyword + "/" + i,
            depth + 1);
      }
    }
  }

  int components() {
    return components;
  }

  boolean hasMetadataReferences() {
    return declarations.keySet().stream().anyMatch(path -> path.startsWith("/metadata/"));
  }
}
