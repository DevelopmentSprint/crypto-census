package com.developmentsprint.cryptocensus.agent;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

/**
 * Agent entry point.
 *
 * <p>The JCA classes we instrument ({@code javax.crypto.Cipher}, {@code java.security.Signature},
 * ...) are loaded by the bootstrap class loader, so the advice code inlined into them can only see
 * classes on the bootstrap class path. Before touching any other agent class we therefore append
 * this jar to the bootstrap search path and load the {@code Installer} from there explicitly.
 * Everything in the jar lives under {@code com.developmentsprint.cryptocensus.agent} (Byte Buddy
 * and census-core are relocated), so nothing we add there can shadow an application's own classes.
 *
 * <p>This class must stay tiny and must not reference other agent classes directly.
 */
public final class CensusAgent {

  private static boolean started;

  private CensusAgent() {}

  /**
   * Entry point for {@code -javaagent:census-agent.jar=options}.
   *
   * @param args agent options, see {@code AgentConfig}
   * @param inst the JVM's instrumentation handle
   */
  public static void premain(String args, Instrumentation inst) {
    start(args, inst);
  }

  /**
   * Entry point for dynamic attach.
   *
   * @param args agent options, see {@code AgentConfig}
   * @param inst the JVM's instrumentation handle
   */
  public static void agentmain(String args, Instrumentation inst) {
    start(args, inst);
  }

  // Catching Throwable is the agent's contract: observation must never break the host application.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification =
          "the path is this agent jar's own code-source location, chosen by whoever wrote the"
              + " -javaagent flag; no request or file content influences it")
  private static synchronized void start(String args, Instrumentation inst) {
    if (started) {
      System.err.println(
          "[crypto-census] already started in this JVM; ignoring repeated -javaagent / attach");
      return;
    }
    started = true;
    try {
      File jar =
          new File(CensusAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      inst.appendToBootstrapClassLoaderSearch(new JarFile(jar));
      Class<?> installer =
          Class.forName("com.developmentsprint.cryptocensus.agent.Installer", true, null);
      installer
          .getMethod("install", String.class, Instrumentation.class, File.class)
          .invoke(null, args, inst, jar);
    } catch (Throwable t) {
      // Never take the application down because the observer failed.
      System.err.println("[crypto-census] agent failed to start: " + t);
    }
  }
}
