package com.developmentsprint.cryptocensus.core;

import java.util.Objects;

/**
 * Where an asset was used: the first application frame on the stack when the JCA call happened.
 *
 * @param location fully qualified class name, or {@code <jdk>} when no application frame was found
 * @param symbol method name, or {@code null}
 * @param line source line, or {@code -1} when unknown
 * @param via JDK subsystem the call went through ({@code jsse}, {@code jar-verification}, ...), or
 *     {@code null} for direct application use
 */
public record Occurrence(String location, String symbol, int line, String via) {

  public static final String JDK_LOCATION = "<jdk>";

  /** Stands in for further call sites once an asset reaches its per-asset limit. */
  public static final Occurrence OVERFLOW = new Occurrence("<overflow>", null, -1, null);

  /** Used when a CBOM component carries no occurrence evidence. */
  public static final Occurrence UNKNOWN = new Occurrence("<unknown>", null, -1, null);

  public Occurrence {
    Objects.requireNonNull(location, "location");
    location = Names.truncate(location);
    symbol = Names.truncate(symbol);
    via = Names.truncate(via);
    if (line < -1) line = -1;
    if (symbol != null && symbol.isBlank()) symbol = null;
    if (via != null && via.isBlank()) via = null;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder(location);
    if (symbol != null) sb.append('.').append(symbol);
    if (line >= 0) sb.append(':').append(line);
    if (via != null) sb.append(" (via ").append(via).append(')');
    return sb.toString();
  }
}
