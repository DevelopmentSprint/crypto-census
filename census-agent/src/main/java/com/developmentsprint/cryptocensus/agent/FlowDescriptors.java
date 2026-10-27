package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.LinkedHashSet;
import java.util.Set;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.jar.asm.AnnotationVisitor;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;

/** Descriptor-only prefilter preserves visibility of annotations whose API types are missing. */
final class FlowDescriptors {
  private static final ThreadLocal<Set<String>> ACTIVE = new ThreadLocal<>();

  private FlowDescriptors() {}

  static Set<String> active() {
    return ACTIVE.get();
  }

  static ResettableClassFileTransformer decorate(ResettableClassFileTransformer delegate) {
    return new ResettableClassFileTransformer.WithDelegation(delegate) {
      @Override
      public byte[] transform(
          ClassLoader loader,
          String name,
          Class<?> redefined,
          ProtectionDomain domain,
          byte[] bytes)
          throws IllegalClassFormatException {
        return transform(null, loader, name, redefined, domain, bytes);
      }

      @Override
      @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
          value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS",
          justification =
              "ClassFileTransformer defines null as unchanged bytes; empty bytes corrupt the class")
      // ClassFileTransformer requires null for unchanged bytes; an empty array is invalid bytecode.
      @SuppressWarnings({
        "PMD.AvoidCatchingGenericException",
        "PMD.ReturnEmptyCollectionRatherThanNull"
      })
      public byte[] transform(
          Module module,
          ClassLoader loader,
          String name,
          Class<?> redefined,
          ProtectionDomain domain,
          byte[] bytes)
          throws IllegalClassFormatException {
        if (loader == null
            || name == null
            || FlowInstrumentation.ignoredName(name.replace('/', '.'))) {
          return null;
        }
        Set<String> previous = ACTIVE.get();
        try {
          if (bytes.length > CanonicalJson.MAX_BYTES) {
            AgentEnrichment.diagnostic(EvidenceDocument.Counter.TRANSFORM_ERRORS);
            return null;
          }
          Set<String> methods = scan(bytes);
          if (methods.isEmpty()) {
            return null;
          }
          ACTIVE.set(methods);
          return module == null
              ? delegate.transform(loader, name, redefined, domain, bytes)
              : delegate.transform(module, loader, name, redefined, domain, bytes);
        } catch (Throwable failed) {
          AgentEnrichment.diagnostic(EvidenceDocument.Counter.TRANSFORM_ERRORS);
          return null;
        } finally {
          if (previous == null) {
            ACTIVE.remove();
          } else {
            ACTIVE.set(previous);
          }
        }
      }
    };
  }

  static Set<String> scan(byte[] bytes) {
    Set<String> methods = new LinkedHashSet<>();
    new ClassReader(bytes)
        .accept(
            new ClassVisitor(Opcodes.ASM9) {
              @Override
              public MethodVisitor visitMethod(
                  int access,
                  String name,
                  String descriptor,
                  String signature,
                  String[] exceptions) {
                if ((access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0) {
                  return null;
                }
                boolean unsupported =
                    (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                        || name.startsWith("<");
                return new MethodVisitor(Opcodes.ASM9) {
                  @Override
                  public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    if (annotation.equals("L" + FlowDeclarations.FLOW.replace('.', '/') + ";")
                        || annotation.equals(
                            "L" + FlowDeclarations.PROTECTION.replace('.', '/') + ";")) {
                      if (unsupported) {
                        AgentEnrichment.diagnostic(EvidenceDocument.Counter.TRANSFORM_ERRORS);
                      } else {
                        methods.add(name + descriptor);
                      }
                    }
                    return null;
                  }
                };
              }
            },
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return Set.copyOf(methods);
  }
}
