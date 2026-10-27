package com.developmentsprint.cryptocensus.agent;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.developmentsprint.cryptocensus.core.EvidenceDocument.Capability;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.CapabilityStatus;
import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/** Optional TLS transformer with health and rollback independent of the core inventory agent. */
public final class TlsInstrumentation {
  private static final String JSSE = TlsSunJsse.PREFIX;
  private static final Map<String, String> HEALTH = new ConcurrentHashMap<>();
  private static final Set<String> TARGETS =
      Set.of(
          JSSE + "TransportContext",
          JSSE + "HandshakeContext",
          JSSE + "ClientHandshakeContext",
          JSSE + "ServerHandshakeContext",
          JSSE + "CertificateVerify$T13CertificateVerifyMessage",
          JSSE + "SSLSessionImpl");

  private TlsInstrumentation() {}

  /** Installs separately; failure disables only TLS enrichment. */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT",
      justification =
          "Byte Buddy 1.18.14 reset removes the supplied transformer and retransforms classes;"
              + " verified by decorated-reset regression")
  public static boolean install(Instrumentation instrumentation, ClassFileLocator locator) {
    AtomicReference<ResettableClassFileTransformer> registered = new AtomicReference<>();
    TARGETS.forEach(name -> HEALTH.put(name, "unavailable"));
    try {
      instrumentation.redefineModule(
          Object.class.getModule(),
          Set.of(),
          Map.of(),
          Map.of("sun.security.ssl", Set.of(TlsSunJsse.class.getModule())),
          Set.of(),
          Map.of());
      checkShape();
      new AgentBuilder.Default()
          .disableClassFormatChanges()
          .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
          .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
          .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
          .with(AgentBuilder.DescriptionStrategy.Default.POOL_FIRST)
          .ignore(nameStartsWith("com.developmentsprint.cryptocensus.agent."))
          .with(new HealthListener())
          .with(
              new AgentBuilder.InstallationListener.Adapter() {
                @Override
                public void onBeforeInstall(
                    Instrumentation inst, ResettableClassFileTransformer transformer) {
                  registered.set(transformer);
                }
              })
          .type(named(JSSE + "TransportContext"))
          .transform(
              (builder, type, loader, module, domain) ->
                  builder
                      .visit(
                          advice(
                              locator,
                              TlsAdvices.Finished.class,
                              named("finishHandshake").and(takesArguments(0))))
                      .visit(
                          advice(
                              locator,
                              TlsAdvices.Failed.class,
                              named("fatal").and(takesArguments(4))))
                      .visit(
                          advice(
                              locator,
                              TlsAdvices.Closed.class,
                              named("closeInbound")
                                  .or(named("closeOutbound"))
                                  .and(takesArguments(0)))))
          .type(named(JSSE + "HandshakeContext"))
          .transform(
              (builder, type, loader, module, domain) ->
                  builder
                      .visit(
                          advice(
                              locator,
                              TlsAdvices.Created.class,
                              isConstructor().and(takesArguments(2))))
                      .visit(
                          advice(
                              locator,
                              TlsAdvices.Active.class,
                              named("dispatch")
                                  .and(takesArguments(2))
                                  .and(takesArgument(1, java.nio.ByteBuffer.class)))))
          .type(named(JSSE + "ClientHandshakeContext").or(named(JSSE + "ServerHandshakeContext")))
          .transform(
              (builder, type, loader, module, domain) ->
                  builder.visit(
                      advice(
                          locator,
                          TlsAdvices.Active.class,
                          named("kickstart").and(takesArguments(0)))))
          .type(named(JSSE + "CertificateVerify$T13CertificateVerifyMessage"))
          .transform(
              (builder, type, loader, module, domain) ->
                  builder.visit(
                      advice(
                          locator,
                          TlsAdvices.Signature.class,
                          isConstructor().and(takesArguments(2)))))
          .type(named(JSSE + "SSLSessionImpl"))
          .transform(
              (builder, type, loader, module, domain) ->
                  builder.visit(
                      advice(
                          locator,
                          TlsAdvices.Ticket.class,
                          isConstructor()
                              .and(takesArguments(2))
                              .and(takesArgument(0, named(JSSE + "SSLSessionImpl")))
                              .and(takesArgument(1, named(JSSE + "SessionId"))))))
          .assureReadEdgeTo(instrumentation, TlsCollector.class)
          .installOn(instrumentation);
      if (HEALTH.values().stream().anyMatch(value -> !"supported".equals(value))) {
        throw new IllegalStateException("TLS hook unavailable");
      }
      TlsCollector.enable();
      return true;
    } catch (Throwable failure) {
      TlsCollector.installationFailed();
      ResettableClassFileTransformer transformer = registered.get();
      if (transformer != null) {
        try {
          transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        } catch (Throwable rollbackFailure) {
          HEALTH.replaceAll((name, status) -> "rollback-failed");
        }
      }
      HEALTH.replaceAll(
          (name, status) -> "rollback-failed".equals(status) ? status : "unavailable");
      return false;
    }
  }

  /** Structural hook outcomes, deliberately separate from primary inventory target health. */
  public static Map<String, String> hookHealth() {
    return Map.copyOf(new LinkedHashMap<>(HEALTH));
  }

  /** Separates runtime support from individual handshake outcomes and unavailable protocols. */
  public static List<Capability> capabilities() {
    boolean supported = !HEALTH.isEmpty() && HEALTH.values().stream().allMatch("supported"::equals);
    CapabilityStatus status = supported ? CapabilityStatus.SUPPORTED : CapabilityStatus.UNAVAILABLE;
    if (supported && TlsCollector.recordingFailures() > 0) {
      status = CapabilityStatus.PARTIAL;
    }
    return List.of(
        new Capability(
            "tls-sunjsse-1.3-correlation",
            status,
            supported
                ? "Verified object-owned socket/engine hooks; " + System.getProperty("java.version")
                : "TLS hooks unavailable or disabled"),
        new Capability(
            "tls-sunjsse-1.2-selection",
            supported ? CapabilityStatus.PARTIAL : CapabilityStatus.UNAVAILABLE,
            "Completion and resumption only; selected group and CertificateVerify scheme"
                + " unobserved"),
        new Capability(
            "tls-psk-lineage",
            supported ? CapabilityStatus.PARTIAL : CapabilityStatus.UNAVAILABLE,
            "In-process ticket/session identity only; stateless or restored origins may be"
                + " unknown"),
        new Capability(
            "tls-other-providers",
            CapabilityStatus.UNAVAILABLE,
            "No verified provider adapter installed"));
  }

  private static net.bytebuddy.asm.AsmVisitorWrapper advice(
      ClassFileLocator locator,
      Class<?> advice,
      ElementMatcher<? super MethodDescription> matcher) {
    return Advice.to(advice, locator).on(matcher);
  }

  private static void checkShape() throws ReflectiveOperationException {
    Class<?> transport = Class.forName(JSSE + "TransportContext", false, null);
    Class<?> handshake = Class.forName(JSSE + "HandshakeContext", false, null);
    List<java.lang.reflect.AccessibleObject> members = new java.util.ArrayList<>();
    for (String name : ListFields.TRANSPORT) {
      members.add(transport.getDeclaredField(name));
    }
    for (String name : ListFields.HANDSHAKE) {
      members.add(handshake.getDeclaredField(name));
    }
    members.add(transport.getDeclaredMethod("finishHandshake"));
    members.add(transport.getDeclaredMethod("closeInbound"));
    members.add(transport.getDeclaredMethod("closeOutbound"));
    members.add(
        transport.getDeclaredMethod(
            "fatal",
            Class.forName(JSSE + "Alert", false, null),
            String.class,
            boolean.class,
            Throwable.class));
    members.add(
        handshake.getDeclaredConstructor(
            Class.forName(JSSE + "SSLContextImpl", false, null), transport));
    Class<?> signature =
        Class.forName(JSSE + "CertificateVerify$T13CertificateVerifyMessage", false, null);
    members.add(signature.getDeclaredConstructor(handshake, java.nio.ByteBuffer.class));
    members.add(
        signature.getDeclaredConstructor(
            handshake, Class.forName(JSSE + "X509Authentication$X509Possession", false, null)));
    members.add(signature.getDeclaredField("signatureScheme"));
    Class<?> session = Class.forName(JSSE + "SSLSessionImpl", false, null);
    members.add(
        session.getDeclaredConstructor(session, Class.forName(JSSE + "SessionId", false, null)));
    members.add(
        Class.forName(JSSE + "ClientHandshakeContext", false, null).getDeclaredMethod("kickstart"));
    members.add(
        Class.forName(JSSE + "ServerHandshakeContext", false, null).getDeclaredMethod("kickstart"));
    members.add(handshake.getDeclaredMethod("dispatch", byte.class, java.nio.ByteBuffer.class));
    members.add(
        Class.forName(JSSE + "NamedGroup", false, null).getDeclaredMethod("nameOf", int.class));
    members.add(
        Class.forName(JSSE + "KeyShareExtension$SHKeyShareSpec", false, null)
            .getDeclaredField("serverShare"));
    members.add(
        Class.forName(JSSE + "KeyShareExtension$KeyShareEntry", false, null)
            .getDeclaredField("namedGroupId"));
    members.add(
        Class.forName(JSSE + "SSLConfiguration", false, null).getDeclaredField("isClientMode"));
    members.add(Class.forName(JSSE + "SignatureScheme", false, null).getDeclaredField("name"));
    for (java.lang.reflect.AccessibleObject member : members) {
      if (!member.trySetAccessible()) {
        throw new IllegalAccessException("TLS hook inaccessible");
      }
    }
  }

  private static final class ListFields {
    private static final String[] TRANSPORT = {
      "handshakeContext", "isNegotiated", "conSession", "sslConfig", "transport"
    };
    private static final String[] HANDSHAKE = {
      "conContext", "handshakeExtensions", "isResumption", "resumingSession"
    };
  }

  private static final class HealthListener extends AgentBuilder.Listener.Adapter {
    @Override
    public void onTransformation(
        TypeDescription type,
        ClassLoader loader,
        JavaModule module,
        boolean loaded,
        DynamicType dynamicType) {
      HEALTH.put(type.getName(), "supported");
    }

    @Override
    public void onError(
        String name, ClassLoader loader, JavaModule module, boolean loaded, Throwable error) {
      if (TARGETS.contains(name)) {
        HEALTH.put(name, "transform-error");
      }
    }
  }
}
