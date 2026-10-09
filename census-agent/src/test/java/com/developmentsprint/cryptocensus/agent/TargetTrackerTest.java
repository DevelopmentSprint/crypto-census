package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.Targets.Status;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TargetTrackerTest {

  private static final String CIPHER = "javax.crypto.Cipher";
  private static final String MAC = "javax.crypto.Mac";
  private static final String DIGEST = "java.security.MessageDigest";

  @Test
  void distinguishesEveryWayTargetsCanBeMissed() {
    TargetTracker tracker = new TargetTracker();
    tracker.transformed(CIPHER);
    tracker.advised(CIPHER);
    tracker.transformed(MAC); // transformed, but no method matched
    Set<String> loaded = Set.of(CIPHER, MAC, DIGEST);
    Set<String> existing = Set.of(CIPHER, MAC, DIGEST, TargetTracker.KEM);

    Map<String, Status> statuses =
        tracker.statuses(
            Set.of(CIPHER, MAC, DIGEST, TargetTracker.KEM, TargetTracker.SSL_SESSION),
            loaded::contains,
            existing::contains);

    assertThat(statuses)
        .containsEntry(CIPHER, Status.INSTRUMENTED)
        .containsEntry(MAC, Status.NO_MATCHING_METHODS)
        .containsEntry(DIGEST, Status.NOT_TRANSFORMED)
        .containsEntry(TargetTracker.KEM, Status.NOT_LOADED)
        .containsEntry(TargetTracker.SSL_SESSION, Status.ABSENT);
  }

  @Test
  void remembersOnlyFixedTargets() {
    TargetTracker tracker = new TargetTracker();
    tracker.transformed("com.acme.Provider$KeyPairGenerator");
    tracker.advised("com.acme.Provider$KeyPairGenerator");

    assertThat(
            tracker.statuses(
                Set.of("com.acme.Provider$KeyPairGenerator"), name -> true, name -> true))
        .containsEntry("com.acme.Provider$KeyPairGenerator", Status.NOT_TRANSFORMED);
  }

  @Test
  void expectsKemFromJava21AndTlsOnlyWhenEnabled() {
    assertThat(TargetTracker.expected(true, 17))
        .doesNotContain(TargetTracker.KEM)
        .contains(TargetTracker.SSL_SESSION);
    assertThat(TargetTracker.expected(true, 21)).containsAll(TargetTracker.ALL);
    assertThat(TargetTracker.expected(false, 25)).doesNotContain(TargetTracker.SSL_SESSION);
  }

  @Test
  void findsJdkClassFilesWithoutLoadingThem() {
    // sun.security.ssl is not exported by java.base; class files are still visible as resources
    assertThat(TargetTracker.classFileExists(TargetTracker.SSL_SESSION)).isTrue();
    assertThat(TargetTracker.classFileExists(CIPHER)).isTrue();
    assertThat(TargetTracker.classFileExists("sun.security.ssl.SSLSessionImplRenamed")).isFalse();
  }
}
