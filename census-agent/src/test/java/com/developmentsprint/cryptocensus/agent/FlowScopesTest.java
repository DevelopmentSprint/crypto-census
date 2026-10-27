package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FlowScopesTest {
  @Test
  void nestedAndRecursiveScopesRestoreTheirFullPath() {
    var scopes = enabled(4);
    assertThat(scopes.current().status()).isEqualTo(FlowScopes.Status.NO_SCOPE);
    final long outer = scopes.enter(1);
    final long inner = scopes.enter(2);
    long recursive = scopes.enter(2);
    assertThat(scopes.current().bindings()).containsExactly(1, 2, 2);
    scopes.exit(recursive);
    assertThat(scopes.current().bindings()).containsExactly(1, 2);
    scopes.exit(inner);
    assertThat(scopes.current().bindings()).containsExactly(1);
    scopes.exit(outer);
    assertThat(scopes.current().status()).isEqualTo(FlowScopes.Status.NO_SCOPE);
    assertThat(scopes.hasThreadState()).isFalse();
  }

  @Test
  void disabledEntryAndZeroExitNeverPopTheParent() {
    var scopes = new FlowScopes(2);
    assertThat(scopes.enter(1)).isZero();
    scopes.enable();
    final long outer = scopes.enter(1);
    scopes.disable();
    assertThat(scopes.enter(2)).isZero();
    scopes.exit(0);
    assertThat(scopes.current().bindings()).containsExactly(1);
    scopes.exit(outer);
    assertThat(scopes.hasThreadState()).isFalse();
  }

  @Test
  void unknownBindingsAndOverflowNeverFallBackToTheParent() {
    var scopes = enabled(2);
    final long outer = scopes.enter(1);
    final long inner = scopes.enter(2);
    final long overflow = scopes.enter(3);
    long nestedOverflow = scopes.enter(4);
    assertThat(scopes.current().status()).isEqualTo(FlowScopes.Status.SCOPE_LOST);
    assertThat(scopes.current().bindings()).isEmpty();
    scopes.exit(nestedOverflow);
    scopes.exit(overflow);
    scopes.exit(inner);
    assertThat(scopes.current().status()).isEqualTo(FlowScopes.Status.SCOPE_LOST);
    scopes.exit(outer);
    assertThat(scopes.current().status()).isEqualTo(FlowScopes.Status.NO_SCOPE);
    assertThat(scopes.overflowed()).isEqualTo(2);
    assertThat(scopes.hasThreadState()).isFalse();
    for (int id : new int[] {0, -1, FlowScopes.MAX_BINDINGS + 1}) {
      long token = scopes.enter(id);
      assertThat(token).isNotZero();
      assertThat(scopes.current().status()).isEqualTo(FlowScopes.Status.SCOPE_LOST);
      scopes.exit(token);
      assertThat(scopes.current().status()).isEqualTo(FlowScopes.Status.NO_SCOPE);
    }
  }

  @Test
  void staleSiblingTokensInvalidateRatherThanPopNewScopes() {
    var scopes = enabled(2);
    final long outer = scopes.enter(1);
    long oldInner = scopes.enter(2);
    scopes.exit(oldInner);
    long newInner = scopes.enter(3);
    assertThat(oldInner).isNotEqualTo(newInner);
    scopes.exit(oldInner);
    assertThat(scopes.unverified()).isTrue();
    assertThat(scopes.current().status()).isEqualTo(FlowScopes.Status.SCOPE_LOST);
    assertThat(scopes.hasThreadState()).isFalse();
    scopes.exit(newInner);
    scopes.exit(outer);
    assertThat(scopes.integrityFailures()).isEqualTo(3);
  }

  @Test
  void outOfOrderAndMalformedTokensCannotRestoreInventedParents() {
    for (long token : new long[] {1, -1, 1L << 32, (1L << 32) | 3}) {
      var scopes = enabled(2);
      scopes.enter(1);
      scopes.exit(token);
      assertThat(scopes.unverified()).isTrue();
      assertThat(scopes.hasThreadState()).isFalse();
    }
    var scopes = enabled(2);
    final long outer = scopes.enter(1);
    scopes.enter(2);
    scopes.exit(outer);
    assertThat(scopes.hasThreadState()).isFalse();
    assertThat(scopes.current().bindings()).isEmpty();
  }

  @Test
  void entryFailureReturnsNoopAndLeavesNoLeakedThreadState() {
    var firstFails =
        new FlowScopes(
            2,
            () -> {
              throw new OutOfMemoryError("injected");
            },
            null,
            null);
    firstFails.enable();
    assertThat(firstFails.enter(1)).isZero();
    assertThat(firstFails.hasThreadState()).isFalse();
    assertThat(firstFails.entryFailures()).isEqualTo(1);
    assertThat(firstFails.unverified()).isTrue();

    var calls = new AtomicInteger();
    var nestedFails =
        new FlowScopes(
            2,
            () -> {
              if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("injected before commit");
              }
            },
            null,
            null);
    nestedFails.enable();
    final long outer = nestedFails.enter(1);
    assertThat(nestedFails.enter(2)).isZero();
    nestedFails.exit(0);
    assertThat(nestedFails.hasThreadState()).isTrue();
    nestedFails.exit(outer);
    assertThat(nestedFails.hasThreadState()).isFalse();
  }

  @Test
  void restoreFailureInvalidatesTheRunAndRemovesTheMisattributionWindow() {
    var scopes =
        new FlowScopes(
            2,
            null,
            () -> {
              throw new StackOverflowError("injected");
            },
            null);
    scopes.enable();
    final long outer = scopes.enter(1);
    final long inner = scopes.enter(2);
    scopes.exit(inner);
    assertThat(scopes.restoreFailures()).isEqualTo(1);
    assertThat(scopes.hasThreadState()).isFalse();
    assertThat(scopes.current().status()).isEqualTo(FlowScopes.Status.SCOPE_LOST);
    scopes.exit(outer);
    assertThat(scopes.integrityFailures()).isEqualTo(1);
  }

  @Test
  void postRestoreDiagnosticFailureCannotLeaveFrame() {
    var scopes =
        new FlowScopes(
            1,
            null,
            null,
            () -> {
              throw new IllegalStateException("diagnostic");
            });
    scopes.enable();
    scopes.exit(scopes.enter(1));
    assertThat(scopes.hasThreadState()).isFalse();
    assertThat(scopes.restoreFailures()).isEqualTo(1);
    assertThat(scopes.unverified()).isTrue();
  }

  @Test
  void counterOverflowFailsClosedWithoutWrapping() throws ReflectiveOperationException {
    var scopes = enabled(1);
    scopes.enter(1);
    Field localField = FlowScopes.class.getDeclaredField("local");
    localField.setAccessible(true);
    Object state = ((ThreadLocal<?>) localField.get(scopes)).get();
    Field depthField = state.getClass().getDeclaredField("depth");
    depthField.setAccessible(true);
    depthField.setInt(state, Integer.MAX_VALUE);
    assertThat(scopes.enter(1)).isZero();
    assertThat(scopes.hasThreadState()).isFalse();
    assertThat(scopes.unverified()).isTrue();

    var generationsOverflow = enabled(1);
    Field generationsField = FlowScopes.class.getDeclaredField("generations");
    generationsField.setAccessible(true);
    ((AtomicInteger) generationsField.get(generationsOverflow)).set(Integer.MAX_VALUE);
    assertThat(generationsOverflow.enter(1)).isZero();
    assertThat(generationsOverflow.hasThreadState()).isFalse();
    assertThat(generationsOverflow.integrityFailures()).isEqualTo(1);
    assertThat(((AtomicInteger) generationsField.get(generationsOverflow)).get())
        .isEqualTo(Integer.MAX_VALUE);
  }

  @Test
  void reusedWorkerDoesNotInheritOrRetainScopes() throws Exception {
    var scopes = enabled(2);
    long caller = scopes.enter(1);
    var executor = Executors.newSingleThreadExecutor();
    try {
      assertThat(executor.submit(() -> scopes.current().status()).get(10, TimeUnit.SECONDS))
          .isEqualTo(FlowScopes.Status.NO_SCOPE);
      assertThat(
              executor
                  .submit(
                      () -> {
                        long token = scopes.enter(2);
                        try {
                          return scopes.current().bindings();
                        } finally {
                          scopes.exit(token);
                        }
                      })
                  .get(10, TimeUnit.SECONDS))
          .containsExactly(2);
      assertThat(executor.submit(scopes::hasThreadState).get(10, TimeUnit.SECONDS)).isFalse();
    } finally {
      executor.shutdownNow();
      scopes.exit(caller);
    }
  }

  @Test
  void snapshotsOwnTheirPathAndRejectMutation() {
    List<Integer> ids = new ArrayList<>(List.of(1, 2));
    var snapshot = new FlowScopes.Snapshot(FlowScopes.Status.SYNCHRONOUS_SCOPE, ids);
    ids.clear();
    assertThat(snapshot.bindings()).containsExactly(1, 2);
    assertThatThrownBy(() -> snapshot.bindings().add(3))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> new FlowScopes(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FlowScopes(33)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void bridgeUsesPrimitiveEntryExitAndRestoresAfterDisable() {
    FlowBridge.configure(2);
    assertThat(FlowBridge.enter(1)).isZero();
    FlowBridge.scopes().enable();
    long token = FlowBridge.enter(1);
    assertThat(FlowBridge.scopes().current().bindings()).containsExactly(1);
    FlowBridge.scopes().disable();
    FlowBridge.exit(token);
    assertThat(FlowBridge.scopes().hasThreadState()).isFalse();
    FlowBridge.configure(FlowScopes.MAX_DEPTH);
  }

  @Test
  void bridgeRejectsStaleTokensAcrossConfiguredRuns() {
    FlowBridge.configure(2);
    FlowBridge.scopes().enable();
    long old = FlowBridge.enter(1);
    FlowBridge.exit(old);
    FlowBridge.configure(2);
    FlowBridge.scopes().enable();
    assertThat(FlowBridge.enter(2)).isNotEqualTo(old);
    FlowBridge.exit(old);
    assertThat(FlowBridge.scopes().unverified()).isTrue();
    assertThat(FlowBridge.scopes().hasThreadState()).isFalse();
    FlowBridge.configure(FlowScopes.MAX_DEPTH);
  }

  @Test
  void healthCountersSaturateWithoutWrapping() throws ReflectiveOperationException {
    var scopes = enabled(1);
    Field field = FlowScopes.class.getDeclaredField("integrityFailures");
    field.setAccessible(true);
    ((AtomicLong) field.get(scopes)).set(Long.MAX_VALUE);
    scopes.exit(1);
    assertThat(scopes.integrityFailures()).isEqualTo(Long.MAX_VALUE);
    assertThat(scopes.unverified()).isTrue();
  }

  private static FlowScopes enabled(int maxDepth) {
    var scopes = new FlowScopes(maxDepth);
    scopes.enable();
    return scopes;
  }
}
