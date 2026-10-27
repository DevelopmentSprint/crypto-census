package com.developmentsprint.cryptocensus.it;

import java.lang.instrument.Instrumentation;

/** Test agent supplies the real JVM handle and loads an application type before Census installs. */
public final class RuntimeControlAgent {
  private static Instrumentation instrumentation;

  private RuntimeControlAgent() {}

  public static void premain(String ignored, Instrumentation handle) throws Exception {
    instrumentation = handle;
    Class.forName("com.developmentsprint.cryptocensus.it.RetransformWorkload$Service");
  }

  static void retransform(Class<?>... types) throws Exception {
    if (instrumentation == null || !instrumentation.isRetransformClassesSupported()) {
      throw new AssertionError("Real retransformation instrumentation unavailable");
    }
    for (Class<?> type : types) {
      if (!instrumentation.isModifiableClass(type)) {
        throw new AssertionError("Fixture class is not modifiable: " + type.getName());
      }
    }
    instrumentation.retransformClasses(types);
  }
}
