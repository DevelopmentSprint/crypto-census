package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The agent's contract when it cannot start: say so on stderr, leave the JVM uninstrumented and
 * never throw into the host application. Each entry point installs at most once per JVM, so each
 * test drives one of them through its failure path and then checks the repeat guard.
 */
class StartupFailureTest {

  @TempDir Path tmp;

  /** Runs {@code action} with System.err captured. */
  private static String stderrOf(ThrowingRunnable action) throws Exception {
    PrintStream original = System.err;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      System.setErr(capture);
      action.run();
    } finally {
      System.setErr(original);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  /** A JVM that refuses everything: no retransformation, no classes, no bootstrap search. */
  private static Instrumentation refusingInstrumentation() {
    return (Instrumentation)
        Proxy.newProxyInstance(
            Instrumentation.class.getClassLoader(),
            new Class<?>[] {Instrumentation.class},
            (proxy, method, args) -> {
              Class<?> type = method.getReturnType();
              if (type == boolean.class) {
                return false;
              }
              if (type == Class[].class) {
                return new Class<?>[0];
              }
              if (type == long.class) {
                return 0L;
              }
              if (type == void.class) {
                throw new UnsupportedOperationException(method.getName());
              }
              return null;
            });
  }

  @Test
  void censusAgentReportsFailedStartInsteadOfThrowing() throws Exception {
    // Outside a jar (here: target/classes) the bootstrap search path cannot be extended.
    String first = stderrOf(() -> CensusAgent.agentmain("summary=false", null));
    assertThat(first).contains("[crypto-census] agent failed to start:");

    String second = stderrOf(() -> CensusAgent.premain("summary=false", null));
    assertThat(second).contains("already started in this JVM");
  }

  @Test
  void installerLeavesTheJvmUninstrumentedWhenRetransformationIsUnavailable() throws Exception {
    File jar = tmp.resolve("empty.jar").toFile();
    try (OutputStream out = Files.newOutputStream(jar.toPath());
        JarOutputStream empty = new JarOutputStream(out)) {
      empty.flush();
    }
    String output = tmp.resolve("cbom.json").toString();
    Instrumentation inst = refusingInstrumentation();

    String first = stderrOf(() -> Installer.install("output=" + output + ",bogus=1", inst, jar));
    assertThat(first)
        .contains("[crypto-census] ignoring unknown option 'bogus'")
        .contains("[crypto-census] instrumentation failed; agent disabled:");
    assertThat(Recorder.enter()).as("recorder stays disabled").isZero();

    String second = stderrOf(() -> Installer.install("", inst, jar));
    assertThat(second).contains("already installed in this JVM");
    assertThat(Files.exists(Path.of(output))).isFalse();
  }
}
