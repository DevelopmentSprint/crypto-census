package com.developmentsprint.cryptocensus.agent;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Bounded structural diagnostics; annotation values and exception payloads are never retained. */
final class AnnotationDiagnostics {
  static final int LIMIT = 32;
  private static final System.Logger LOG = System.getLogger(AnnotationDiagnostics.class.getName());

  enum Reason {
    INVALID_OR_UNRESOLVED_DECLARATION,
    UNSUPPORTED_METHOD,
    BRIDGE_UNAVAILABLE,
    DESCRIPTORS_UNAVAILABLE,
    CLASS_BUDGET_EXCEEDED,
    CLASS_READ_FAILED,
    TRANSFORM_FAILED,
    INSTALL_FAILED,
    DIAGNOSTIC_LIMIT_REACHED
  }

  record Diagnostic(Reason reason, String className, String method, String descriptor) {}

  private final Set<Diagnostic> seen = new HashSet<>();
  private final Consumer<Diagnostic> sink;
  private boolean truncated;
  private volatile boolean sinkUnavailable;

  AnnotationDiagnostics() {
    this(
        value ->
            LOG.log(
                System.Logger.Level.WARNING,
                "Census annotation diagnostic reason={0} class={1} method={2} descriptor={3}",
                value.reason(),
                value.className(),
                value.method(),
                value.descriptor()));
  }

  AnnotationDiagnostics(Consumer<Diagnostic> sink) {
    this.sink = sink;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  void report(Reason reason, String className, String method, String descriptor) {
    if (sinkUnavailable) {
      return;
    }
    try {
      Diagnostic output =
          retain(
              new Diagnostic(
                  reason, bounded(className, 256), bounded(method, 128), bounded(descriptor, 256)));
      if (output != null) {
        // Sink code may reenter instrumentation. Never call it with the registry monitor held.
        sink.accept(output);
      }
    } catch (Throwable unavailable) {
      // Health counters already retain the instrumentation failure. A broken diagnostic sink must
      // not change application execution or recursively attempt to report its own failure.
      sinkUnavailable = true;
    }
  }

  private synchronized Diagnostic retain(Diagnostic value) {
    if (seen.contains(value) || truncated) {
      return null;
    }
    if (seen.size() == LIMIT) {
      truncated = true;
      return new Diagnostic(Reason.DIAGNOSTIC_LIMIT_REACHED, "", "", "");
    }
    seen.add(value);
    return value;
  }

  private static String bounded(String value, int limit) {
    if (value == null) {
      return "";
    }
    int length = Math.min(value.length(), limit);
    StringBuilder output = new StringBuilder(length);
    for (int index = 0; index < length; index++) {
      char character = value.charAt(index);
      output.append(
          Character.isISOControl(character) || character == '\u2028' || character == '\u2029'
              ? '?'
              : character);
    }
    return output.toString();
  }
}
