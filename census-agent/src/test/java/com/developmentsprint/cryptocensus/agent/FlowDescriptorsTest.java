package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;
import org.junit.jupiter.api.Test;

class FlowDescriptorsTest {
  @Test
  void rawDescriptorsDoNotRequireLoadingAnnotationDefinitions() throws IOException {
    byte[] bytes = fixture();
    assertThat(FlowDescriptors.scan(bytes)).contains("archive()V", "orphan()V", "badId()V");
    assertThat(FlowDescriptors.scan(bytes)).doesNotContain("unannotated()V", "<init>()V");
    ClassWriter writer = new ClassWriter(0);
    writer.visit(
        Opcodes.V17,
        Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
        "fixture/Abstract",
        null,
        "java/lang/Object",
        null);
    for (int modifier :
        new int[] {
          Opcodes.ACC_ABSTRACT, Opcodes.ACC_NATIVE, Opcodes.ACC_BRIDGE, Opcodes.ACC_SYNTHETIC
        }) {
      var method =
          writer.visitMethod(
              Opcodes.ACC_PUBLIC | modifier, "ignored" + modifier, "()V", null, null);
      method.visitAnnotation("L" + FlowDeclarations.FLOW.replace('.', '/') + ";", true).visitEnd();
      method.visitEnd();
    }
    writer.visitEnd();
    assertThat(FlowDescriptors.scan(writer.toByteArray())).isEmpty();
  }

  @Test
  void actualBytesAreDelegatedWithTransientSignaturesAndRestoredOnFailure() throws Exception {
    var calls = new AtomicInteger();
    var delegate =
        (ResettableClassFileTransformer)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ResettableClassFileTransformer.class},
                (proxy, method, args) -> {
                  assertThat(method.getName()).isEqualTo("transform");
                  assertThat(FlowDescriptors.active()).contains("archive()V");
                  if (calls.incrementAndGet() == 3) {
                    throw new IllegalStateException("injected delegate failure");
                  }
                  return new byte[] {1};
                });
    var wrapper = FlowDescriptors.decorate(delegate);
    byte[] bytes = fixture();
    assertThat(
            wrapper.transform(getClass().getClassLoader(), "fixture/Declared", null, null, bytes))
        .containsExactly((byte) 1);
    assertThat(FlowDescriptors.active()).isNull();
    assertThat(
            wrapper.transform(
                getClass().getModule(),
                getClass().getClassLoader(),
                "fixture/Declared",
                null,
                null,
                bytes))
        .containsExactly((byte) 1);
    assertThat(FlowDescriptors.active()).isNull();
    assertThat(
            wrapper.transform(getClass().getClassLoader(), "fixture/Declared", null, null, bytes))
        .isNull();
    assertThat(FlowDescriptors.active()).isNull();
    assertThat(calls.get()).isEqualTo(3);
  }

  @Test
  void ignoredMalformedAndOversizedInputsNeverReachMetadataParsing() throws Exception {
    var calls = new AtomicInteger();
    var delegate =
        (ResettableClassFileTransformer)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {ResettableClassFileTransformer.class},
                (proxy, method, args) -> {
                  calls.incrementAndGet();
                  throw new AssertionError("Must not delegate");
                });
    var wrapper = FlowDescriptors.decorate(delegate);
    assertThat(wrapper.transform(null, "fixture/Type", null, null, new byte[0])).isNull();
    assertThat(wrapper.transform(getClass().getClassLoader(), null, null, null, new byte[0]))
        .isNull();
    for (String ignored :
        new String[] {
          "java/Type",
          "jdk/Type",
          "sun/Type",
          "com/sun/Type",
          "net/bytebuddy/Type",
          "com/developmentsprint/cryptocensus/agent/Type"
        }) {
      assertThat(wrapper.transform(getClass().getClassLoader(), ignored, null, null, new byte[0]))
          .isNull();
    }
    assertThat(
            wrapper.transform(getClass().getClassLoader(), "fixture/Type", null, null, new byte[0]))
        .isNull();
    assertThat(
            wrapper.transform(
                getClass().getClassLoader(),
                "fixture/Type",
                null,
                null,
                new byte[CanonicalJson.MAX_BYTES + 1]))
        .isNull();
    assertThat(FlowDescriptors.active()).isNull();
    assertThat(calls.get()).isZero();
  }

  private static byte[] fixture() throws IOException {
    try (var input =
        FlowDeclarationsTest.Declarations.class.getResourceAsStream(
            "FlowDeclarationsTest$Declarations.class")) {
      return Objects.requireNonNull(input).readAllBytes();
    }
  }
}
