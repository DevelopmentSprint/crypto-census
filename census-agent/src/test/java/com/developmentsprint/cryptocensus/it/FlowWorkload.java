package com.developmentsprint.cryptocensus.it;

/** Loads annotated application code only after the observer has begun ordinary recording. */
public final class FlowWorkload {
  private FlowWorkload() {}

  public static void invoke(Runnable callback) {
    callback.run();
  }

  public static void main(String[] args) throws Exception {
    ChildJvmCoverage.dumpAfterAgentShutdown();
    LateFlowWorkload.raw();
    // A distinct type is explicitly loaded late to catch observer hashing polluting inventory.
    Class<?> late = Class.forName("com.developmentsprint.cryptocensus.it.LateAnnotatedWorkload");
    late.getMethod("run", boolean.class).invoke(null, args.length > 0 && "stack".equals(args[0]));
    LateFlowWorkload.raw();
  }
}
