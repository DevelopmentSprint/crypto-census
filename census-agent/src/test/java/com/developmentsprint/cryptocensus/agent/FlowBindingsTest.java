package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class FlowBindingsTest {
  @Test
  void repeatDeclarationsReuseIdsEvenAtTheCap() {
    var bindings = new FlowBindings(2);
    assertThat(bindings.register("app:flow:declaration-a")).isEqualTo(1);
    assertThat(bindings.register("app:flow:declaration-b")).isEqualTo(2);
    assertThat(bindings.register("app:flow:declaration-c")).isZero();
    assertThat(bindings.register("app:flow:declaration-a")).isEqualTo(1);
    assertThat(bindings.size()).isEqualTo(2);
    assertThat(bindings.overflowed()).isEqualTo(1);
  }

  @Test
  void invalidKeysCannotConsumeIds() {
    var bindings = new FlowBindings(1);
    assertThatThrownBy(() -> bindings.register(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> bindings.register("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> bindings.register("x".repeat(4097)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(bindings.register("x".repeat(4096))).isEqualTo(1);
    assertThat(bindings.size()).isEqualTo(1);
    assertThatThrownBy(() -> new FlowBindings(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FlowBindings(8193)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void concurrentRetransformationDoesNotMintDuplicateIds() throws Exception {
    var bindings = new FlowBindings(1);
    var executor = Executors.newFixedThreadPool(4);
    try {
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        tasks.add(() -> bindings.register("same-normalized-key"));
      }
      for (var result : executor.invokeAll(tasks)) {
        assertThat(result.get()).isEqualTo(1);
      }
      assertThat(bindings.size()).isEqualTo(1);
      assertThat(bindings.overflowed()).isZero();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void overflowDiagnosticSaturates() throws ReflectiveOperationException {
    var bindings = new FlowBindings(1);
    bindings.register("first");
    Field field = FlowBindings.class.getDeclaredField("overflowed");
    field.setAccessible(true);
    field.setLong(bindings, Long.MAX_VALUE);
    assertThat(bindings.register("second")).isZero();
    assertThat(bindings.overflowed()).isEqualTo(Long.MAX_VALUE);
  }
}
