package com.developmentsprint.cryptocensus.it;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/** Loads identical declaring bytecode from one URL into independent deployment class loaders. */
public final class LoaderBoundaryWorkload {
  private LoaderBoundaryWorkload() {}

  public static void main(String[] args) throws Exception {
    ChildJvmCoverage.dumpAfterAgentShutdown();
    URL[] source = {Path.of(args[0]).toUri().toURL()};
    if (args[1].equals("hidden")) {
      try (var loader = new HiddenBridgeLoader(source)) {
        invoke(loader);
      }
    } else {
      var loaders = named(source);
      for (int attempt = 0;
          attempt < 50 && loaders.stream().anyMatch(ref -> ref.get() != null);
          attempt++) {
        System.gc();
        Thread.sleep(20);
      }
      if (loaders.stream().anyMatch(ref -> ref.get() != null)) {
        throw new AssertionError("Closed deployment loader remained strongly reachable");
      }
    }
  }

  private static List<WeakReference<ClassLoader>> named(URL[] source) throws Exception {
    try (var first =
            new URLClassLoader(
                "deployment-one", source, LoaderBoundaryWorkload.class.getClassLoader());
        var second =
            new URLClassLoader(
                "deployment-two", source, LoaderBoundaryWorkload.class.getClassLoader())) {
      invoke(first);
      invoke(second);
      return List.of(new WeakReference<>(first), new WeakReference<>(second));
    }
  }

  @SuppressWarnings(
      "ReferenceEquality") // The fixture verifies the actual defining loader instance.
  private static void invoke(ClassLoader loader) throws Exception {
    Class<?> type = Class.forName("fixture.SharedFlow", true, loader);
    if (type.getClassLoader() != loader) {
      throw new AssertionError("Wrong declaring loader");
    }
    type.getMethod("run").invoke(null);
  }

  private static final class HiddenBridgeLoader extends URLClassLoader {
    HiddenBridgeLoader(URL[] source) {
      super("hidden", source, LoaderBoundaryWorkload.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith("com.developmentsprint.cryptocensus.agent.")) {
        throw new ClassNotFoundException(name);
      }
      return super.loadClass(name, resolve);
    }
  }
}
