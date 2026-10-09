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
    Frame application = null;
    while (application == null && frames.hasNext()) {
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
      if (isThirdPartyTls(c)) {
        if (via == null) {
          via = "jsse";
        }
        continue;
      }
      if (isPlatform(f)) {
        if (via == null) {
          via = subsystem(c);
        }
      } else {
        application = f;
      }
    }
    return application == null
        ? new Occurrence(Occurrence.JDK_LOCATION, null, -1, via)
        : new Occurrence(application.className, application.methodName, application.line, via);
  }

  private static boolean isAgent(String c) {
    return c.startsWith("com.developmentsprint.cryptocensus.agent.");
  }

  private static boolean isSecureRandom(String c) {
    return "java.security.SecureRandom".equals(c)
        || c.startsWith("sun.security.provider.SecureRandom")
        || c.startsWith("sun.security.provider.NativePRNG")
        || c.startsWith("sun.security.provider.SeedGenerator")
        || c.startsWith("sun.security.provider.AbstractDrbg")
        || c.startsWith("sun.security.provider.HashDrbg")
        || c.startsWith("sun.security.provider.DRBG");
  }

  /**
   * Packages of JCA providers. Their frames are never the application's call site, whichever class
   * loader defined them: Semeru's OpenJCEPlus, for one, is defined by the application class loader,
   * and an HSM vendor's provider sits on the class path like any library.
   */
  private static final String[] PROVIDER_PACKAGES = {
    // OpenJDK
    "sun.security.jca.",
    "sun.security.rsa.",
    "sun.security.ec.",
    "sun.security.pkcs11.", // SunPKCS11, including NSS-backed FIPS mode (RHEL)
    "sun.security.mscapi.", // SunMSCAPI (Windows)
    "com.sun.crypto.provider.",
    "apple.security.", // Apple provider (macOS KeychainStore)
    // IBM (Semeru, WebSphere): IBMJCE, IBMJCEFIPS, IBMJCEPlus(FIPS), OpenJCEPlus(FIPS),
    // IBMPKCS11Impl, IBMJCECCA
    "com.ibm.crypto.",
    // Bouncy Castle, including BC-FIPS
    "org.bouncycastle.jcajce.provider.",
    "org.bouncycastle.jce.provider.",
    "org.bouncycastle.pqc.jcajce.provider.",
    // Other providers common in regulated environments
    "com.amazon.corretto.crypto.provider.", // ACCP
    "com.amazonaws.cloudhsm.jce.provider.", // AWS CloudHSM
    "com.safenetinc.luna.provider.", // Thales Luna HSM
    "com.ncipher.provider.", // Entrust nShield HSM
    "com.wolfssl.provider.jce.", // wolfCrypt JCE (FIPS)
    "org.conscrypt.", // Conscrypt
  };

  /** TLS implementations that are libraries rather than part of the JDK. */
  private static final String[] THIRD_PARTY_TLS_PACKAGES = {
    "org.bouncycastle.jsse.", // BCJSSE
    "org.bouncycastle.tls.",
  };

  /** The JCA facades and crypto providers: never interesting as a call site. */
  private static boolean isCryptoLayer(String c) {
    if (c.startsWith("java.security.")
        || c.startsWith("javax.crypto.")
        || (c.startsWith("sun.security.provider.")
            && !c.startsWith("sun.security.provider.certpath."))) {
      return true;
    }
    for (String p : PROVIDER_PACKAGES) {
      if (c.startsWith(p)) {
        return true;
      }
    }
    return false;
  }

  /** A TLS stack shipped as a library: its handshake crypto is attributed like JSSE's. */
  private static boolean isThirdPartyTls(String c) {
    for (String p : THIRD_PARTY_TLS_PACKAGES) {
      if (c.startsWith(p)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPlatform(Frame f) {
    if (f.type != null) {
      ClassLoader loader = f.type.getClassLoader();
      return loader == null || loader.equals(PLATFORM);
    }
    String c = f.className;
    return c.startsWith("java.")
        || c.startsWith("javax.")
        || c.startsWith("jdk.")
        || c.startsWith("sun.")
        || c.startsWith("com.sun.");
  }

  private static String subsystem(String c) {
    if (c.startsWith("sun.security.ssl.") || c.startsWith("javax.net.ssl.")) {
      return "jsse";
    }
    if (c.startsWith("java.util.jar.")
        || c.startsWith("sun.security.util.SignatureFileVerifier")
        || c.startsWith("sun.security.util.ManifestEntryVerifier")
        || c.startsWith("sun.security.pkcs.")) {
      return "jar-verification";
    }
    if (c.startsWith("sun.security.provider.certpath.")
        || c.startsWith("sun.security.validator.")
        || c.startsWith("sun.security.x509.")) {
      return "pki-validation";
    }
    if (c.startsWith("java.util.Random")) {
      return "secure-random";
    }
    // reflection, lambdas and thread plumbing say nothing about which subsystem was involved
    if (c.startsWith("java.lang.") || c.startsWith("jdk.internal.reflect.")) {
      return null;
    }
    return c;
  }
}
