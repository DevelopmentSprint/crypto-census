package com.developmentsprint.cryptocensus.agent;

/** Checks loader delegation before application bytecode is given calls to the scope bridge. */
final class FlowVisibility {
  private FlowVisibility() {}

  /**
   * Verifies class identity without initializing an application-provided class. A same-named
   * private copy cannot safely substitute for the installed agent's bridge.
   */
  // Loader identity is deliberate: custom equals implementations cannot establish delegation.
  @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
  static boolean resolvesBridge(ClassLoader loader) {
    if (loader == null || loader == ClassLoader.getPlatformClassLoader()) {
      return false;
    }
    try {
      return Class.forName(FlowBridge.class.getName(), false, loader) == FlowBridge.class;
    } catch (ClassNotFoundException | LinkageError | SecurityException unavailable) {
      return false;
    }
  }
}
