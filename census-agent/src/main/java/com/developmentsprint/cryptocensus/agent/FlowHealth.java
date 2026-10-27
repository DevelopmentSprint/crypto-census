package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Independent saturating health counters; never updates ordinary inventory health. */
final class FlowHealth {
  private final Map<EvidenceDocument.Counter, AtomicLong> counters =
      new EnumMap<>(EvidenceDocument.Counter.class);

  FlowHealth() {
    for (EvidenceDocument.Counter counter : EvidenceDocument.Counter.values()) {
      counters.put(counter, new AtomicLong());
    }
  }

  void increment(EvidenceDocument.Counter counter) {
    add(counter, 1);
  }

  void add(EvidenceDocument.Counter counter, long count) {
    if (count < 0) {
      throw new IllegalArgumentException("Negative health count");
    }
    AtomicLong value = counters.get(counter);
    long previous;
    long updated;
    do {
      previous = value.get();
      updated = previous >= Long.MAX_VALUE - count ? Long.MAX_VALUE : previous + count;
    } while (!value.compareAndSet(previous, updated));
  }

  Map<EvidenceDocument.Counter, Long> snapshot() {
    Map<EvidenceDocument.Counter, Long> result = new EnumMap<>(EvidenceDocument.Counter.class);
    counters.forEach((counter, value) -> result.put(counter, value.get()));
    return Map.copyOf(result);
  }
}
