package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.Targets.Status;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CheckMojoTargetsTest {

  private static final String CIPHER = "javax.crypto.Cipher";
  private static final String TLS = "sun.security.ssl.SSLSessionImpl";

  private static final Map<String, Status> HEALTHY =
      Map.of(CIPHER, Status.INSTRUMENTED, TLS, Status.INSTRUMENTED);
  private static final Map<String, Status> NO_TLS =
      Map.of(CIPHER, Status.INSTRUMENTED, TLS, Status.NOT_LOADED);
  private static final Map<String, Status> RENAMED =
      Map.of(CIPHER, Status.INSTRUMENTED, TLS, Status.ABSENT);

  @Test
  void healthyTargetsSayNothing() {
    CheckMojo.TargetReport report = CheckMojo.targetReport(List.of(HEALTHY, NO_TLS), true);

    assertThat(report.info()).isEmpty();
    assertThat(report.warnings()).isEmpty();
    assertThat(report.failure()).isEmpty();
  }

  @Test
  void targetsNoJvmLoadedAreInformational() {
    CheckMojo.TargetReport report = CheckMojo.targetReport(List.of(NO_TLS), true);

    assertThat(report.info()).singleElement().asString().endsWith(TLS);
    assertThat(report.warnings()).isEmpty();
    assertThat(report.failure()).isEmpty();
  }

  @Test
  void gapsWarnByDefault() {
    CheckMojo.TargetReport report = CheckMojo.targetReport(List.of(HEALTHY, RENAMED), false);

    assertThat(report.warnings())
        .singleElement()
        .asString()
        .contains("Instrumentation gap", TLS, "does not exist in this JDK", "(1 of 2 JVMs)");
    assertThat(report.failure()).isEmpty();
  }

  @Test
  void gapsFailWhenConfigured() {
    CheckMojo.TargetReport report = CheckMojo.targetReport(List.of(RENAMED), true);

    assertThat(report.warnings()).isEmpty();
    assertThat(report.failure()).get().asString().contains(TLS, "failOnMissingTargets=false");
  }

  @Test
  void jvmWithoutTargetReportIsGap() {
    // An older agent, or one that could not determine its targets, proves no coverage at all.
    CheckMojo.TargetReport strict = CheckMojo.targetReport(List.of(HEALTHY, Map.of()), true);
    assertThat(strict.failure()).get().asString().contains("1 of 2 test JVM(s)", "unknown");

    CheckMojo.TargetReport lenient = CheckMojo.targetReport(List.of(Map.of()), false);
    assertThat(lenient.failure()).isEmpty();
    assertThat(lenient.warnings()).singleElement().asString().contains("1 of 1 test JVM(s)");
  }
}
