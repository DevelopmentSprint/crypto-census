package com.developmentsprint.cryptocensus.agent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Primitive-only entry/exit bridge for advice in application classes.
 *
 * <p>Public solely for inlined bytecode. This is agent infrastructure, not an application API.
 * Annotation instrumentation must verify that the defining loader resolves this bootstrap copy
 * before emitting calls. No agent-defined reference type enters application verifier frames.
 *
 * <p>The process-wide generation budget is 2,147,483,647 enabled entry attempts. Exhaustion
 * disables trusted scope attribution with an integrity diagnostic; it never reuses old tokens.
 * Ordinary cryptographic inventory remains independent of this budget.
 */
public final class FlowBridge {
  // Never reset token identity when a configured run replaces its thread-local state.
  private static final AtomicInteger GENERATIONS = new AtomicInteger();
  private static volatile FlowScopes scopes = new FlowScopes(FlowScopes.MAX_DEPTH, GENERATIONS);

  private FlowBridge() {}

  /**
   * Starts a synchronous scope if enrichment is enabled.
   *
   * @param binding a bounded registry ID, or zero for unavailable binding metadata
   * @return a balancing token; zero means no successful entry
   */
  public static long enter(int binding) {
    return scopes.enter(binding);
  }

  /**
   * Restores the scope captured by an entry token, independently of the enabled state.
   *
   * @param token the token returned by {@link #enter(int)}
   */
  public static void exit(long token) {
    scopes.exit(token);
  }

  static void configure(int maxDepth) {
    scopes = new FlowScopes(maxDepth, GENERATIONS);
  }

  static FlowScopes scopes() {
    return scopes;
  }
}
