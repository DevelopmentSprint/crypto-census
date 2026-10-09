package com.developmentsprint.cryptocensus.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Percent-encoding for values embedded in small key/value grammars: agent options ({@code
 * key=value,key=value}), CBOM {@code additionalContext} ({@code key=value; key=value}) and {@code
 * bom-ref} segments.
 *
 * <p>Only the characters that are structural in those grammars (plus whitespace, quotes and {@code
 * %} itself) are encoded, so ordinary paths stay readable. Decoding is strict: {@code %} must be
 * followed by two ASCII hex digits, {@code %00} is rejected, and the result must be valid UTF-8.
 */
public final class Escapes {

  private static final String RESERVED = "%,;=:@\"' \t\r\n";

  private Escapes() {}

  /**
   * Percent-encodes structural characters, whitespace, quotes, {@code %} and non-ASCII bytes.
   *
   * @param value the raw value, or null
   * @return the encoded value, or null
   */
  public static String encode(String value) {
    if (value == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(value.length());
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      if (c < 0x20 || c >= 0x7f || RESERVED.indexOf(c) >= 0) {
        sb.append('%')
            .append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
            .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
      } else {
        sb.append((char) c);
      }
    }
    return sb.toString();
  }

  /**
   * Strictly decodes a value produced by {@link #encode(String)}.
   *
   * @param value the encoded value, or null
   * @return the decoded value, or null
   * @throws IllegalArgumentException on a malformed escape, an encoded NUL, or invalid UTF-8
   */
  public static String decode(String value) {
    if (value == null || value.indexOf('%') < 0) {
      return value;
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
    int i = 0;
    while (i < value.length()) {
      if (value.charAt(i) == '%') {
        int hi = i + 1 < value.length() ? hex(value.charAt(i + 1)) : -1;
        int lo = i + 2 < value.length() ? hex(value.charAt(i + 2)) : -1;
        if (hi < 0 || lo < 0) {
          throw new IllegalArgumentException(
              "Malformed escape at offset " + i + " in '" + value + "'");
        }
        int b = hi * 16 + lo;
        if (b == 0) {
          throw new IllegalArgumentException("Encoded NUL is not allowed in '" + value + "'");
        }
        out.write(b);
        i += 3;
      } else {
        int cp = value.codePointAt(i);
        byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        i += Character.charCount(cp);
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(out.toByteArray()))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IllegalArgumentException("Escapes do not form valid UTF-8 in '" + value + "'", e);
    }
  }

  /** ASCII hex digit value, or -1 (Character.digit would also accept full-width digits). */
  private static int hex(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    return -1;
  }
}
