package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class FlowVisibilityTest {
  @Test
  void parentDelegationResolvesTheInstalledBridge() {
    ClassLoader installed = FlowBridge.class.getClassLoader();
    assertThat(FlowVisibility.resolvesBridge(new ClassLoader(installed) {})).isTrue();
    assertThat(FlowVisibility.resolvesBridge(null)).isFalse();
    assertThat(FlowVisibility.resolvesBridge(ClassLoader.getPlatformClassLoader())).isFalse();
  }

  @Test
  void nonDelegatingLoaderIsRejectedBeforeInstrumentation() {
    ClassLoader hidden =
        new ClassLoader(FlowBridge.class.getClassLoader()) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(FlowBridge.class.getName())) {
              throw new ClassNotFoundException("Explicit bridge delegation is not configured");
            }
            return super.loadClass(name, resolve);
          }
        };
    assertThat(FlowVisibility.resolvesBridge(hidden)).isFalse();
  }

  @Test
  void sameNamedPrivateCopyIsNotTheInstalledBridge() throws IOException {
    final byte[] bytes;
    try (var input = FlowBridge.class.getResourceAsStream("FlowBridge.class")) {
      bytes = Objects.requireNonNull(input).readAllBytes();
    }
    ClassLoader shadow =
        new ClassLoader(FlowBridge.class.getClassLoader()) {
          @Override
          protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(FlowBridge.class.getName())) {
              return defineClass(name, bytes, 0, bytes.length);
            }
            return super.loadClass(name, resolve);
          }
        };
    assertThat(FlowVisibility.resolvesBridge(shadow)).isFalse();
  }

  @Test
  void linkingAndAccessFailuresAreUnavailable() {
    for (Throwable failure : new Throwable[] {new LinkageError(), new SecurityException()}) {
      ClassLoader failing =
          new ClassLoader(FlowBridge.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) {
              if (failure instanceof LinkageError error) {
                throw error;
              }
              throw (SecurityException) failure;
            }
          };
      assertThat(FlowVisibility.resolvesBridge(failing)).isFalse();
    }
  }
}
