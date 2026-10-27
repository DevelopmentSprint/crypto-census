package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnnotationDiagnosticsTest {
  @Test
  void structuralLocationsAreSanitizedBoundedAndDeduplicated() {
    List<AnnotationDiagnostics.Diagnostic> output = new ArrayList<>();
    var diagnostics = new AnnotationDiagnostics(output::add);
    var reason = AnnotationDiagnostics.Reason.INVALID_OR_UNRESOLVED_DECLARATION;
    for (int repeat = 0; repeat < 2; repeat++) {
      diagnostics.report(
          reason,
          "example.\n" + "A".repeat(300),
          "method\r" + "x".repeat(200),
          "(\u2028\u2029" + "I".repeat(300));
    }
    assertThat(output)
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.reason()).isEqualTo(reason);
              assertThat(value.className()).hasSize(256).startsWith("example.?");
              assertThat(value.method()).hasSize(128).startsWith("method?");
              assertThat(value.descriptor()).hasSize(256).startsWith("(??");
            });
    for (int index = 0; index < 1000; index++) {
      diagnostics.report(
          AnnotationDiagnostics.Reason.UNSUPPORTED_METHOD, "Example", "m" + index, "()V");
    }
    assertThat(output).hasSize(AnnotationDiagnostics.LIMIT + 1);
    assertThat(output.get(output.size() - 1).reason())
        .isEqualTo(AnnotationDiagnostics.Reason.DIAGNOSTIC_LIMIT_REACHED);
  }

  @Test
  void reentrantSinkIsBoundedAndRunsOutsideTheRegistryMonitor() {
    var reference = new java.util.concurrent.atomic.AtomicReference<AnnotationDiagnostics>();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var held = new java.util.concurrent.atomic.AtomicBoolean();
    var diagnostics =
        new AnnotationDiagnostics(
            value -> {
              held.set(held.get() || Thread.holdsLock(reference.get()));
              reference
                  .get()
                  .report(
                      AnnotationDiagnostics.Reason.TRANSFORM_FAILED,
                      "Example",
                      "reentrant" + calls.incrementAndGet(),
                      "()V");
            });
    reference.set(diagnostics);
    diagnostics.report(AnnotationDiagnostics.Reason.TRANSFORM_FAILED, "Example", "first", "()V");
    assertThat(calls.get()).isEqualTo(AnnotationDiagnostics.LIMIT + 1);
    assertThat(held.get()).isFalse();
  }

  @Test
  void overloadedMethodsRemainDistinctAndBrokenSinkCannotEscape() {
    List<AnnotationDiagnostics.Diagnostic> output = new ArrayList<>();
    var diagnostics = new AnnotationDiagnostics(output::add);
    diagnostics.report(AnnotationDiagnostics.Reason.UNSUPPORTED_METHOD, "Example", "m", "()V");
    diagnostics.report(AnnotationDiagnostics.Reason.UNSUPPORTED_METHOD, "Example", "m", "(I)V");
    diagnostics.report(AnnotationDiagnostics.Reason.INSTALL_FAILED, null, null, null);
    assertThat(output).hasSize(3);
    assertThat(output.get(2).className()).isEmpty();
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    var broken =
        new AnnotationDiagnostics(
            value -> {
              attempts.incrementAndGet();
              throw new IllegalStateException("sink unavailable");
            });
    assertThatCode(() -> broken.report(AnnotationDiagnostics.Reason.INSTALL_FAILED, "", "", ""))
        .doesNotThrowAnyException();
    broken.report(AnnotationDiagnostics.Reason.INSTALL_FAILED, "other", "", "");
    assertThat(attempts.get()).isEqualTo(1);
  }
}
