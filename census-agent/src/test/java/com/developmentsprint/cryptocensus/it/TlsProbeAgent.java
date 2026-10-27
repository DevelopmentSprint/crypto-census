package com.developmentsprint.cryptocensus.it;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/** Test-only agent proving object-owned SunJSSE handshake hooks without changing Census. */
public final class TlsProbeAgent {
  private TlsProbeAgent() {}

  /** Installs the probe bridge from the supplied test jar. */
  public static void premain(String bridgeJar, Instrumentation instrumentation) throws Exception {
    instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(bridgeJar));
    new AgentBuilder.Default()
        .disableClassFormatChanges()
        .ignore(named(TlsProbeAgent.class.getName()))
        .type(named("sun.security.ssl.TransportContext"))
        .transform(
            (builder, type, loader, module, domain) ->
                builder
                    .visit(Advice.to(Finished.class).on(named("finishHandshake")))
                    .visit(Advice.to(Failed.class).on(named("fatal").and(takesArguments(4)))))
        .type(named("sun.security.ssl.CertificateVerify$T13CertificateVerifyMessage"))
        .transform(
            (builder, type, loader, module, domain) ->
                builder.visit(Advice.to(Signature.class).on(isConstructor())))
        .type(named("sun.security.ssl.SSLSessionImpl"))
        .transform(
            (builder, type, loader, module, domain) ->
                builder.visit(
                    Advice.to(Ticket.class)
                        .on(
                            isConstructor()
                                .and(takesArguments(2))
                                .and(takesArgument(0, named("sun.security.ssl.SSLSessionImpl")))
                                .and(takesArgument(1, named("sun.security.ssl.SessionId"))))))
        .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
        .installOn(instrumentation);
  }

  /** Captures the handshake before JSSE clears it; reports only successful completion. */
  public static final class Finished {
    private Finished() {}

    @Advice.OnMethodEnter
    public static Object enter(@Advice.This Object transport) throws Exception {
      return TlsProbeBridge.handshake(transport);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.This Object transport,
        @Advice.Enter Object handshake,
        @Advice.Thrown Throwable thrown)
        throws Exception {
      if (thrown == null) {
        TlsProbeBridge.completed(transport, handshake);
      }
    }
  }

  /** Failure capture occurs before JSSE destroys the handshake context. */
  public static final class Failed {
    private Failed() {}

    @Advice.OnMethodEnter
    public static void enter(@Advice.This Object transport) throws Exception {
      TlsProbeBridge.failed(transport);
    }
  }

  /** Successful constructor exit distinguishes locally produced and verified peer signatures. */
  public static final class Signature {
    private Signature() {}

    @Advice.OnMethodExit
    public static void exit(
        @Advice.This Object message,
        @Advice.Argument(0) Object handshake,
        @Advice.Argument(1) Object material)
        throws Exception {
      TlsProbeBridge.signature(handshake, message, material instanceof java.nio.ByteBuffer);
    }
  }

  /** Ticket-derived sessions are observations, never completed handshakes. */
  public static final class Ticket {
    private Ticket() {}

    @Advice.OnMethodExit
    public static void exit(@Advice.This Object session, @Advice.Argument(0) Object parent) {
      TlsProbeBridge.ticket(session, parent);
    }
  }
}
