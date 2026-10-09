package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.Occurrence;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * Attributes a JCA call to the application code that caused it.
 *
 * <p>Walks the stack outward, skipping the agent, the JCA facades and provider implementations. The
 * first remaining platform frame tells us which subsystem the call came through (TLS, jar
 * verification, certificate path validation, secure random); the first non-platform frame is the
 * application call site.
 *
 * <p>"Platform" is decided by class loader (bootstrap or platform loader) when the class is known,
 * not by package name: a third-party library in a {@code javax.*} package is application code.
 */
final class Callsites {

  private static final StackWalker WALKER =
      StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
  private static final ClassLoader PLATFORM = ClassLoader.getPlatformClassLoader();

  private Callsites() {}

  /** One stack frame. {@code type} is null when only the name is known (tests). */
  static final class Frame {
    final String className;
    final String methodName;
    final int line;
    final Class<?> type;

    Frame(String className, String methodName, int line) {
      this(className, methodName, line, null);
    }

    Frame(String className, String methodName, int line, Class<?> type) {
      this.className = className;
      this.methodName = methodName;
      this.line = line;
      this.type = type;
    }
  }

  static Occurrence current() {
    return WALKER.walk(
        frames ->
            attribute(
                frames
                    .map(
                        f ->
                            new Frame(
                                f.getClassName(),
                                f.getMethodName(),
                                f.getLineNumber(),
                                f.getDeclaringClass()))
                    .iterator()));
  }

  static Occurrence attribute(Stream<Frame> frames) {
    return attribute(frames.iterator());
  }

  static Occurrence attribute(Iterator<Frame> frames) {
    String via = null;
    while (frames.hasNext()) {
      Frame f = frames.next();
      String c = f.className;
      if (isAgent(c)) {
        continue;
      }
      if (via == null && isSecureRandom(c)) {
        via = "secure-random";
      }
      if (isCryptoLayer(c)) {
        continue;
      }
      if (isPlatform(f)) {
        if (via == null) via = subsystem(c);
        continue;
      }
      return new Occurrence(c, f.methodName, f.line, via);
    }
    return new Occurrence(Occurrence.JDK_LOCATION, null, -1, via);
  }

  private static boolean isAgent(String c) {
    return c.startsWith("com.developmentsprint.cryptocensus.agent.");
  }

  private static boolean isSecureRandom(String c) {
    return c.equals("java.security.SecureRandom")
        || c.startsWith("sun.security.provider.SecureRandom")
        || c.startsWith("sun.security.provider.NativePRNG")
        || c.startsWith("sun.security.provider.SeedGenerator")
        || c.startsWith("sun.security.provider.AbstractDrbg")
        || c.startsWith("sun.security.provider.HashDrbg")
        || c.startsWith("sun.security.provider.DRBG");
  }

  /** The JCA facades and crypto providers: never interesting as a call site. */
  private static boolean isCryptoLayer(String c) {
    return c.startsWith("java.security.")
        || c.startsWith("javax.crypto.")
        || c.startsWith("sun.security.jca.")
        || (c.startsWith("sun.security.provider.")
            && !c.startsWith("sun.security.provider.certpath."))
        || c.startsWith("sun.security.rsa.")
        || c.startsWith("sun.security.ec.")
        || c.startsWith("sun.security.pkcs11.")
        || c.startsWith("com.sun.crypto.provider.")
        || c.startsWith("org.bouncycastle.jcajce.provider.")
        || c.startsWith("org.bouncycastle.jce.provider.")
        || c.startsWith("org.bouncycastle.pqc.jcajce.provider.");
  }

  private static boolean isPlatform(Frame f) {
    if (f.type != null) {
      ClassLoader loader = f.type.getClassLoader();
      return loader == null || loader == PLATFORM;
    }
    String c = f.className;
    return c.startsWith("java.")
        || c.startsWith("javax.")
        || c.startsWith("jdk.")
        || c.startsWith("sun.")
        || c.startsWith("com.sun.");
  }

  private static String subsystem(String c) {
    if (c.startsWith("sun.security.ssl.") || c.startsWith("javax.net.ssl.")) return "jsse";
    if (c.startsWith("java.util.jar.")
        || c.startsWith("sun.security.util.SignatureFileVerifier")
        || c.startsWith("sun.security.util.ManifestEntryVerifier")
        || c.startsWith("sun.security.pkcs.")) return "jar-verification";
    if (c.startsWith("sun.security.provider.certpath.")
        || c.startsWith("sun.security.validator.")
        || c.startsWith("sun.security.x509.")) return "pki-validation";
    if (c.startsWith("java.util.Random")) return "secure-random";
    // reflection, lambdas and thread plumbing say nothing about which subsystem was involved
    if (c.startsWith("java.lang.") || c.startsWith("jdk.internal.reflect.")) return null;
    return c;
  }
}
