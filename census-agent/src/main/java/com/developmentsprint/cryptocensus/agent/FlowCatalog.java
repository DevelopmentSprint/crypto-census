package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Bounded declaration registry; runtime binding IDs reference shared typed scope definitions. */
final class FlowCatalog {
  private final ReentrantLock lock = new ReentrantLock();
  private final FlowBindings bindings;
  private final Map<Integer, EvidenceDocument.ScopeRef> bindingScopes = new LinkedHashMap<>();
  private final Map<EvidenceDocument.ScopeRef, ContextModel.FlowDefinition> flows =
      new LinkedHashMap<>();
  private final Consumer<EvidenceDocument.Counter> diagnostic;
  private final int capacity;
  private int declarations;
  private int declaredBindings;

  FlowCatalog(int capacity, int bindingCapacity, Consumer<EvidenceDocument.Counter> diagnostic) {
    if (capacity < 1 || capacity > ContextModel.MAX_FLOWS) {
      throw new IllegalArgumentException("Flow capacity must be between 1 and 4096");
    }
    this.capacity = capacity;
    this.bindings = new FlowBindings(bindingCapacity);
    this.diagnostic = diagnostic;
  }

  int register(EvidenceDocument.IdentityMapping identity, ContextModel.FlowDefinition definition) {
    if (identity.status() != EvidenceDocument.IdentityStatus.RESOLVED) {
      diagnostic.accept(EvidenceDocument.Counter.LOST_ATTRIBUTION);
      return 0;
    }
    if (!identity.applicationId().equals(definition.subject().applicationId())
        || definition.bindings().size() != 1) {
      throw new IllegalArgumentException("Binding and declaring identity must match");
    }
    var scope = new EvidenceDocument.ScopeRef(identity.id(), definition.subject());
    String key = normalizedKey(scope, definition);
    lock.lock();
    try {
      ContextModel.FlowDefinition previous = flows.get(scope);
      if (previous == null && flows.size() == capacity) {
        diagnostic.accept(EvidenceDocument.Counter.DROPPED_FLOWS);
        return 0;
      }
      var mergedDeclarations = new LinkedHashSet<>(definition.declarations());
      var mergedBindings = new LinkedHashSet<>(definition.bindings());
      if (previous != null) {
        mergedDeclarations.addAll(previous.declarations());
        mergedBindings.addAll(previous.bindings());
      }
      int newDeclarations =
          declarations
              + mergedDeclarations.size()
              - (previous == null ? 0 : previous.declarations().size());
      int newBindings =
          declaredBindings
              + mergedBindings.size()
              - (previous == null ? 0 : previous.bindings().size());
      if (mergedBindings.size() > ContextModel.MAX_BINDINGS
          || newBindings > EvidenceDocument.MAX_BINDINGS
          || newDeclarations > ContextModel.MAX_DECLARATIONS) {
        diagnostic.accept(EvidenceDocument.Counter.DROPPED_BINDINGS);
        return 0;
      }
      var merged =
          new ContextModel.FlowDefinition(
              definition.subject(),
              definition.label(),
              List.copyOf(mergedDeclarations),
              List.copyOf(mergedBindings));
      int id = bindings.register(key);
      if (id == 0) {
        diagnostic.accept(EvidenceDocument.Counter.DROPPED_BINDINGS);
        return 0;
      }
      flows.put(scope, merged);
      bindingScopes.put(id, scope);
      declarations = newDeclarations;
      declaredBindings = newBindings;
      return id;
    } finally {
      lock.unlock();
    }
  }

  EvidenceDocument.ScopeContext context(FlowScopes.Snapshot snapshot) {
    if (snapshot.status() != FlowScopes.Status.SYNCHRONOUS_SCOPE) {
      return unassigned(
          snapshot.status() == FlowScopes.Status.NO_SCOPE
              ? EvidenceDocument.Association.NO_SCOPE
              : EvidenceDocument.Association.SCOPE_LOST);
    }
    lock.lock();
    try {
      List<EvidenceDocument.ScopeRef> path = new ArrayList<>(snapshot.bindings().size());
      for (int id : snapshot.bindings()) {
        EvidenceDocument.ScopeRef scope = bindingScopes.get(id);
        if (scope == null) {
          return unassigned(EvidenceDocument.Association.SCOPE_LOST);
        }
        path.add(scope);
      }
      return new EvidenceDocument.ScopeContext(
          path.get(path.size() - 1).identityId(),
          path,
          EvidenceDocument.Association.SYNCHRONOUS_SCOPE);
    } finally {
      lock.unlock();
    }
  }

  List<EvidenceDocument.DiscoveredFlow> snapshot() {
    lock.lock();
    try {
      return flows.entrySet().stream()
          .map(
              entry ->
                  new EvidenceDocument.DiscoveredFlow(
                      entry.getKey().identityId(), entry.getValue()))
          .toList();
    } finally {
      lock.unlock();
    }
  }

  static EvidenceDocument.ScopeContext unassigned(EvidenceDocument.Association association) {
    return new EvidenceDocument.ScopeContext(FlowIdentities.UNKNOWN, List.of(), association);
  }

  private static String normalizedKey(
      EvidenceDocument.ScopeRef scope, ContextModel.FlowDefinition definition) {
    ContextModel.Binding binding = definition.bindings().get(0);
    List<Map<String, String>> declarations =
        definition.declarations().stream()
            .map(
                value ->
                    Map.of(
                        "field",
                        value.field().name(),
                        "value",
                        value.value(),
                        "source",
                        value.source().id(),
                        "revision",
                        value.source().revision()))
            .toList();
    return FlowHashes.revision(
        Map.of(
            "identity",
            scope.identityId(),
            "application",
            scope.subject().applicationId(),
            "flow",
            scope.subject().flowId(),
            "class",
            binding.className(),
            "method",
            binding.method(),
            "descriptor",
            binding.descriptor(),
            "artifact",
            binding.sourceArtifact(),
            "declarations",
            declarations));
  }
}
