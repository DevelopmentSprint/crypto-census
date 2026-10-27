package com.developmentsprint.cryptocensus.core;

/** Bounded strict JSON access for out-of-process Census tooling without agent dependencies. */
public final class JsonDocument {
  private JsonDocument() {}

  /**
   * Parses strict JSON with the same depth, value and retained-memory bounds as Census evidence.
   */
  @SuppressWarnings(
      "PMD.PreserveStackTrace") // Raw parser diagnostics may contain inventory or secrets.
  public static Object parse(String value) {
    if (value == null || value.length() > 16 * 1024 * 1024) {
      throw new IllegalArgumentException("JSON document exceeds its limit");
    }
    try {
      return Json.parse(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("JSON document is invalid or exceeds its limits");
    }
  }

  /** Serializes a bounded JSON value tree using the canonical Census writer. */
  public static String write(Object value) {
    validate(value, 0, new int[1]);
    Json.Cost cost = Json.measure(value, 0);
    if (cost.bytes() > 16 * 1024 * 1024L
        || cost.depth() > Json.MAX_DEPTH
        || cost.values() > Json.MAX_VALUES
        || cost.retained() > Json.MAX_RETAINED_BYTES) {
      throw new IllegalArgumentException("JSON document exceeds its limit");
    }
    return Json.write(canonical(value));
  }

  private static Object canonical(Object value) {
    if (value instanceof java.util.Map<?, ?> map) {
      java.util.Map<String, Object> result = new java.util.TreeMap<>();
      for (var entry : map.entrySet()) {
        result.put((String) entry.getKey(), canonical(entry.getValue()));
      }
      return result;
    }
    if (value instanceof java.util.List<?> list) {
      java.util.List<Object> result = new java.util.ArrayList<>(list.size());
      for (Object item : list) {
        result.add(canonical(item));
      }
      return result;
    }
    return value;
  }

  private static void validate(Object value, int depth, int[] count) {
    if (depth > Json.MAX_DEPTH || ++count[0] > Json.MAX_VALUES) {
      throw new IllegalArgumentException("JSON tree exceeds its limits");
    }
    if (value instanceof java.util.Map<?, ?> map) {
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String)) {
          throw new IllegalArgumentException("JSON object key must be a string");
        }
        validate(entry.getValue(), depth + 1, count);
      }
    } else if (value instanceof java.util.List<?> list) {
      for (Object item : list) {
        validate(item, depth + 1, count);
      }
    } else if (value != null
        && !(value instanceof String)
        && !(value instanceof Boolean)
        && !(value instanceof Number)) {
      throw new IllegalArgumentException("Unsupported JSON value");
    }
  }
}
