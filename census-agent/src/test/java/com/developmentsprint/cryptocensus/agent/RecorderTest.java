package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Provider;
import java.security.Security;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecorderTest {

  @Test
  void operationsInsideTheProviderCountAsInFlightFromAdmission() {
    // regression: in-flight used to be counted only once exit advice started recording, so the
    // shutdown drain could not see a call that was still inside the provider
    Recorder.enable();
    try {
      int depth = Recorder.enter();
      assertThat(depth).isEqualTo(1);
      assertThat(Recorder.inFlight()).isEqualTo(1);

      Recorder.disable(); // shutdown begins while the call is still running
      assertThat(Recorder.inFlight()).isEqualTo(1);

      Recorder.exit(depth, null, null, null, null);
      assertThat(Recorder.inFlight()).isZero();
    } finally {
      Recorder.disable();
    }
  }

  @Test
  void shutdownBetweenRegistrationAndCheckIsSeenByOneSide() {
    // regression: enter() used to check `enabled` and then register; a thread pausing in between
    // was invisible to the drain. Now registration comes first, so at the moment shutdown flips
    // the flag this call is already counted, and it backs out instead of recording.
    Recorder.enable();
    int[] seenByShutdown = new int[1];
    Recorder.afterAdmissionHook =
        () -> {
          Recorder.disable(); // shutdown happens exactly here
          seenByShutdown[0] = Recorder.inFlight(); // ... and its drain sees the call
        };
    try {
      assertThat(Recorder.enter()).isZero(); // the call backs out rather than recording late
      assertThat(seenByShutdown[0]).isEqualTo(1);
      assertThat(Recorder.inFlight()).isZero();
    } finally {
      Recorder.afterAdmissionHook = null;
      Recorder.disable();
    }
  }

  @Test
  void nestedCallsAreNotCountedTwice() {
    Recorder.enable();
    try {
      int outer = Recorder.enter();
      int inner = Recorder.enter();
      assertThat(Recorder.inFlight()).isEqualTo(1);
      Recorder.exit(inner, null, null, null, null);
      Recorder.exit(outer, null, null, null, null);
      assertThat(Recorder.inFlight()).isZero();
    } finally {
      Recorder.disable();
    }
  }

  @Test
  void resolvesProviderAliasesToCanonicalNames() {
    // regression: DSS / SHA / OID spellings reached real algorithms but were reported as unknown
    assertThat(Recorder.resolve(Security.getProvider("SUN"), "Signature", "DSS"))
        .isEqualTo("SHA1withDSA");
    assertThat(Recorder.resolve(Security.getProvider("SUN"), "MessageDigest", "SHA"))
        .isEqualTo("SHA-1");
    assertThat(Recorder.resolveTransformation(Security.getProvider("SunJCE"), "AES/GCM/NoPadding"))
        .isEqualTo("AES/GCM/NoPadding");
    assertThat(Recorder.resolve(Security.getProvider("SUN"), "MessageDigest", "no-such-thing"))
        .isEqualTo("no-such-thing");
    assertThat(Recorder.resolve(null, "MessageDigest", "SHA")).isEqualTo("SHA");
  }

  @Test
  @SuppressWarnings("deprecation")
  void resolvesFullTransformationAliases() {
    // regression: only the part before '/' was resolved, so a provider alias for a whole
    // transformation was reported under the vendor name and judged unknown
    Provider vendor =
        new Provider("Vendor", "1.0", "test") {
          {
            putService(
                new Service(
                    this,
                    "Cipher",
                    "AES/CBC/PKCS5Padding",
                    "unused.Impl",
                    List.of("VendorAES/CBC/PKCS5Padding"),
                    null));
            putService(
                new Service(
                    this, "Cipher", "AES/GCM", "unused.Impl", List.of("VendorAES/GCM"), null));
          }
        };
    assertThat(Recorder.resolveTransformation(vendor, "VendorAES/CBC/PKCS5Padding"))
        .isEqualTo("AES/CBC/PKCS5Padding");
    assertThat(Recorder.resolveTransformation(vendor, "VendorAES/GCM/NoPadding"))
        .isEqualTo("AES/GCM/NoPadding");
    assertThat(Recorder.resolveTransformation(vendor, "Other/CBC/NoPadding"))
        .isEqualTo("Other/CBC/NoPadding");
    // round 4: whitespace is normalized before lookup
    assertThat(Recorder.resolveTransformation(vendor, " VendorAES / CBC / PKCS5Padding "))
        .isEqualTo("AES/CBC/PKCS5Padding");
  }

  @Test
  @SuppressWarnings("deprecation")
  void rebuildsFromTheCanonicalServiceShape() {
    // round 4: canonical "AES" registered under the alias "VendorAES/CBC" -> mode comes from the
    // request
    Provider vendor =
        new Provider("Vendor2", "1.0", "test") {
          {
            putService(
                new Service(this, "Cipher", "AES", "unused.Impl", List.of("VendorAES/CBC"), null));
          }
        };
    assertThat(Recorder.resolveTransformation(vendor, "VendorAES/CBC/PKCS5Padding"))
        .isEqualTo("AES/CBC/PKCS5Padding");
    // padding names may contain '/'
    assertThat(
            Recorder.resolveTransformation(
                Security.getProvider("SunJCE"), "RSA/ECB/OAEPWithSHA-512/256AndMGF1Padding"))
        .isEqualTo("RSA/ECB/OAEPWithSHA-512/256AndMGF1Padding");
  }
}
