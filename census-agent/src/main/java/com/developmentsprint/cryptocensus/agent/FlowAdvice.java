package com.developmentsprint.cryptocensus.agent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import net.bytebuddy.asm.Advice;

/** Inlined entry/exit code has only primitive locals and bridge method descriptors. */
public final class FlowAdvice {
  private FlowAdvice() {}

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  @interface BindingId {}

  /**
   * Enters the bound synchronous scope.
   *
   * @param binding normalized registry identifier
   * @return primitive balancing value, or zero for no successful entry
   */
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static long enter(@BindingId int binding) {
    return FlowBridge.enter(binding);
  }

  /**
   * Restores the scope on both normal and exceptional exits.
   *
   * @param previous primitive value from entry
   */
  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void exit(@Advice.Enter long previous) {
    FlowBridge.exit(previous);
  }
}
