package com.developmentsprint.cryptocensus.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded synchronous execution scopes, independent of the cryptographic recorder's health. */
final class FlowScopes {
  static final int MAX_DEPTH = 32;
  static final int MAX_BINDINGS = 8192;
  static final int MAX_NESTING = 0xffff;
  static final long MAX_GENERATION = (1L << 48) - 1;

  private static final Snapshot NO_SCOPE = new Snapshot(Status.NO_SCOPE, List.of());
  private static final Snapshot LOST = new Snapshot(Status.SCOPE_LOST, List.of());

  // Each configured run owns isolated state; a static ThreadLocal would leak across
  // reconfiguration.
  @SuppressWarnings("ThreadLocalUsage")
  private final ThreadLocal<State> local = new ThreadLocal<>();

  private final AtomicLong generations;
  private final AtomicLong overflowed = new AtomicLong();
  private final AtomicLong integrityFailures = new AtomicLong();
  private final AtomicLong entryFailures = new AtomicLong();
  private final AtomicLong restoreFailures = new AtomicLong();
  private final int maxDepth;
  private final Runnable beforeEntry;
  private final Runnable beforeRestore;
  private final Runnable afterRestore;
  private volatile boolean enabled;
  private volatile boolean unverified;

  FlowScopes(int maxDepth) {
    this(maxDepth, new AtomicLong());
  }

  FlowScopes(int maxDepth, AtomicLong generations) {
    this(maxDepth, generations, null, null, null);
  }

  /**
   * Fault hooks are null in production and are run only at explicitly tested transaction points.
   */
  FlowScopes(int maxDepth, Runnable beforeEntry, Runnable beforeRestore, Runnable afterRestore) {
    this(maxDepth, new AtomicLong(), beforeEntry, beforeRestore, afterRestore);
  }

  private FlowScopes(
      int maxDepth,
      AtomicLong generations,
      Runnable beforeEntry,
      Runnable beforeRestore,
      Runnable afterRestore) {
    if (maxDepth < 1 || maxDepth > MAX_DEPTH) {
      throw new IllegalArgumentException("Scope depth must be between 1 and " + MAX_DEPTH);
    }
    this.maxDepth = maxDepth;
    this.generations = generations;
    this.beforeEntry = beforeEntry;
    this.beforeRestore = beforeRestore;
    this.afterRestore = afterRestore;
  }

  void enable() {
    enabled = true;
  }

  void disable() {
    enabled = false;
  }

  /**
   * Returns a balancing token, never zero after a committed push. ID zero means unknown scope.
   * Normal-frame generations detect stale sibling tokens; overflow stays lost until root exit.
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  long enter(int binding) {
    if (!enabled) {
      return 0;
    }
    State state = null;
    try {
      state = local.get();
      if (state == null) {
        state = new State(maxDepth);
        local.set(state);
      }
      if (state.depth >= MAX_NESTING || state.depth < 0) {
        invalidate();
        return 0;
      }
      long generation = nextGeneration();
      if (generation <= 0) {
        invalidate();
        return 0;
      }
      int depth = state.depth + 1;
      final long token = (generation << 16) | depth;
      boolean lost = binding <= 0 || binding > MAX_BINDINGS;
      boolean beyondCap = depth > maxDepth;
      if (beforeEntry != null) {
        beforeEntry.run();
      }
      // Record diagnostics before committing a push: failure here cannot strand a new frame.
      if (beyondCap) {
        increment(overflowed);
      }
      if (!beyondCap) {
        state.bindings[depth - 1] = lost ? 0 : binding;
        state.generations[depth - 1] = generation;
      }
      state.lost |= lost || beyondCap;
      state.depth = depth;
      return token;
    } catch (Throwable failure) {
      if (state != null) {
        state.lost = true;
        if (state.depth == 0) {
          local.remove();
        }
      }
      unverified = true;
      increment(entryFailures);
      return 0;
    }
  }

  /** Restores primitive state before diagnostics; token zero never pops an existing parent. */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  void exit(long token) {
    if (token == 0) {
      return;
    }
    try {
      State state = local.get();
      int depth = (int) (token & MAX_NESTING);
      long generation = token >>> 16;
      if (state == null
          || depth <= 0
          || generation <= 0
          || state.depth != depth
          || (depth <= maxDepth && state.generations[depth - 1] != generation)) {
        invalidate();
        return;
      }
      if (beforeRestore != null) {
        beforeRestore.run();
      }
      state.depth = depth - 1;
      if (depth <= maxDepth) {
        state.bindings[depth - 1] = 0;
        state.generations[depth - 1] = 0;
      }
      if (state.depth == 0) {
        local.remove();
      }
      if (afterRestore != null) {
        afterRestore.run();
      }
    } catch (Throwable failure) {
      // Resource exhaustion can prevent this handler itself from running; evidence documents that
      // residual limit. A recorded failure invalidates the entire run, including prior snapshots.
      unverified = true;
      local.remove();
      increment(restoreFailures);
    }
  }

  Snapshot current() {
    State state = local.get();
    if (unverified) {
      return LOST;
    }
    if (state == null || state.depth == 0) {
      return NO_SCOPE;
    }
    if (state.lost) {
      return LOST;
    }
    List<Integer> bindings = new ArrayList<>(state.depth);
    for (int i = 0; i < state.depth; i++) {
      bindings.add(state.bindings[i]);
    }
    return new Snapshot(Status.SYNCHRONOUS_SCOPE, bindings);
  }

  boolean hasThreadState() {
    return local.get() != null;
  }

  boolean unverified() {
    return unverified;
  }

  long overflowed() {
    return overflowed.get();
  }

  long integrityFailures() {
    return integrityFailures.get();
  }

  long entryFailures() {
    return entryFailures.get();
  }

  long restoreFailures() {
    return restoreFailures.get();
  }

  private long nextGeneration() {
    long previous;
    do {
      previous = generations.get();
      if (previous >= MAX_GENERATION || previous < 0) {
        return 0;
      }
    } while (!generations.compareAndSet(previous, previous + 1));
    return previous + 1;
  }

  private static void increment(AtomicLong counter) {
    long previous;
    do {
      previous = counter.get();
      if (previous == Long.MAX_VALUE) {
        return;
      }
    } while (!counter.compareAndSet(previous, previous + 1));
  }

  private void invalidate() {
    unverified = true;
    local.remove();
    increment(integrityFailures);
  }

  enum Status {
    NO_SCOPE,
    SYNCHRONOUS_SCOPE,
    SCOPE_LOST
  }

  record Snapshot(Status status, List<Integer> bindings) {
    Snapshot {
      bindings = List.copyOf(bindings);
    }
  }

  private static final class State {
    final int[] bindings;
    final long[] generations;
    int depth;
    boolean lost;

    State(int maxDepth) {
      bindings = new int[maxDepth];
      generations = new long[maxDepth];
    }
  }
}
