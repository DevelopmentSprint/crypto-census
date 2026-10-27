package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.core.JsonDocument;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Strict bounded JSON and schema helpers; exceptions never contain supplied values. */
public final class JsonSupport {
  private JsonSupport() {}

  /** Parses valid UTF-8 without replacement or raw parser diagnostics. */
  @SuppressWarnings("PMD.PreserveStackTrace")
  public static Object parse(byte[] bytes) {
    if (bytes.length > 16 * 1024 * 1024) {
      throw new IllegalArgumentException("JSON document exceeds its limit");
    }
    try {
      return JsonDocument.parse(
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString());
    } catch (CharacterCodingException | IllegalArgumentException error) {
      throw new IllegalArgumentException("JSON document is invalid or exceeds its limits");
    }
  }

  /** Emits canonical bounded UTF-8 JSON. */
  @SuppressWarnings("PMD.PreserveStackTrace")
  public static byte[] write(Object value) {
    try {
      return JsonDocument.write(value).getBytes(StandardCharsets.UTF_8);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("JSON value is invalid or exceeds its limits");
    }
  }

  /** Requires an object with string keys without an unchecked cast. */
  public static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("Expected a JSON object");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("Expected a JSON object with string keys");
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  /** Rejects every unknown option; field values are never echoed. */
  public static void fields(Map<String, Object> value, String... allowed) {
    if (!Set.of(allowed).containsAll(value.keySet())) {
      throw new IllegalArgumentException("Unknown configuration field");
    }
  }

  /** Requires a nonempty bounded string; use typed validation after decoding. */
  public static String string(Map<String, Object> value, String name) {
    if (!(value.get(name) instanceof String text) || text.isEmpty() || text.length() > 2048) {
      throw new IllegalArgumentException("Missing or invalid required string field: " + name);
    }
    return text;
  }
}
