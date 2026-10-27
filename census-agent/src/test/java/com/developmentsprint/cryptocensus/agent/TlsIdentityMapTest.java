package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TlsIdentityMapTest {
  private record Connection(int id) {}

  @Test
  void equalityNeverMergesDistinctConnectionsAndCapacityDoesNotEvictEvidence() {
    TlsIdentityMap<String> map = new TlsIdentityMap<>(2);
    Object first = new Connection(1);
    Object second = new Connection(1);
    Object overflow = new Object();
    assertThat(map.put(first, "first")).isTrue();
    assertThat(map.put(second, "second")).isTrue();
    assertThat(map.put(overflow, "third")).isFalse();
    assertThat(map.get(first)).isEqualTo("first");
    assertThat(map.get(second)).isEqualTo("second");
    assertThat(map.put(first, "updated")).isTrue();
    assertThat(map.remove(second)).isEqualTo("second");
    assertThat(map.put(overflow, "third")).isTrue();
    assertThat(map.values()).containsExactlyInAnyOrder("updated", "third");
    map.clear();
    assertThat(map.values()).isEmpty();
  }
}
