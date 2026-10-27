package com.developmentsprint.cryptocensus.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Versioned per-run evidence sidecar. Counts are entry-point events, never protected bytes. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "ContextModel.bounded snapshots lists with List.copyOf; all elements are immutable values")
public record EvidenceDocument(
    int schemaVersion,
    String runId,
    String cbomHash,
    List<DiscoveredFlow> flows,
    List<Observation> observations,
    List<Handshake> handshakes,
    List<IdentityMapping> identities,
    List<Capability> capabilities,
    Map<Counter, Long> health) {
  public static final int VERSION = 1;
  public static final int MAX_SCOPE_DEPTH = 32;
  public static final int MAX_OBSERVATIONS = 16384;
  public static final int MAX_HANDSHAKES = 4096;
  public static final int MAX_CAPABILITIES = 256;
  public static final int MAX_BINDINGS = 8192;
  public static final int MAX_PSK_DEPTH = 32;
  public static final String RUN_PROPERTY = "cryptocensus:runId";

  /**
   * Snapshots all collections and validates version, identities, references and complete health.
   */
  public EvidenceDocument {
    if (schemaVersion != VERSION) {
      throw new IllegalArgumentException("Unsupported evidence schema " + schemaVersion);
    }
    if (!UUID.fromString(runId).toString().equals(runId)) {
      throw new IllegalArgumentException("Noncanonical run UUID");
    }
    cbomHash = ContextModel.hash(cbomHash, "CBOM hash");
    flows = ContextModel.bounded(flows, ContextModel.MAX_FLOWS, "flows");
    observations = ContextModel.bounded(observations, MAX_OBSERVATIONS, "observations");
    handshakes = ContextModel.bounded(handshakes, MAX_HANDSHAKES, "handshakes");
    identities = ContextModel.bounded(identities, ContextModel.MAX_FLOWS, "identity mappings");
    capabilities = ContextModel.bounded(capabilities, MAX_CAPABILITIES, "capabilities");
    Map<Counter, Long> copied = new EnumMap<>(Counter.class);
    copied.putAll(health);
    if (!copied.keySet().equals(Set.of(Counter.values()))) {
      throw new IllegalArgumentException("All evidence health counters are required");
    }
    copied.forEach(
        (counter, count) -> {
          Objects.requireNonNull(counter, "health counter");
          if (count == null || count < 0) {
            throw new IllegalArgumentException("Invalid health count");
          }
        });
    health = Collections.unmodifiableMap(copied);
    Map<String, IdentityMapping> mappings = new java.util.HashMap<>();
    for (IdentityMapping identity : identities) {
      if (mappings.put(identity.id(), identity) != null) {
        throw new IllegalArgumentException("Duplicate identity mapping");
      }
    }
    Set<ScopeRef> defined = new HashSet<>();
    long declarationCount = 0;
    long bindingCount = 0;
    for (DiscoveredFlow flow : flows) {
      declarationCount += flow.definition().declarations().size();
      bindingCount += flow.definition().bindings().size();
      if (declarationCount > ContextModel.MAX_DECLARATIONS || bindingCount > MAX_BINDINGS) {
        throw new IllegalArgumentException("Evidence declaration/binding budget exceeded");
      }
      IdentityMapping mapping = mappings.get(flow.identityId());
      if (mapping == null
          || mapping.status() != IdentityStatus.RESOLVED
          || !mapping.applicationId().equals(flow.definition().subject().applicationId())) {
        throw new IllegalArgumentException("Flow lacks a resolved matching declaring identity");
      }
      if (!defined.add(new ScopeRef(flow.identityId(), flow.definition().subject()))) {
        throw new IllegalArgumentException("Duplicate flow definition");
      }
    }
    for (Observation observation : observations) {
      validateContext(observation.context(), mappings, defined);
    }
    Map<AttemptRef, Handshake> attempts = new java.util.HashMap<>();
    for (Handshake handshake : handshakes) {
      validateContext(handshake.initiation(), mappings, defined);
      if (attempts.put(handshake.ref(), handshake) != null) {
        throw new IllegalArgumentException("Duplicate handshake attempt");
      }
    }
    for (Handshake handshake : handshakes) {
      validateLineage(handshake, attempts);
    }
    Set<String> features = new HashSet<>();
    for (Capability capability : capabilities) {
      if (!features.add(capability.feature())) {
        throw new IllegalArgumentException("Duplicate capability");
      }
    }
  }

  private static void validateLineage(Handshake handshake, Map<AttemptRef, Handshake> attempts) {
    Set<AttemptRef> seen = new HashSet<>();
    seen.add(handshake.ref());
    AttemptRef origin = handshake.resumption().pskOrigin();
    while (origin != null) {
      Handshake parent = attempts.get(origin);
      if (parent == null
          || parent.outcome() != Outcome.COMPLETED
          || !seen.add(origin)
          || seen.size() > MAX_PSK_DEPTH) {
        throw new IllegalArgumentException("Invalid or cyclic PSK lineage");
      }
      origin = parent.resumption().pskOrigin();
    }
  }

  private static void validateContext(
      ScopeContext context, Map<String, IdentityMapping> mappings, Set<ScopeRef> defined) {
    if (!mappings.containsKey(context.identityId()) || !defined.containsAll(context.path())) {
      throw new IllegalArgumentException("Scope context has dangling identity or flow references");
    }
  }

  /** Complete zero-valued v1 health map; missing counters must never be treated as healthy. */
  public static Map<Counter, Long> zeroHealth() {
    Map<Counter, Long> result = new EnumMap<>(Counter.class);
    for (Counter counter : Counter.values()) {
      result.put(counter, 0L);
    }
    return Collections.unmodifiableMap(result);
  }

  /** Whether a stable application identity was established. */
  public enum IdentityStatus {
    RESOLVED,
    UNRESOLVED
  }

  /** Opaque run-local loader/deployment identifiers, separate from stable application identity. */
  public record IdentityMapping(
      String id,
      String deploymentId,
      String loaderId,
      String sourceArtifact,
      String applicationId,
      IdentityStatus status,
      String provenance) {
    /** Validates resolved or explicitly unresolved declaring-code provenance. */
    public IdentityMapping {
      id = ContextModel.text(id, "identity id", 128, false);
      deploymentId = ContextModel.text(deploymentId, "deployment id", 128, false);
      loaderId = ContextModel.text(loaderId, "loader id", 128, false);
      sourceArtifact = ContextModel.text(sourceArtifact, "source artifact", 256, true);
      applicationId = ContextModel.text(applicationId, "application id", 256, true);
      Objects.requireNonNull(status, "identity status");
      provenance = ContextModel.text(provenance, "identity provenance", 512, false);
      if (status == IdentityStatus.RESOLVED == applicationId.isEmpty()) {
        throw new IllegalArgumentException("Identity resolution and application id disagree");
      }
    }
  }

  /** A discovered flow associated with its declaring-code identity. */
  public record DiscoveredFlow(String identityId, ContextModel.FlowDefinition definition) {
    /** Associates a declaration with a run-local declaring-code identity. */
    public DiscoveredFlow {
      identityId = ContextModel.text(identityId, "identity id", 128, false);
      Objects.requireNonNull(definition, "definition");
    }
  }

  /** A stable flow and the exact source identity from which it was observed. */
  public record ScopeRef(String identityId, ContextModel.Subject subject) {
    /** Validates a stable flow reference and its run-local source identity. */
    public ScopeRef {
      identityId = ContextModel.text(identityId, "identity id", 128, false);
      Objects.requireNonNull(subject, "subject");
      if (subject.applicationDefault()) {
        throw new IllegalArgumentException("Application default is not an observed scope");
      }
    }
  }

  /** Initiating context is execution attribution, never a request-to-connection data-flow proof. */
  public record ScopeContext(String identityId, List<ScopeRef> path, Association association) {
    /** Snapshots an ordered scope path and validates active identity and association. */
    public ScopeContext {
      identityId = ContextModel.text(identityId, "identity id", 128, false);
      path = ContextModel.bounded(path, MAX_SCOPE_DEPTH, "scope path");
      Objects.requireNonNull(association, "association");
      if (association == Association.SYNCHRONOUS_SCOPE != !path.isEmpty()) {
        throw new IllegalArgumentException("Invalid scope path and association");
      }
      if (!path.isEmpty() && !identityId.equals(path.get(path.size() - 1).identityId())) {
        throw new IllegalArgumentException("Active identity differs from innermost flow");
      }
    }
  }

  /** Independent enrichment diagnostics; all counters are required in every v1 document. */
  public enum Counter {
    TRANSFORM_ERRORS,
    RECORDING_FAILURES,
    DROPPED_FLOWS,
    DROPPED_BINDINGS,
    LOST_ATTRIBUTION,
    DROPPED_HANDSHAKES,
    SCOPE_INTEGRITY,
    SCOPE_RESTORE_FAILURES,
    DRAIN_TIMEOUT,
    CONSERVATION_UNVERIFIED,
    SATURATED_COUNTS,
    OUTPUT_FAILURES
  }

  /** Strength of execution-scope attribution, separate from protected-data tracing. */
  public enum Association {
    SYNCHRONOUS_SCOPE,
    NO_SCOPE,
    SCOPE_LOST
  }

  /** Path entries retain order and recursion; inclusive views deduplicate subjects per event. */
  public record Observation(
      String assetRef,
      Occurrence occurrence,
      ScopeContext context,
      long count,
      ContextModel.Binding projectFrame) {
    /** Validates a positive count and its retained inventory attribution. */
    public Observation {
      assetRef = ContextModel.text(assetRef, "assetRef", 4096, false);
      Objects.requireNonNull(occurrence, "occurrence");
      Objects.requireNonNull(context, "context");
      if (count < 1) {
        throw new IllegalArgumentException("Invalid observation count");
      }
      Objects.requireNonNull(projectFrame, "projectFrame");
    }
  }

  /** Declared support level for an observed collection capability. */
  public enum CapabilityStatus {
    SUPPORTED,
    PARTIAL,
    UNAVAILABLE
  }

  /** One unique named capability and its evidence limitations. */
  public record Capability(String feature, CapabilityStatus status, String detail) {
    /** Validates a bounded capability declaration. */
    public Capability {
      feature = ContextModel.text(feature, "capability", 64, false);
      Objects.requireNonNull(status, "status");
      detail = ContextModel.text(detail, "capability detail", 1024, true);
    }
  }

  /** JSSE transport abstraction used for the attempt. */
  public enum Transport {
    SOCKET,
    ENGINE
  }

  /** Local endpoint role in the handshake. */
  public enum PeerRole {
    CLIENT,
    SERVER
  }

  /** Observed handshake outcome, independent of session-object construction. */
  public enum Outcome {
    COMPLETED,
    FAILED,
    UNKNOWN
  }

  /** An explicit yes/no/unknown observation. */
  public enum Flag {
    YES,
    NO,
    UNKNOWN
  }

  /** Empty structural names and group -1 mean unobserved; never export sessions or key material. */
  public record Negotiation(
      String protocol,
      String cipherSuite,
      int selectedGroupId,
      String selectedGroupName,
      String localSignatureScheme,
      String peerSignatureScheme) {
    /** Validates observed structural TLS metadata, retaining unknown fields explicitly. */
    public Negotiation {
      protocol = ContextModel.text(protocol, "protocol", 64, true);
      cipherSuite = ContextModel.text(cipherSuite, "cipher suite", 256, true);
      if (selectedGroupId < -1 || selectedGroupId > 65535) {
        throw new IllegalArgumentException("Invalid IANA named group");
      }
      selectedGroupName = ContextModel.text(selectedGroupName, "named group", 128, true);
      localSignatureScheme =
          ContextModel.text(localSignatureScheme, "local signature scheme", 128, true);
      peerSignatureScheme =
          ContextModel.text(peerSignatureScheme, "peer signature scheme", 128, true);
    }
  }

  /** An opaque attempt reference within this document's run, never a raw TLS session ID. */
  public record AttemptRef(String connectionId, long attempt) {
    /** Validates a run-local attempt identity. */
    public AttemptRef {
      connectionId = ContextModel.text(connectionId, "connectionId", 128, false);
      if (attempt < 1) {
        throw new IllegalArgumentException("Invalid handshake attempt");
      }
    }
  }

  /** A null PSK origin means unestablished lineage, not an independently validated secret. */
  public record Resumption(Flag resumed, Flag freshKeyExchange, AttemptRef pskOrigin) {
    /** Validates resumption evidence and optional object-correlated lineage. */
    public Resumption {
      Objects.requireNonNull(resumed, "resumed");
      Objects.requireNonNull(freshKeyExchange, "freshKeyExchange");
      if (resumed != Flag.YES && pskOrigin != null) {
        throw new IllegalArgumentException("PSK lineage without observed resumption");
      }
    }
  }

  /** One observed TLS attempt with structural metadata and initiating context. */
  public record Handshake(
      String connectionId,
      long attempt,
      Transport transport,
      PeerRole role,
      Outcome outcome,
      Negotiation negotiation,
      Resumption resumption,
      List<String> relatedAssetRefs,
      String adapter,
      List<String> diagnostics,
      ScopeContext initiation) {
    /** Snapshots a TLS attempt without exporting session or secret data. */
    public Handshake {
      connectionId = ContextModel.text(connectionId, "connectionId", 128, false);
      if (attempt < 1) {
        throw new IllegalArgumentException("Invalid handshake attempt");
      }
      Objects.requireNonNull(transport, "transport");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(negotiation, "negotiation");
      Objects.requireNonNull(resumption, "resumption");
      Objects.requireNonNull(initiation, "initiation");
      relatedAssetRefs = ContextModel.bounded(relatedAssetRefs, 64, "handshake assets");
      for (String ref : relatedAssetRefs) {
        ContextModel.text(ref, "related asset", 4096, false);
      }
      adapter = ContextModel.text(adapter, "adapter", 128, false);
      diagnostics = ContextModel.bounded(diagnostics, 32, "handshake diagnostics");
      for (String diagnostic : diagnostics) {
        ContextModel.text(diagnostic, "diagnostic", 256, false);
      }
    }

    /** Returns the attempt identity used for run-local lineage. */
    public AttemptRef ref() {
      return new AttemptRef(connectionId, attempt);
    }
  }
}
