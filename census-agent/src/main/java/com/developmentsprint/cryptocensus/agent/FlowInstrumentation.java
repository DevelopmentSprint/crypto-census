package com.developmentsprint.cryptocensus.agent;

import static net.bytebuddy.matcher.ElementMatchers.is;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/** Annotation transformer and listener, deliberately separate from the core JCA target tracker. */
final class FlowInstrumentation {
  private FlowInstrumentation() {}

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  static boolean install(Instrumentation instrumentation, ClassFileLocator locator) {
    var registered = new AtomicReference<ResettableClassFileTransformer>();
    try {
      new AgentBuilder.Default()
          .disableClassFormatChanges()
          .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
          .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
          .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
          .with(AgentBuilder.DescriptionStrategy.Default.POOL_FIRST)
          .with((AgentBuilder.TransformerDecorator) FlowDescriptors::decorate)
          .with(
              new AgentBuilder.Listener.Adapter() {
                @Override
                public void onError(
                    String typeName,
                    ClassLoader loader,
                    JavaModule module,
                    boolean loaded,
                    Throwable failure) {
                  AgentEnrichment.annotationDiagnostic(
                      AnnotationDiagnostics.Reason.TRANSFORM_FAILED, typeName, "", "");
                }
              })
          .with(
              new AgentBuilder.InstallationListener.Adapter() {
                @Override
                public void onBeforeInstall(
                    Instrumentation inst, ResettableClassFileTransformer transformer) {
                  registered.set(transformer);
                }
              })
          .ignore((type, loader, module, redefined, domain) -> ignored(type, loader))
          .type((type, loader, module, redefined, domain) -> true)
          .transform(
              (builder, type, loader, module, domain) ->
                  transform(builder, type, loader, domain, locator))
          .assureReadEdgeTo(instrumentation, FlowBridge.class)
          .installOn(instrumentation);
      AgentEnrichment.installed();
      return true;
    } catch (Throwable failure) {
      AgentEnrichment.annotationDiagnostic(AnnotationDiagnostics.Reason.INSTALL_FAILED, "", "", "");
      FlowBridge.scopes().disable();
      ResettableClassFileTransformer transformer = registered.get();
      if (transformer != null) {
        instrumentation.removeTransformer(transformer);
      }
      return false;
    }
  }

  static boolean ignored(TypeDescription type, ClassLoader loader) {
    return loader == null || type.isSynthetic() || ignoredName(type.getName());
  }

  static boolean ignoredName(String name) {
    return name.startsWith("java.")
        || name.startsWith("jdk.")
        || name.startsWith("sun.")
        || name.startsWith("com.sun.")
        || name.startsWith("net.bytebuddy.")
        || name.startsWith("com.developmentsprint.cryptocensus.agent.");
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  static DynamicType.Builder<?> transform(
      DynamicType.Builder<?> builder,
      TypeDescription type,
      ClassLoader loader,
      ProtectionDomain domain,
      ClassFileLocator locator) {
    if (!FlowVisibility.resolvesBridge(loader)) {
      AgentEnrichment.annotationDiagnostic(
          AnnotationDiagnostics.Reason.BRIDGE_UNAVAILABLE, type.getName(), "", "");
      return builder;
    }
    DynamicType.Builder<?> result = builder;
    var methods = FlowDescriptors.active();
    if (methods == null) {
      AgentEnrichment.annotationDiagnostic(
          AnnotationDiagnostics.Reason.DESCRIPTORS_UNAVAILABLE, type.getName(), "", "");
      return builder;
    }
    for (MethodDescription method : type.getDeclaredMethods()) {
      if (methods.contains(method.getName() + method.getDescriptor())) {
        int binding = 0;
        try {
          binding = AgentEnrichment.binding(method, loader, domain);
        } catch (Throwable invalid) {
          AgentEnrichment.annotationDiagnostic(
              AnnotationDiagnostics.Reason.INVALID_OR_UNRESOLVED_DECLARATION,
              type.getName(),
              method.getName(),
              method.getDescriptor());
        }
        result =
            result.visit(
                Advice.withCustomMapping()
                    .bind(FlowAdvice.BindingId.class, binding)
                    .to(FlowAdvice.class, locator)
                    .on(is(method)));
      }
    }
    return result;
  }
}
