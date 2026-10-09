package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.Targets.Status;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TargetsTest {

  private static final String CIPHER = "javax.crypto.Cipher";
  private static final String TLS = "sun.security.ssl.SSLSessionImpl";
  private static final String KEM = "javax.crypto.KEM";

  @Test
  void formatsSortedAndParsesBack() {
    Map<String, Status> statuses =
        Map.of(TLS, Status.ABSENT, CIPHER, Status.INSTRUMENTED, KEM, Status.NOT_LOADED);

    String value = Targets.format(statuses);

    assertThat(value)
        .isEqualTo(
            "javax.crypto.Cipher=instrumented,javax.crypto.KEM=not-loaded,"
                + "sun.security.ssl.SSLSessionImpl=absent");
    assertThat(Targets.parse(value)).isEqualTo(statuses);
  }

  @Test
  void blankMeansNoTargets() {
    assertThat(Targets.format(Map.of())).isEmpty();
    assertThat(Targets.parse("")).isEmpty();
    assertThat(Targets.parse(null)).isEmpty();
  }

  @Test
  void rejectsMalformedEntries() {
    assertThatThrownBy(() -> Targets.parse("javax.crypto.Cipher"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Targets.parse("=instrumented"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Targets.parse("javax.crypto.Cipher=fine"))
        .hasMessageContaining("fine");
    assertThatThrownBy(() -> Targets.parse("a.B=absent,a.B=instrumented"))
        .hasMessageContaining("more than once");
  }

  @Test
  void onlyChangesThatLoseObservationsAreGaps() {
    assertThat(Status.NOT_LOADED.isGap()).isFalse();
    assertThat(Status.INSTRUMENTED.isGap()).isFalse();
    assertThat(Status.NO_MATCHING_METHODS.isGap()).isTrue();
    assertThat(Status.NOT_TRANSFORMED.isGap()).isTrue();
    assertThat(Status.ABSENT.isGap()).isTrue();
    for (Status s : Status.values()) {
      assertThat(Status.parse(s.label())).isSameAs(s);
      assertThat(s.explanation()).isNotBlank();
    }
  }

  @Test
  void mergeKeepsTheMostConcerningStatusPerTarget() {
    Map<String, Status> jvm1 =
        Map.of(CIPHER, Status.INSTRUMENTED, TLS, Status.NOT_LOADED, KEM, Status.NOT_LOADED);
    Map<String, Status> jvm2 =
        Map.of(CIPHER, Status.NOT_TRANSFORMED, TLS, Status.INSTRUMENTED, KEM, Status.NOT_LOADED);

    Map<String, Status> merged = Targets.merge(List.of(jvm1, jvm2));

    // a gap in any JVM is a gap; used anywhere beats never loaded
    assertThat(merged)
        .containsEntry(CIPHER, Status.NOT_TRANSFORMED)
        .containsEntry(TLS, Status.INSTRUMENTED)
        .containsEntry(KEM, Status.NOT_LOADED);
    assertThat(Targets.missing(merged))
        .containsOnlyKeys(CIPHER, KEM)
        .containsEntry(CIPHER, Status.NOT_TRANSFORMED);
  }

  @Test
  void describesGapsWithTheNumberOfAffectedJvms() {
    Map<String, Status> healthy = Map.of(CIPHER, Status.INSTRUMENTED, TLS, Status.INSTRUMENTED);
    Map<String, Status> renamed = Map.of(CIPHER, Status.INSTRUMENTED, TLS, Status.ABSENT);

    assertThat(Targets.describeGaps(List.of(healthy, renamed, healthy)))
        .singleElement()
        .asString()
        .startsWith(TLS + " does not exist in this JDK")
        .endsWith("(1 of 3 JVMs)");
    assertThat(Targets.describeGaps(List.of(renamed))).singleElement().asString().endsWith("JVM)");
    assertThat(Targets.describeGaps(List.of(healthy))).isEmpty();
  }

  @Test
  void listsTargetsNoJvmLoaded() {
    Map<String, Status> merged =
        Targets.merge(
            List.of(
                Map.of(CIPHER, Status.INSTRUMENTED, KEM, Status.NOT_LOADED, TLS, Status.NOT_LOADED),
                Map.of(CIPHER, Status.INSTRUMENTED, KEM, Status.NOT_LOADED, TLS, Status.ABSENT)));

    assertThat(Targets.notLoaded(merged)).containsExactly(KEM);
  }
}
