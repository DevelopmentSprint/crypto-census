package com.developmentsprint.cryptocensus.it;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.jar.JarFile;

/** Launches the actual shaded production transformer, independently of CLI wiring. */
public final class TlsProductionAgent {
  private TlsProductionAgent() {}

  public static void premain(String agentJar, Instrumentation instrumentation) throws Exception {
    instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(agentJar));
    Bootstrap.initialize(instrumentation, agentJar);
  }

  private static final class Bootstrap {
    private static final String AGENT = "com.developmentsprint.cryptocensus.agent.";
    private static final String CORE = AGENT + "shaded.core.EvidenceDocument$";
    private static final String BYTE_BUDDY = AGENT + "shaded.bytebuddy.";

    private Bootstrap() {}

    static void initialize(Instrumentation instrumentation, String jar) throws Exception {
      System.setProperty(BYTE_BUDDY + "safe", "true");
      Class<?> association = Class.forName(CORE + "Association", true, null);
      Object noScope = association.getField("NO_SCOPE").get(null);
      Constructor<?> context =
          Class.forName(CORE + "ScopeContext", true, null)
              .getConstructor(String.class, List.class, association);
      Supplier<Object> supplier =
          () -> {
            if (Boolean.getBoolean("census.tls.fixtureFault")) {
              throw new IllegalStateException("injected context failure");
            }
            try {
              return context.newInstance("tls-fixture", List.of(), noScope);
            } catch (ReflectiveOperationException failure) {
              throw new IllegalStateException(failure);
            }
          };
      Consumer<Object> health = counter -> System.err.println("TLS-HEALTH:" + counter);
      Class<?> collector = Class.forName(AGENT + "TlsCollector", true, null);
      collector
          .getMethod("configure", Supplier.class, Consumer.class)
          .invoke(null, supplier, health);
      Class<?> locator = Class.forName(BYTE_BUDDY + "dynamic.ClassFileLocator", true, null);
      Object jarLocator =
          Class.forName(BYTE_BUDDY + "dynamic.ClassFileLocator$ForJarFile", true, null)
              .getMethod("of", File.class)
              .invoke(null, new File(jar));
      Class<?> installer = Class.forName(AGENT + "TlsInstrumentation", true, null);
      Object installed =
          installer
              .getMethod("install", Instrumentation.class, locator)
              .invoke(null, instrumentation, jarLocator);
      if (!Boolean.TRUE.equals(installed)) {
        throw new IllegalStateException(
            "Production TLS installation failed: "
                + installer.getMethod("hookHealth").invoke(null));
      }
    }
  }
}
