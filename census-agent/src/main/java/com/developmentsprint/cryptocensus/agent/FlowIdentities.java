package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.SourceCatalog;
import java.lang.ref.WeakReference;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Run-local identities with bounded weak loader references and no exported filesystem paths. */
final class FlowIdentities {
  static final String UNKNOWN = "unknown";
  private static final EvidenceDocument.IdentityMapping UNRESOLVED =
      new EvidenceDocument.IdentityMapping(
          UNKNOWN,
          UNKNOWN,
          UNKNOWN,
          "",
          "",
          EvidenceDocument.IdentityStatus.UNRESOLVED,
          "no-resolved-declaring-identity");
  private final ReentrantLock lock = new ReentrantLock();
  private final List<WeakReference<ClassLoader>> loaders = new ArrayList<>();
  private final Map<List<String>, EvidenceDocument.IdentityMapping> identities =
      new LinkedHashMap<>();
  private final SourceCatalog sources;
  private final String applicationId;
  private final Consumer<EvidenceDocument.Counter> diagnostic;
  private final int capacity;

  FlowIdentities(
      SourceCatalog sources,
      String applicationId,
      Consumer<EvidenceDocument.Counter> diagnostic,
      int capacity) {
    if (capacity < 2 || capacity > ContextModel.MAX_FLOWS) {
      throw new IllegalArgumentException("Identity capacity must be between 2 and 4096");
    }
    this.sources = sources;
    this.applicationId =
        applicationId == null ? "" : ContextModel.text(applicationId, "applicationId", 256, false);
    this.diagnostic = diagnostic;
    this.capacity = capacity;
    identities.put(List.of(UNKNOWN), UNRESOLVED);
  }

  EvidenceDocument.IdentityMapping resolve(ProtectionDomain domain, ClassLoader loader) {
    Optional<SourceCatalog.Entry> source = source(domain, loader);
    String application =
        applicationId.isEmpty()
            ? source.map(SourceCatalog.Entry::applicationId).orElse("")
            : applicationId;
    String artifact = source.map(SourceCatalog.Entry::artifactId).orElse("");
    String deployment = source.map(SourceCatalog.Entry::deploymentId).orElse("unspecified");
    lock.lock();
    try {
      String loaderId = loaderId(loader);
      if (UNKNOWN.equals(loaderId)) {
        return UNRESOLVED;
      }
      List<String> key = List.of(loaderId, deployment, artifact, application);
      EvidenceDocument.IdentityMapping existing = identities.get(key);
      if (existing != null) {
        return existing;
      }
      if (identities.size() == capacity) {
        diagnostic.accept(EvidenceDocument.Counter.DROPPED_BINDINGS);
        return UNRESOLVED;
      }
      var mapping =
          new EvidenceDocument.IdentityMapping(
              "identity-" + identities.size(),
              deployment,
              loaderId,
              artifact,
              application,
              application.isEmpty()
                  ? EvidenceDocument.IdentityStatus.UNRESOLVED
                  : EvidenceDocument.IdentityStatus.RESOLVED,
              !applicationId.isEmpty()
                  ? "explicit-application-id"
                  : source.isPresent() ? "source-catalog" : "unmapped-code-source");
      identities.put(key, mapping);
      return mapping;
    } finally {
      lock.unlock();
    }
  }

  ContextModel.Origin origin(ProtectionDomain domain, ClassLoader loader) {
    return source(domain, loader)
        .map(SourceCatalog.Entry::origin)
        .orElse(ContextModel.Origin.UNKNOWN);
  }

  Optional<SourceCatalog.Entry> source(ProtectionDomain domain, ClassLoader loader) {
    if (domain == null
        || domain.getCodeSource() == null
        || domain.getCodeSource().getLocation() == null) {
      return Optional.empty();
    }
    return sources.resolve(
        domain.getCodeSource().getLocation().toExternalForm(),
        loader == null ? null : loader.getName());
  }

  List<EvidenceDocument.IdentityMapping> snapshot() {
    lock.lock();
    try {
      return List.copyOf(identities.values());
    } finally {
      lock.unlock();
    }
  }

  // Do not call an application loader's equals/hashCode, and do not retain its classes or loader.
  @SuppressWarnings({"ReferenceEquality", "PMD.CompareObjectsWithEquals"})
  private String loaderId(ClassLoader loader) {
    if (loader == null) {
      return "bootstrap";
    }
    for (int i = 0; i < loaders.size(); i++) {
      if (loaders.get(i).get() == loader) {
        return "loader-" + i;
      }
    }
    if (loaders.size() == capacity) {
      diagnostic.accept(EvidenceDocument.Counter.DROPPED_BINDINGS);
      return UNKNOWN;
    }
    int id = loaders.size();
    loaders.add(new WeakReference<>(loader));
    return "loader-" + id;
  }
}
