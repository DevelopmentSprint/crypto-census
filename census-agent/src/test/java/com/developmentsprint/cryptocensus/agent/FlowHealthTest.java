package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import org.junit.jupiter.api.Test;

class FlowHealthTest {
  @Test
  void snapshotsAreCompleteIndependentAndSaturating() {
    var health = new FlowHealth();
    var before = health.snapshot();
    health.add(EvidenceDocument.Counter.RECORDING_FAILURES, Long.MAX_VALUE);
    health.increment(EvidenceDocument.Counter.RECORDING_FAILURES);
    assertThat(before).isEqualTo(EvidenceDocument.zeroHealth());
    assertThat(health.snapshot().get(EvidenceDocument.Counter.RECORDING_FAILURES))
        .isEqualTo(Long.MAX_VALUE);
    assertThatThrownBy(() -> before.clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> health.add(EvidenceDocument.Counter.LOST_ATTRIBUTION, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
