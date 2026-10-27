package com.developmentsprint.cryptocensus.agent;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FlowTransformerLifecycleTest {
  @AfterEach
  void reset() {
    AgentEnrichment.configure(AgentConfig.parse(""));
    Recorder.disable();
  }

  @Test
  void decoratedResetRemovesTheRegisteredWrapperAndRetransformsMatchingClasses() {
    var state = new State(false);
    ResettableClassFileTransformer wrapper =
        (ResettableClassFileTransformer)
            new AgentBuilder.Default()
                .disableClassFormatChanges()
                .ignore(none())
                .type(named(Fixture.class.getName()))
                .transform((builder, type, loader, module, domain) -> builder)
                .with((AgentBuilder.TransformerDecorator) FlowDescriptors::decorate)
                .makeRaw();
    wrapper = FlowDescriptors.decorate(wrapper);
    state.active.add(wrapper);
    assertThat(wrapper).isInstanceOf(ResettableClassFileTransformer.WithDelegation.class);
    assertThat(
            wrapper.reset(
                state.instrumentation(), AgentBuilder.RedefinitionStrategy.RETRANSFORMATION))
        .isTrue();
    assertThat(state.removed).containsExactly(wrapper);
    assertThat(state.active).isEmpty();
    assertThat(state.retransformed).containsExactly(Fixture.class);
  }

  @Test
  void failedEnrichmentInstallationCannotRemoveTheExistingCoreTransformer() {
    AgentEnrichment.configure(AgentConfig.parse("enrichment=true,applicationId=unit"));
    Recorder.enable();
    var state = new State(true);
    ClassFileTransformer core = new ClassFileTransformer() {};
    state.active.add(core);
    assertThat(FlowInstrumentation.install(state.instrumentation(), ClassFileLocator.NoOp.INSTANCE))
        .isFalse();
    assertThat(state.added).hasSize(1);
    assertThat(state.added.get(0))
        .isInstanceOf(ResettableClassFileTransformer.WithDelegation.class);
    assertThat(state.active).containsExactly(core);
    int admitted = Recorder.enter();
    assertThat(admitted).isEqualTo(1);
    Recorder.exit(
        admitted, null, null, null, new IllegalStateException("expected application outcome"));
    assertThat(FlowBridge.enter(1)).isZero();
  }

  private static final class State {
    final List<ClassFileTransformer> active = new ArrayList<>();
    final List<ClassFileTransformer> added = new ArrayList<>();
    final List<ClassFileTransformer> removed = new ArrayList<>();
    final List<Class<?>> retransformed = new ArrayList<>();
    final boolean failDiscovery;

    State(boolean failDiscovery) {
      this.failDiscovery = failDiscovery;
    }

    Instrumentation instrumentation() {
      return (Instrumentation)
          Proxy.newProxyInstance(
              Instrumentation.class.getClassLoader(),
              new Class<?>[] {Instrumentation.class},
              (proxy, method, args) -> {
                switch (method.getName()) {
                  case "addTransformer" -> {
                    var transformer = (ClassFileTransformer) args[0];
                    active.add(transformer);
                    added.add(transformer);
                    return null;
                  }
                  case "removeTransformer" -> {
                    var transformer = (ClassFileTransformer) args[0];
                    removed.add(transformer);
                    return active.remove(transformer);
                  }
                  case "getAllLoadedClasses" -> {
                    if (failDiscovery) {
                      throw new IllegalStateException(
                          "injected discovery failure after registration");
                    }
                    return new Class<?>[] {Fixture.class};
                  }
                  case "retransformClasses" -> {
                    retransformed.addAll(List.of((Class<?>[]) args[0]));
                    return null;
                  }
                  default -> {
                    if (method.getReturnType() == boolean.class) {
                      return true;
                    }
                    return null;
                  }
                }
              });
    }
  }

  public static class Fixture {}
}
