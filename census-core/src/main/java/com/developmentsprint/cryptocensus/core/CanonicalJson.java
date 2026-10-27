package com.developmentsprint.cryptocensus.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bounded canonical JSON shared by evidence and assessment. Only exact integral numbers are used.
 */
public final class CanonicalJson {
  public static final int MAX_BYTES = 8 * 1024 * 1024;
  public static final int MAX_DEPTH = 32;
  private static final int MAX_STRING_LENGTH = 65536;
  private static final int MAX_COLLECTION_SIZE = 32768;

  private CanonicalJson() {}

  /** Encodes a defensive, key-sorted snapshot; list ordering belongs to the typed schema. */
  public static byte[] encode(Object value) {
    Object normalized = normalize(value, 0, new Budget());
    Json.Cost cost = Json.measure(normalized, 0);
    if (cost.bytes() + 1 > MAX_BYTES || cost.retained() > Cbom.MAX_OUTPUT_RETAINED_BYTES) {
      throw new IllegalArgumentException("Canonical JSON exceeds output budget");
    }
    return Json.write(normalized).getBytes(StandardCharsets.UTF_8);
  }

  /** Decodes strict UTF-8 JSON with duplicate-key, size, depth and retained-memory checks. */
  public static Object decode(byte[] bytes) {
    if (bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("Canonical JSON exceeds input budget");
    }
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      return normalize(Json.parse(text), 0, new Budget());
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("Invalid UTF-8 JSON", e);
    }
  }

  /** SHA-256 of exact bytes, used for provenance and pairing, never as authentication. */
  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Required SHA-256 unavailable", e);
    }
  }

  /** Canonical content revision. */
  public static String revision(Object value) {
    return hash(encode(value));
  }

  /** Requires a JSON object with String keys and copies its top-level map for typed decoders. */
  public static Map<String, Object> object(Object value) {
    if (!(value instanceof Map<?, ?> source)) {
      throw new IllegalArgumentException("Expected JSON object");
    }
    Map<String, Object> result = new TreeMap<>();
    source.forEach(
        (key, item) -> {
          if (!(key instanceof String text)) {
            throw new IllegalArgumentException("Expected String object key");
          }
          result.put(text, item);
        });
    return Collections.unmodifiableMap(result);
  }

  /** Rejects missing or unknown fields instead of silently interpreting a different schema. */
  public static void requireKeys(Map<String, Object> object, String... keys) {
    if (!object.keySet().equals(Set.of(keys))) {
      throw new IllegalArgumentException("Unexpected JSON fields: " + object.keySet());
    }
  }

  /** Requires a textual field without coercion. */
  public static String text(Map<String, Object> object, String key) {
    if (object.get(key) instanceof String value) {
      return value;
    }
    throw new IllegalArgumentException("Expected text field " + key);
  }

  /** Requires an exact integral field without coercion. */
  public static long integer(Map<String, Object> object, String key) {
    if (object.get(key) instanceof Long value) {
      return value;
    }
    throw new IllegalArgumentException("Expected integer field " + key);
  }

  /** Requires a boolean field without coercion. */
  public static boolean bool(Map<String, Object> object, String key) {
    if (object.get(key) instanceof Boolean value) {
      return value;
    }
    throw new IllegalArgumentException("Expected boolean field " + key);
  }

  /** Requires an array field without coercion. */
  public static List<?> array(Map<String, Object> object, String key) {
    if (object.get(key) instanceof List<?> value) {
      return value;
    }
    throw new IllegalArgumentException("Expected array field " + key);
  }

  private static Object normalize(Object value, int depth, Budget budget) {
    budget.add(64);
    if (depth > MAX_DEPTH) {
      throw new IllegalArgumentException("Canonical JSON exceeds nesting limit");
    }
    if (value == null || value instanceof Boolean) {
      return value;
    }
    if (value instanceof String text) {
      validateText(text);
      budget.add(2L * text.length());
      return text;
    }
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      return ((Number) value).longValue();
    }
    if (value instanceof Map<?, ?> map) {
      if (map.size() > MAX_COLLECTION_SIZE) {
        throw new IllegalArgumentException("Canonical JSON object too large");
      }
      Map<String, Object> result = new TreeMap<>();
      map.forEach(
          (key, item) -> {
            if (!(key instanceof String text)) {
              throw new IllegalArgumentException("Expected String object key");
            }
            validateText(text);
            budget.add(2L * text.length() + 64);
            result.put(text, normalize(item, depth + 1, budget));
          });
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof List<?> list) {
      if (list.size() > MAX_COLLECTION_SIZE) {
        throw new IllegalArgumentException("Canonical JSON array too large");
      }
      List<Object> result = new ArrayList<>(list.size());
      for (Object item : list) {
        result.add(normalize(item, depth + 1, budget));
      }
      return Collections.unmodifiableList(result);
    }
    throw new IllegalArgumentException("Unsupported canonical JSON value type");
  }

  private static final class Budget {
    private long retained;
    private int values;

    void add(long bytes) {
      retained += bytes;
      values++;
      if (retained > Cbom.MAX_OUTPUT_RETAINED_BYTES || values > 262144) {
        throw new IllegalArgumentException("Canonical JSON exceeds retained-memory budget");
      }
    }
  }

  private static void validateText(String text) {
    if (text.length() > MAX_STRING_LENGTH) {
      throw new IllegalArgumentException("Canonical JSON string too long");
    }
    for (int i = 0; i < text.length(); i++) {
      char next = text.charAt(i);
      if (Character.isHighSurrogate(next)) {
        if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) {
          throw new IllegalArgumentException("Unpaired JSON surrogate");
        }
      } else if (Character.isLowSurrogate(next)) {
        throw new IllegalArgumentException("Unpaired JSON surrogate");
      }
    }
  }
}
