package com.developmentsprint.cryptocensus.it;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JaCoCo coverage for the child JVMs that run the packaged agent; see the quality profile in
 * census-agent/pom.xml for how the measurement as a whole works.
 *
 * <p>Test JVM side: {@link #jvmArgs()} turns the {@code census.it.jacocoAgent} system property,
 * which the quality profile passes through Failsafe, into the child's {@code -javaagent} option.
 * Without that profile (JDK 17, {@code -Djacoco.skip}) the property is absent, empty or an
 * unresolved {@code ${...}} expression, and child JVMs run without coverage.
 *
 * <p>Child JVM side: JaCoCo writes its data from a JVM shutdown hook, and the census agent writes
 * its CBOM from another one. Shutdown hooks run concurrently, so JaCoCo's dump usually misses the
 * CBOM-writing code. {@link #dumpAfterAgentShutdown()} adds a hook that waits for the census hook
 * to finish and then dumps again; the JaCoCo agent appends, and the report merges both dumps.
 */
final class ChildJvmCoverage {

  /** The census agent's shutdown hook thread (see Installer). */
  private static final String CENSUS_SHUTDOWN_THREAD = "crypto-census-shutdown";

  private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);

  private ChildJvmCoverage() {}

  /** Extra JVM arguments for a child JVM, placed before the census agent's own -javaagent. */
  static List<String> jvmArgs() {
    String agent = System.getProperty("census.it.jacocoAgent", "").strip();
    // JaCoCo quotes the whole option when its path contains spaces (it is meant for an argLine);
    // ProcessBuilder passes each argument verbatim, so the quotes must go.
    if (agent.length() >= 2 && agent.startsWith("\"") && agent.endsWith("\"")) {
      agent = agent.substring(1, agent.length() - 1);
    }
    return agent.startsWith("-javaagent:") ? List.of(agent) : List.of();
  }

  /** In a child JVM: dump coverage once more after the census agent's shutdown hook is done. */
  static void dumpAfterAgentShutdown() {
    Object agent;
    Method dump;
    try {
      agent = Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null);
      dump = Class.forName("org.jacoco.agent.rt.IAgent").getMethod("dump", boolean.class);
    } catch (ReflectiveOperationException | LinkageError e) {
      return; // not running under JaCoCo
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  awaitCensusShutdown();
                  dumpWithRetry(agent, dump);
                },
                "census-it-coverage-dump"));
  }

  private static void awaitCensusShutdown() {
    long deadline = System.nanoTime() + WAIT_NANOS;
    try {
      // all hooks are started together, but not necessarily the census one before this one
      while (System.nanoTime() < deadline) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
          if (CENSUS_SHUTDOWN_THREAD.equals(t.getName())) {
            t.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            return;
          }
        }
        Thread.sleep(10);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void dumpWithRetry(Object agent, Method dump) {
    for (int attempt = 0; attempt < 50; attempt++) {
      try {
        dump.invoke(agent, false);
        return;
      } catch (ReflectiveOperationException e) {
        // JaCoCo's own hook may hold the file lock right now (OverlappingFileLockException)
        try {
          Thread.sleep(20);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
    System.err.println("[census-it] could not write JaCoCo data after the agent's shutdown");
  }
}
