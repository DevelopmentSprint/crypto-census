package com.developmentsprint.cryptocensus.agent;

import net.bytebuddy.asm.Advice;

/** Separate enrichment advice: suppressed collector errors must never change JSSE behavior. */
// Namespace for advice classes consumed as class files by Byte Buddy.
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
public final class TlsAdvices {
  private TlsAdvices() {}

  /** Captures initiating context after construction. */
  public static final class Created {
    private Created() {}

    /** Records the completed operation and restores dynamic ownership where applicable. */
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object handshake) {
      TlsCollector.created(handshake);
    }
  }

  /** Scopes operation ownership to a JSSE dispatch call. */
  public static final class Active {
    private Active() {}

    /** Captures ownership before the instrumented operation. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object enter(@Advice.This Object handshake) {
      return TlsCollector.enter(handshake);
    }

    /** Records the completed operation and restores dynamic ownership where applicable. */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Object previous) {
      TlsCollector.exit(previous);
    }
  }

  /** Captures the cleared context across successful completion. */
  public static final class Finished {
    private Finished() {}

    /** Captures ownership before the instrumented operation. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Object enter(@Advice.This Object transport) {
      return TlsCollector.handshake(transport);
    }

    /** Records the completed operation and restores dynamic ownership where applicable. */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.This Object transport,
        @Advice.Enter Object handshake,
        @Advice.Thrown Throwable error) {
      if (error == null) {
        TlsCollector.completed(transport, handshake);
      }
    }
  }

  /** Finalizes failed attempts before JSSE clears their context. */
  public static final class Failed {
    private Failed() {}

    /** Captures ownership before the instrumented operation. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This Object transport) {
      TlsCollector.failed(transport);
    }
  }

  /** Finalizes unfinished attempts when their transport closes. */
  public static final class Closed {
    private Closed() {}

    /** Captures ownership before the instrumented operation. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This Object transport) {
      TlsCollector.closed(transport);
    }
  }

  /** Separates local signing from verified peer authentication. */
  public static final class Signature {
    private Signature() {}

    /** Records the completed operation and restores dynamic ownership where applicable. */
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.This Object message,
        @Advice.Argument(0) Object handshake,
        @Advice.Argument(1) Object material) {
      TlsCollector.signature(handshake, message, material instanceof java.nio.ByteBuffer);
    }
  }

  /** Captures object-owned session lineage without ticket bytes. */
  public static final class Ticket {
    private Ticket() {}

    /** Records the completed operation and restores dynamic ownership where applicable. */
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object session, @Advice.Argument(0) Object parent) {
      TlsCollector.ticket(session, parent);
    }
  }
}
