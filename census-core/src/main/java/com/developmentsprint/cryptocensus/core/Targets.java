package com.developmentsprint.cryptocensus.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * What became of each JDK class the agent always means to instrument ({@code javax.crypto.Cipher},
 * {@code sun.security.ssl.SSLSessionImpl}, ...).
 *
 * <p>A JDK that renames or reshapes one of these classes does not make the agent fail: it silently
 * stops observing that kind of cryptography. The agent therefore records a status per expected
 * target, and {@code census:check} reports the gaps. The format of both metadata properties is
 * {@code name=status,name=status}, sorted by name.
 */
public final class Targets {

  /** Status of every expected target in this JVM, including the instrumented ones. */
  public static final String PROPERTY = Cbom.P + "targets";

  /** Only the targets that were not instrumented; empty when all of them were. */
  public static final String MISSING_PROPERTY = Cbom.P + "missingTargets";

  private Targets() {}

  /**
   * What happened to one expected target, in increasing order of concern when evidence from several
   * JVMs is merged.
   */
  public enum Status {
    /** The class exists in this JDK but was never loaded: nothing of that kind ran. Benign. */
    NOT_LOADED,
    /** Instrumented, with at least one advised method. */
    INSTRUMENTED,
    /**
     * Instrumented, but none of the expected methods exist: the JDK changed the class's API, so its
     * use is not observed.
     */
    NO_MATCHING_METHODS,
    /**
     * Loaded, but never transformed (rejected by the JVM or Byte Buddy); its use is not observed.
     */
    NOT_TRANSFORMED,
    /** Not present in this JDK at all (renamed or removed); its use is not observed. */
    ABSENT;

    /** True when uses of this target may have happened without being observed. */
    public boolean isGap() {
      return this == NO_MATCHING_METHODS || this == NOT_TRANSFORMED || this == ABSENT;
    }

    /**
     * Lower-case, hyphenated label as written to the CBOM.
     *
     * @return {@code "not-loaded"}, {@code "instrumented"}, ...
     */
    public String label() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** One sentence for build output, completing "{@code <target>} ...". */
    public String explanation() {
      return switch (this) {
        case NOT_LOADED -> "was not loaded, so nothing of that kind ran";
        case INSTRUMENTED -> "was instrumented";
        case NO_MATCHING_METHODS ->
            "was instrumented but has none of the expected methods (the JDK changed its API)";
        case NOT_TRANSFORMED ->
            "was loaded but never instrumented (see cryptocensus:transformErrors, or run the agent"
                + " with debug=true)";
        case ABSENT ->
            "does not exist in this JDK (renamed or removed); a newer Crypto Census is needed";
      };
    }

    /**
     * Parses a {@link #label()}.
     *
     * @throws IllegalArgumentException for anything else
     */
    public static Status parse(String label) {
      for (Status s : values()) {
        if (s.label().equals(label)) {
          return s;
        }
      }
      throw new IllegalArgumentException("Unknown target status '" + label + "'");
    }
  }

  /**
   * Formats target statuses as {@code name=status,...}, sorted by name.
   *
   * @param statuses status per fully qualified class name
   * @return the property value, empty when {@code statuses} is
   */
  public static String format(Map<String, Status> statuses) {
    StringJoiner joiner = new StringJoiner(",");
    new TreeMap<>(statuses).forEach((name, status) -> joiner.add(name + "=" + status.label()));
    return joiner.toString();
  }

  /**
   * Parses {@link #format(Map)}'s output.
   *
   * @param value the property value; blank means no targets
   * @return status per target, in the order written
   * @throws IllegalArgumentException when an entry is malformed or repeated
   */
  public static Map<String, Status> parse(String value) {
    Map<String, Status> result = new LinkedHashMap<>();
    if (value == null || value.isBlank()) {
      return result;
    }
    for (String entry : value.split(",", -1)) {
      int eq = entry.indexOf('=');
      String name = eq < 0 ? "" : entry.substring(0, eq).trim();
      if (name.isEmpty()) {
        throw new IllegalArgumentException("Malformed target entry '" + entry + "'");
      }
      if (result.put(name, Status.parse(entry.substring(eq + 1).trim())) != null) {
        throw new IllegalArgumentException("Target " + name + " listed more than once");
      }
    }
    return result;
  }

  /**
   * Merges the target statuses of several JVMs. Per target, the most concerning status wins: a gap
   * in any JVM is a gap, otherwise a target instrumented anywhere is instrumented, and a target
   * that no JVM loaded stays not loaded.
   *
   * @param perJvm statuses reported by each JVM
   * @return merged status per target, sorted by name
   */
  public static Map<String, Status> merge(Collection<Map<String, Status>> perJvm) {
    Map<String, Status> merged = new TreeMap<>();
    for (Map<String, Status> jvm : perJvm) {
      jvm.forEach(
          (name, status) -> merged.merge(name, status, (a, b) -> a.compareTo(b) >= 0 ? a : b));
    }
    return Collections.unmodifiableMap(merged);
  }

  /**
   * One line per target with a gap in at least one JVM, for build output.
   *
   * @param perJvm statuses reported by each JVM
   * @return lines such as {@code sun.security.ssl.SSLSessionImpl does not exist in this JDK ... (1
   *     of 3 JVMs)}; empty when nothing was lost
   */
  public static List<String> describeGaps(List<Map<String, Status>> perJvm) {
    List<String> lines = new ArrayList<>();
    merge(perJvm)
        .forEach(
            (name, status) -> {
              if (status.isGap()) {
                long jvms =
                    perJvm.stream().filter(m -> m.containsKey(name) && m.get(name).isGap()).count();
                lines.add(
                    name
                        + " "
                        + status.explanation()
                        + " ("
                        + jvms
                        + " of "
                        + perJvm.size()
                        + " JVM"
                        + (perJvm.size() == 1 ? "" : "s")
                        + ")");
              }
            });
    return lines;
  }

  /**
   * Targets no JVM loaded: harmless, but it tells which kinds of cryptography the tests never
   * exercised.
   */
  public static List<String> notLoaded(Map<String, Status> merged) {
    List<String> names = new ArrayList<>();
    merged.forEach(
        (name, status) -> {
          if (status == Status.NOT_LOADED) {
            names.add(name);
          }
        });
    return names;
  }

  /**
   * The subset of {@code statuses} that is not {@link Status#INSTRUMENTED}, for {@link
   * #MISSING_PROPERTY}.
   */
  public static Map<String, Status> missing(Map<String, Status> statuses) {
    Map<String, Status> result = new TreeMap<>();
    statuses.forEach(
        (name, status) -> {
          if (status != Status.INSTRUMENTED) {
            result.put(name, status);
          }
        });
    return result;
  }
}
