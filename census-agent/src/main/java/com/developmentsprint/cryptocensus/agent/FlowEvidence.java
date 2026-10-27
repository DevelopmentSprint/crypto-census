package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Counts one direct attribution bucket per retained core event, without duplicating parents. */
final class FlowEvidence {
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<Key, Long> observations = new LinkedHashMap<>();
  private final int capacity;
  private final Consumer<EvidenceDocument.Counter> diagnostic;

  FlowEvidence(int capacity, Consumer<EvidenceDocument.Counter> diagnostic) {
    if (capacity < 1 || capacity > EvidenceDocument.MAX_OBSERVATIONS) {
      throw new IllegalArgumentException("Observation capacity must be between 1 and 16384");
    }
    this.capacity = capacity;
    this.diagnostic = diagnostic;
  }

  void record(
      String assetRef,
      Inventory.RecordOutcome outcome,
      EvidenceDocument.ScopeContext context,
      ContextModel.Binding projectFrame,
      long count) {
    if (count < 1) {
      throw new IllegalArgumentException("Observation count must be positive");
    }
    if (outcome.disposition() == Inventory.Disposition.DROPPED) {
      return;
    }
    var key = new Key(assetRef, outcome.occurrence(), context, projectFrame);
    lock.lock();
    try {
      if (!observations.containsKey(key) && observations.size() == capacity) {
        diagnostic.accept(EvidenceDocument.Counter.LOST_ATTRIBUTION);
        diagnostic.accept(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED);
        return;
      }
      add(observations, key, count);
    } finally {
      lock.unlock();
    }
  }

  /** Applies the writer's effective occurrence folding and any whole-run integrity failure. */
  List<EvidenceDocument.Observation> snapshot(List<Inventory.Entry> written, boolean unverified) {
    Map<String, Inventory.Entry> assets = new LinkedHashMap<>();
    for (Inventory.Entry entry : written) {
      assets.put(entry.asset().ref(), entry);
    }
    lock.lock();
    try {
      Map<Key, Long> folded = new LinkedHashMap<>();
      observations.forEach(
          (key, count) -> {
            Inventory.Entry entry = assets.get(key.assetRef());
            if (entry == null) {
              diagnostic.accept(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED);
              return;
            }
            Occurrence occurrence =
                entry.occurrences().containsKey(key.occurrence())
                    ? key.occurrence()
                    : Occurrence.OVERFLOW;
            if (!entry.occurrences().containsKey(occurrence)) {
              diagnostic.accept(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED);
              return;
            }
            EvidenceDocument.ScopeContext context =
                unverified
                    ? FlowCatalog.unassigned(EvidenceDocument.Association.SCOPE_LOST)
                    : key.context();
            add(folded, new Key(key.assetRef(), occurrence, context, key.projectFrame()), count);
          });
      return folded.entrySet().stream()
          .map(
              entry ->
                  new EvidenceDocument.Observation(
                      entry.getKey().assetRef(),
                      entry.getKey().occurrence(),
                      entry.getKey().context(),
                      entry.getValue(),
                      entry.getKey().projectFrame()))
          .toList();
    } finally {
      lock.unlock();
    }
  }

  private void add(Map<Key, Long> target, Key key, long count) {
    long previous = target.getOrDefault(key, 0L);
    if (previous >= Long.MAX_VALUE - count) {
      diagnostic.accept(EvidenceDocument.Counter.SATURATED_COUNTS);
    }
    target.put(key, previous >= Long.MAX_VALUE - count ? Long.MAX_VALUE : previous + count);
  }

  private record Key(
      String assetRef,
      Occurrence occurrence,
      EvidenceDocument.ScopeContext context,
      ContextModel.Binding projectFrame) {}
}
