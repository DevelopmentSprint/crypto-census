package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowHashesTest {
  @Test
  void internalHashSuppressionNestsAndRestoresAfterFailure() {
    Recorder.configure(10, 10);
    Recorder.enableEnrichment();
    Recorder.enable();
    boolean previous = Recorder.suppressInternalHash();
    try {
      assertThat(FlowHashes.revision(Map.of("id", "archive"))).hasSize(64);
      assertThat(Recorder.enter()).isZero();
      assertThatThrownBy(() -> FlowHashes.revision(new Object()))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(Recorder.enter()).isZero();
    } finally {
      Recorder.restoreInternalHash(previous);
    }
    int depth = Recorder.enter();
    assertThat(depth).isEqualTo(1);
    Recorder.exit(depth, null, null, null, null);
    assertThat(Recorder.inFlight()).isZero();
    Recorder.disable();
    Recorder.configure(500, 50);
  }
}
