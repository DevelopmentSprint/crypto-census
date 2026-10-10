package com.developmentsprint.cryptocensus.agent;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice inlined into JCA classes. Each advice is a thin trampoline into {@link
 * Recorder}; all logic lives there so that the inlined bytecode stays small and failure-proof.
 *
 * <p>Every advice uses {@code suppress = Throwable.class}, and the recorder never mutates arguments
 * or return values: the agent observes, it never changes application behavior.
 */
// A namespace for advice classes, which Byte Buddy reads as class files; it has no members of its
// own.
@SuppressWarnings("PMD.MissingStaticMethodInNonInstantiatableClass")
final class Advices {

  private Advices() {}

  /** {@code Cipher.init(int opmode, Key|Certificate key, ...)}: records the operation mode too. */
  static final class CipherInit {
    private CipherInit() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int enter() {
      return Recorder.enter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Advice.Enter int depth,
        @Advice.This Object self,
        @Advice.Argument(0) int opmode,
        @Advice.Argument(1) Object key,
        @Advice.Thrown Throwable thrown) {
      Recorder.exit(depth, self, key, opmode, thrown);
    }
  }

  /**
   * Instance methods whose first argument is the key, e.g. {@code Signature.initSign(PrivateKey)}.
   * The method name tells sign from verify.
   */
  static final class KeyArg0 {
    private KeyArg0() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int enter() {
      return Recorder.enter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Advice.Enter int depth,
        @Advice.This Object self,
        @Advice.Argument(0) Object key,
        @Advice.Origin("#m") String method,
        @Advice.Thrown Throwable thrown) {
      Recorder.exit(depth, self, key, method, thrown);
    }
  }

  /**
   * Instance methods that produce the key: {@code KeyGenerator.generateKey()}, {@code
   * KeyPairGenerator.generateKeyPair()}.
   */
  static final class ReturnsKey {
    private ReturnsKey() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int enter() {
      return Recorder.enter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Advice.Enter int depth,
        @Advice.This Object self,
        @Advice.Return Object key,
        @Advice.Thrown Throwable thrown) {
      Recorder.exit(depth, self, key, null, thrown);
    }
  }

  /**
   * Generators with JDK provider failover: {@code KeyGenerator.generateKey()} and {@code
   * KeyPairGenerator$Delegate.generateKeyPair()}. Their {@code getProvider()} disables failover, so
   * the advice reads the {@code provider} field directly and passes it on.
   */
  static final class ReturnsKeyWithProvider {
    private ReturnsKeyWithProvider() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int enter() {
      return Recorder.enter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Advice.Enter int depth,
        @Advice.This Object self,
        @Advice.Return Object key,
        @Advice.FieldValue("provider") Object provider,
        @Advice.Thrown Throwable thrown) {
      Recorder.exit(depth, self, key, provider, thrown);
    }
  }

  /**
   * Constructors where the constructed object is the asset: {@code SSLSessionImpl(...)}.
   *
   * <p>Byte Buddy cannot run exit advice on constructors that throw, so there is no enter/exit
   * depth pairing here; {@link Recorder#constructed} guards nesting and constructor chaining
   * itself.
   */
  static final class Constructed {
    private Constructed() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    static void exit(@Advice.This Object self) {
      Recorder.constructed(self);
    }
  }

  /** Static factories whose result is the asset: {@code MessageDigest.getInstance(...)}. */
  static final class StaticFactory {
    private StaticFactory() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int enter() {
      return Recorder.enter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    static void exit(
        @Advice.Enter int depth, @Advice.Return Object instance, @Advice.Thrown Throwable thrown) {
      Recorder.exit(depth, instance, null, null, thrown);
    }
  }
}
