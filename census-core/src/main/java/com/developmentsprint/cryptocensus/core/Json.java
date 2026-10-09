package com.developmentsprint.cryptocensus.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, strict JSON writer and parser. The agent runs inside arbitrary applications, so it must
 * not drag in (or clash with) Jackson, Gson or similar.
 *
 * <p>The parser reads files that end up gating a build, so it is deliberately strict and bounded:
 * RFC 8259 grammar only (no lenient escapes or number forms), duplicate keys rejected, nesting
 * depth limited, non-finite numbers rejected. Parsed values are {@link Map}, {@link List}, {@link
 * String}, {@link Long}, {@link Double}, {@link Boolean} or {@code null}.
 */
final class Json {

  static final int MAX_DEPTH = 64;

  /** Total values (objects, arrays, scalars) per document. */
  static final int MAX_VALUES = 1_500_000;

  /**
   * Estimated heap retained by the parsed tree, so populated objects cannot exhaust memory while
   * staying under the value count. Deliberately rough (JVM object headers, map entries, strings).
   */
  static final long MAX_RETAINED_BYTES = 64L * 1024 * 1024;

  private Json() {}

  // ---------------------------------------------------------------- writing

  static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    write(sb, value, 0);
    return sb.append('\n').toString();
  }

  private static void write(StringBuilder sb, Object value, int indent) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof String string) {
      quote(sb, string);
    } else if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      if (!Double.isFinite(d)) {
        throw new IllegalArgumentException("Non-finite number " + d);
      }
      sb.append(value);
    } else if (value instanceof Number || value instanceof Boolean) {
      sb.append(value);
    } else if (value instanceof Map) {
      Map<?, ?> map = (Map<?, ?>) value;
      if (map.isEmpty()) {
        sb.append("{}");
        return;
      }
      sb.append("{\n");
      int i = 0;
      for (Map.Entry<?, ?> e : map.entrySet()) {
        pad(sb, indent + 1);
        quote(sb, String.valueOf(e.getKey()));
        sb.append(": ");
        write(sb, e.getValue(), indent + 1);
        sb.append(++i < map.size() ? ",\n" : "\n");
      }
      pad(sb, indent);
      sb.append('}');
    } else if (value instanceof List) {
      List<?> list = (List<?>) value;
      if (list.isEmpty()) {
        sb.append("[]");
        return;
      }
      sb.append("[\n");
      for (int i = 0; i < list.size(); i++) {
        pad(sb, indent + 1);
        write(sb, list.get(i), indent + 1);
        sb.append(i + 1 < list.size() ? ",\n" : "\n");
      }
      pad(sb, indent);
      sb.append(']');
    } else {
      quote(sb, value.toString());
    }
  }

  private static void pad(StringBuilder sb, int indent) {
    for (int i = 0; i < indent; i++) {
      sb.append("  ");
    }
  }

  private static void quote(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append("\\u00")
                .append(Character.forDigit(c >> 4, 16))
                .append(Character.forDigit(c & 0xf, 16));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  // ---------------------------------------------------------------- parsing

  static Object parse(String text) {
    Parser p = new Parser(text);
    p.ws();
    Object value = p.value(0);
    p.ws();
    if (p.pos != text.length()) {
      throw p.error("Trailing content");
    }
    return value;
  }

  /** RFC 8259 number grammar: -?(0|[1-9][0-9]*)(.[0-9]+)?([eE][+-]?[0-9]+)? without a regex. */
  static boolean isJsonNumber(String n) {
    int i = 0;
    int len = n.length();
    if (i < len && n.charAt(i) == '-') {
      i++;
    }
    if (i >= len) {
      return false;
    }
    if (n.charAt(i) == '0') {
      i++;
    } else if (n.charAt(i) >= '1' && n.charAt(i) <= '9') {
      i = digits(n, i);
    } else {
      return false;
    }
    if (i < len && n.charAt(i) == '.') {
      int fraction = digits(n, i + 1);
      if (fraction == i + 1) {
        return false;
      }
      i = fraction;
    }
    if (i < len && (n.charAt(i) == 'e' || n.charAt(i) == 'E')) {
      i++;
      if (i < len && (n.charAt(i) == '+' || n.charAt(i) == '-')) {
        i++;
      }
      int exponent = digits(n, i);
      if (exponent == i) {
        return false;
      }
      i = exponent;
    }
    return i == len;
  }

  private static int digits(String n, int from) {
    int i = from;
    while (i < n.length() && n.charAt(i) >= '0' && n.charAt(i) <= '9') {
      i++;
    }
    return i;
  }

  private static final class Parser {
    private final String text;
    private int pos;
    private int values;
    private long retained;

    void charge(long bytes) {
      retained += bytes;
      if (retained > MAX_RETAINED_BYTES) {
        throw error("Parsed document would retain more than " + MAX_RETAINED_BYTES + " bytes");
      }
    }

    Parser(String text) {
      this.text = text;
    }

    Object value(int depth) {
      if (depth > MAX_DEPTH) {
        throw error("Nesting deeper than " + MAX_DEPTH);
      }
      if (++values > MAX_VALUES) {
        throw error("More than " + MAX_VALUES + " values");
      }
      if (pos >= text.length()) {
        throw error("Unexpected end of input");
      }
      char c = text.charAt(pos);
      return switch (c) {
        case '{' -> object(depth);
        case '[' -> array(depth);
        case '"' -> string();
        case 't' -> {
          literal("true");
          yield true;
        }
        case 'f' -> {
          literal("false");
          yield false;
        }
        case 'n' -> {
          literal("null");
          yield null;
        }
        default -> number();
      };
    }

    Map<String, Object> object(int depth) {
      final Map<String, Object> map = new LinkedHashMap<>();
      charge(64);
      pos++;
      ws();
      if (peek('}')) {
        pos++;
        return map;
      }
      while (true) {
        ws();
        if (!peek('"')) {
          throw error("Expected object key");
        }
        int keyPos = pos;
        String key = string();
        if (map.containsKey(key)) {
          pos = keyPos;
          throw error("Duplicate key \"" + key + "\"");
        }
        ws();
        expect(':');
        ws();
        charge(48);
        map.put(key, value(depth + 1));
        ws();
        if (peek(',')) {
          pos++;
        } else {
          expect('}');
          return map;
        }
      }
    }

    List<Object> array(int depth) {
      final List<Object> list = new ArrayList<>();
      charge(40);
      pos++;
      ws();
      if (peek(']')) {
        pos++;
        return list;
      }
      while (true) {
        ws();
        charge(8);
        list.add(value(depth + 1));
        ws();
        if (peek(',')) {
          pos++;
        } else {
          expect(']');
          return list;
        }
      }
    }

    String string() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (pos >= text.length()) {
          throw error("Unterminated string");
        }
        char c = text.charAt(pos++);
        if (c == '"') {
          charge(40 + 2L * sb.length());
          return sb.toString();
        }
        if (c < 0x20) {
          throw error("Unescaped control character");
        }
        if (retained + 2L * sb.length() > MAX_RETAINED_BYTES) {
          throw error("Parsed document would retain more than " + MAX_RETAINED_BYTES + " bytes");
        }
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        if (pos >= text.length()) {
          throw error("Unterminated escape");
        }
        char e = text.charAt(pos++);
        switch (e) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/' -> sb.append('/');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (pos + 4 > text.length()) {
              throw error("Truncated unicode escape");
            }
            int cp = 0;
            for (int i = 0; i < 4; i++) {
              int d = Character.digit(text.charAt(pos + i), 16);
              if (d < 0) {
                throw error("Invalid unicode escape");
              }
              cp = cp * 16 + d;
            }
            sb.append((char) cp);
            pos += 4;
          }
          default -> throw error("Invalid escape \\" + e);
        }
      }
    }

    Number number() {
      int start = pos;
      while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
        pos++;
      }
      String n = text.substring(start, pos);
      if (n.isEmpty()) {
        throw error("Unexpected character '" + text.charAt(pos) + "'");
      }
      if (!isJsonNumber(n)) {
        pos = start;
        throw error("Invalid number " + n);
      }
      charge(16);
      if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
        try {
          return Long.parseLong(n);
        } catch (NumberFormatException overflow) {
          pos = start;
          throw error("Integer out of range " + n, overflow);
        }
      }
      double d = Double.parseDouble(n);
      if (!Double.isFinite(d)) {
        pos = start;
        throw error("Number out of range " + n);
      }
      return d;
    }

    void literal(String word) {
      if (!text.startsWith(word, pos)) {
        throw error("Expected " + word);
      }
      pos += word.length();
    }

    void ws() {
      while (pos < text.length()) {
        char c = text.charAt(pos);
        if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
          break;
        }
        pos++;
      }
    }

    boolean peek(char c) {
      return pos < text.length() && text.charAt(pos) == c;
    }

    void expect(char c) {
      if (!peek(c)) {
        throw error("Expected '" + c + "'");
      }
      pos++;
    }

    IllegalArgumentException error(String message) {
      return new IllegalArgumentException(message + " at offset " + pos);
    }

    IllegalArgumentException error(String message, Throwable cause) {
      return new IllegalArgumentException(message + " at offset " + pos, cause);
    }
  }
}
