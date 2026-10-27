package com.developmentsprint.cryptocensus.delivery.preparation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.OutputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pinned offline schema; document-supplied schema declarations are never dereferenced. */
final class BomSchema {
  static final int MAX_COMPONENTS = 100_000;
  private static final JsonFactory FACTORY =
      JsonFactory.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(64)
                  .maxStringLength(1024 * 1024)
                  .maxNumberLength(128)
                  .build())
          .build();
  static final ObjectMapper JSON =
      new ObjectMapper(FACTORY)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
  static final JsonNode DEFINITION;
  private static final Schema SCHEMA;

  static {
    try {
      Map<String, String> schemas = new LinkedHashMap<>();
      for (String name :
          new String[] {"bom-1.6.schema.json", "spdx.schema.json", "jsf-0.82.schema.json"}) {
        try (InputStream in = BomSchema.class.getResourceAsStream("schema/" + name)) {
          if (in == null) {
            throw new IOException("Missing pinned schema");
          }
          String data = new String(in.readAllBytes(), StandardCharsets.UTF_8);
          schemas.put("http://cyclonedx.org/schema/" + name, data);
        }
      }
      DEFINITION = JSON.readTree(schemas.get("http://cyclonedx.org/schema/bom-1.6.schema.json"));
      SCHEMA =
          SchemaRegistry.withDefaultDialect(
                  SpecificationVersion.DRAFT_7,
                  builder ->
                      builder
                          .schemas(schemas)
                          .schemaRegistryConfig(
                              com.networknt.schema.SchemaRegistryConfig.builder()
                                  .formatAssertionsEnabled(true)
                                  .build())
                          .schemaLoader(loader -> loader.fetchRemoteResources(false)))
              .getSchema(SchemaLocation.of("http://cyclonedx.org/schema/bom-1.6.schema.json"));
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private BomSchema() {}

  static ObjectNode parse(byte[] bytes) throws IOException {
    ObjectNode object = parseObject(bytes);
    if (!"CycloneDX".equals(object.path("bomFormat").asText())
        || !"1.6".equals(object.path("specVersion").asText())) {
      throw new PreparationException("INPUT_SCHEMA_UNSUPPORTED");
    }
    if (object.has("$schema")
        && !java.util.Set.of(
                "http://cyclonedx.org/schema/bom-1.6.schema.json",
                "https://cyclonedx.org/schema/bom-1.6.schema.json")
            .contains(object.path("$schema").asText())) {
      throw new PreparationException("INPUT_SCHEMA_INVALID");
    }
    if (!SCHEMA.validate(object, OutputFormat.BOOLEAN)) {
      throw new PreparationException("INPUT_SCHEMA_INVALID");
    }
    return object;
  }

  @SuppressWarnings(
      "PMD.PreserveStackTrace") // Never expose parser content, including secret values.
  static ObjectNode parseObject(byte[] bytes) throws IOException {
    if (bytes.length > PreparationIo.MAX_BYTES) {
      throw new PreparationException("INPUT_LIMIT_EXCEEDED");
    }
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      // Bound aggregate tree allocation before constructing Jackson nodes.
      try (JsonParser parser = FACTORY.createParser(text)) {
        int values = 0;
        long retained = 0;
        JsonToken token;
        while ((token = parser.nextToken()) != null) {
          if (++values > 250_000) {
            throw new PreparationException("INPUT_LIMIT_EXCEEDED");
          }
          if (token == JsonToken.VALUE_STRING || token == JsonToken.FIELD_NAME) {
            validUnicode(parser.getText());
          }
          retained +=
              64L
                  + (token == JsonToken.VALUE_STRING || token == JsonToken.FIELD_NAME
                      ? 2L * parser.getTextLength()
                      : 0L);
          if (retained > 64L * 1024 * 1024) {
            throw new PreparationException("INPUT_LIMIT_EXCEEDED");
          }
        }
      }
      try (JsonParser parser = FACTORY.createParser(text)) {
        JsonNode node = JSON.readTree(parser);
        if (!(node instanceof ObjectNode object) || parser.nextToken() != null) {
          throw new PreparationException("INPUT_JSON_INVALID");
        }
        return object;
      }
    } catch (PreparationException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new PreparationException("INPUT_JSON_INVALID");
    }
  }

  private static void validUnicode(String value) throws PreparationException {
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (Character.isHighSurrogate(current)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
          throw new PreparationException("INPUT_JSON_INVALID");
        }
      } else if (Character.isLowSurrogate(current)) {
        throw new PreparationException("INPUT_JSON_INVALID");
      }
    }
  }

  static byte[] bytes(JsonNode tree) throws IOException {
    return JSON.writeValueAsBytes(tree);
  }
}
