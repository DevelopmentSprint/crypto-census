package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TlsSessionLineageTest {
  @Test
  void ticketsCreatedBeforeCompletionResolveOnlyAfterObservedCompletion() {
    TlsSessionLineage<String> lineage = new TlsSessionLineage<>(4);
    Object original = new Object();
    Object firstTicket = new Object();
    Object laterTicket = new Object();
    assertThat(lineage.derived(firstTicket, original)).isTrue();
    assertThat(lineage.origin(firstTicket)).isNull();
    assertThat(lineage.completed(original, "connection-1-attempt-1")).isTrue();
    assertThat(lineage.origin(firstTicket)).isEqualTo("connection-1-attempt-1");
    assertThat(lineage.derived(laterTicket, firstTicket)).isTrue();
    assertThat(lineage.origin(laterTicket)).isEqualTo("connection-1-attempt-1");
    assertThat(lineage.completed(firstTicket, "connection-2-attempt-1")).isTrue();
    assertThat(lineage.origin(firstTicket)).isEqualTo("connection-2-attempt-1");
    assertThat(lineage.derived(firstTicket, original)).isTrue();
    assertThat(lineage.origin(firstTicket)).isEqualTo("connection-2-attempt-1");
    lineage.clear();
    assertThat(lineage.origin(firstTicket)).isNull();
  }

  @Test
  void cyclesAndCapacityRemainUnknownWithoutInventingOrigins() {
    TlsSessionLineage<String> lineage = new TlsSessionLineage<>(2);
    Object first = new Object();
    Object second = new Object();
    assertThat(lineage.derived(first, second)).isTrue();
    assertThat(lineage.derived(second, first)).isTrue();
    assertThat(lineage.origin(first)).isNull();
    assertThat(lineage.completed(new Object(), "overflow")).isFalse();
    assertThat(lineage.origin(new Object())).isNull();
  }
}
