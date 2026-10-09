package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.Targets.Status;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Remembers what happened to each class the agent always means to instrument, so that a JDK which
 * renames or reshapes one of them shows up as a gap in the CBOM instead of as silence.
 */
final class TargetTracker {

  static final String KEM = "javax.crypto.KEM";
  static final String SSL_SESSION = "sun.security.ssl.SSLSessionImpl";

  /** Types we always target by name (KeyPairGenerator subtypes are matched by hierarchy). */
  static final Set<String> ALL =
      Set.of(
          "javax.crypto.Cipher",
          "java.security.Signature",
          "javax.crypto.Mac",
          "javax.crypto.KeyAgreement",
          KEM,
          "javax.crypto.KeyGenerator",
          "java.security.KeyPairGenerator",
          "java.security.MessageDigest",
          SSL_SESSION);

  /** The Java release that added {@code javax.crypto.KEM}. */
  private static final int KEM_SINCE = 21;

  private final Set<String> transformed = ConcurrentHashMap.newKeySet();
  private final Set<String> advised = ConcurrentHashMap.newKeySet();

  /** Byte Buddy transformed this type (bounded: only fixed targets are remembered). */
  void transformed(String type) {
    if (ALL.contains(type)) {
      transformed.add(type);
    }
  }

  /** Advice was applied to at least one method of this type. */
  void advised(String type) {
    if (ALL.contains(type)) {
      advised.add(type);
    }
  }

  /**
   * The targets this JVM is expected to have.
   *
   * @param tls whether TLS observation is enabled
   * @param javaFeature the running Java feature release
   */
  static Set<String> expected(boolean tls, int javaFeature) {
    Set<String> expected = new TreeSet<>(ALL);
    if (!tls) {
      expected.remove(SSL_SESSION);
    }
    if (javaFeature < KEM_SINCE) {
      expected.remove(KEM);
    }
    return expected;
  }

  /**
   * Status of every expected target.
   *
   * @param expected targets to report on
   * @param loaded whether a class of that name is currently loaded; only asked about targets that
   *     were never transformed
   * @param exists whether the JDK has a class file of that name; only asked about targets that are
   *     not loaded
   */
  Map<String, Status> statuses(
      Set<String> expected, Predicate<String> loaded, Predicate<String> exists) {
    Map<String, Status> result = new TreeMap<>();
    for (String target : expected) {
      Status status;
      if (transformed.contains(target)) {
        status = advised.contains(target) ? Status.INSTRUMENTED : Status.NO_MATCHING_METHODS;
      } else if (loaded.test(target)) {
        status = Status.NOT_TRANSFORMED;
      } else {
        status = exists.test(target) ? Status.NOT_LOADED : Status.ABSENT;
      }
      result.put(target, status);
    }
    return result;
  }

  /**
   * Whether the running JDK ships a class file for {@code className}. Never loads the class; class
   * files are visible as resources even in packages a module does not export.
   */
  static boolean classFileExists(String className) {
    return ClassLoader.getSystemResource(className.replace('.', '/') + ".class") != null;
  }
}
