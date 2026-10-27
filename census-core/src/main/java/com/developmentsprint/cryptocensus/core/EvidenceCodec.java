package com.developmentsprint.cryptocensus.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Strict versioned evidence wire codec and exact-byte CBOM pairing validation. */
public final class EvidenceCodec {
  private EvidenceCodec() {}

  /** Encodes a validated document using deterministic schema v1 ordering. */
  public static byte[] encode(EvidenceDocument document) {
    Map<String, Object> values = new TreeMap<>();
    values.put("schemaVersion", document.schemaVersion());
    values.put("runId", document.runId());
    values.put("cbomHash", document.cbomHash());
    values.put(
        "flows",
        ContextJson.ordered(document.flows().stream().map(EvidenceCodec::discoveredFlow).toList()));
    values.put(
        "observations",
        ContextJson.ordered(
            document.observations().stream().map(EvidenceCodec::observation).toList()));
    values.put(
        "handshakes",
        ContextJson.ordered(document.handshakes().stream().map(EvidenceCodec::handshake).toList()));
    values.put(
        "identities",
        ContextJson.ordered(document.identities().stream().map(EvidenceCodec::identity).toList()));
    values.put(
        "capabilities",
        ContextJson.ordered(
            document.capabilities().stream().map(EvidenceCodec::capability).toList()));
    Map<String, Object> health = new TreeMap<>();
    document.health().forEach((counter, count) -> health.put(counter.name(), count));
    values.put("health", health);
    return CanonicalJson.encode(values);
  }

  /** Parses a strict v1 document and validates its structural invariants. */
  public static EvidenceDocument parse(byte[] bytes) {
    Map<String, Object> object = CanonicalJson.object(CanonicalJson.decode(bytes));
    CanonicalJson.requireKeys(
        object,
        "schemaVersion",
        "runId",
        "cbomHash",
        "flows",
        "observations",
        "handshakes",
        "identities",
        "capabilities",
        "health");
    Map<EvidenceDocument.Counter, Long> health = new EnumMap<>(EvidenceDocument.Counter.class);
    Map<String, Object> rawHealth = CanonicalJson.object(object.get("health"));
    for (String key : rawHealth.keySet()) {
      health.put(EvidenceDocument.Counter.valueOf(key), CanonicalJson.integer(rawHealth, key));
    }
    return new EvidenceDocument(
        intField(object, "schemaVersion"),
        CanonicalJson.text(object, "runId"),
        CanonicalJson.text(object, "cbomHash"),
        CanonicalJson.array(object, "flows").stream().map(EvidenceCodec::discoveredFlow).toList(),
        CanonicalJson.array(object, "observations").stream()
            .map(EvidenceCodec::observation)
            .toList(),
        CanonicalJson.array(object, "handshakes").stream().map(EvidenceCodec::handshake).toList(),
        CanonicalJson.array(object, "identities").stream().map(EvidenceCodec::identity).toList(),
        CanonicalJson.array(object, "capabilities").stream()
            .map(EvidenceCodec::capability)
            .toList(),
        health);
  }

  /** Reads and parses a bounded regular evidence file. */
  public static EvidenceDocument read(Path path) throws IOException {
    return parse(DocumentFiles.read(path));
  }

  /** Writes a canonical evidence snapshot atomically. */
  public static void write(EvidenceDocument document, Path path) throws IOException {
    DocumentFiles.write(path, encode(document));
  }

  /**
   * Verifies provenance and references against the same bytes supplied to the ordinary CBOM reader.
   */
  public static Cbom.Document validatePair(EvidenceDocument evidence, byte[] cbomBytes) {
    if (!evidence.cbomHash().equals(CanonicalJson.hash(cbomBytes))) {
      throw new IllegalArgumentException("Evidence CBOM hash mismatch");
    }
    Cbom.Document cbom = Cbom.parse(cbomBytes);
    if (!evidence.runId().equals(cbom.properties().get(EvidenceDocument.RUN_PROPERTY))) {
      throw new IllegalArgumentException("Evidence run nonce mismatch");
    }
    Map<String, Inventory.Entry> entries = new HashMap<>();
    for (Inventory.Entry entry : cbom.inventory().entries()) {
      entries.put(entry.asset().ref(), entry);
    }
    Map<String, Map<Occurrence, Long>> counts = new HashMap<>();
    Map<String, Long> totals = new HashMap<>();
    boolean saturated =
        entries.values().stream().anyMatch(entry -> entry.total() == Long.MAX_VALUE);
    for (EvidenceDocument.Observation observation : evidence.observations()) {
      Inventory.Entry entry = entries.get(observation.assetRef());
      if (entry == null || !entry.occurrences().containsKey(observation.occurrence())) {
        throw new IllegalArgumentException(
            "Evidence asset or effective occurrence is absent from CBOM");
      }
      Map<Occurrence, Long> sites =
          counts.computeIfAbsent(observation.assetRef(), ignored -> new HashMap<>());
      long total = totals.getOrDefault(observation.assetRef(), 0L);
      saturated |= total >= Long.MAX_VALUE - observation.count();
      totals.put(observation.assetRef(), Inventory.saturatingAdd(total, observation.count()));
      long previous = sites.getOrDefault(observation.occurrence(), 0L);
      saturated |= previous >= Long.MAX_VALUE - observation.count();
      sites.put(observation.occurrence(), Inventory.saturatingAdd(previous, observation.count()));
    }
    if (saturated && evidence.health().get(EvidenceDocument.Counter.SATURATED_COUNTS) == 0) {
      throw new IllegalArgumentException("Saturated evidence counts lack a health diagnostic");
    }
    for (EvidenceDocument.Handshake handshake : evidence.handshakes()) {
      if (!entries.keySet().containsAll(handshake.relatedAssetRefs())) {
        throw new IllegalArgumentException("Handshake references an absent CBOM asset");
      }
    }
    boolean uncertain =
        evidence.health().get(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED) > 0
            || evidence.health().get(EvidenceDocument.Counter.DRAIN_TIMEOUT) > 0
            || evidence.health().get(EvidenceDocument.Counter.SATURATED_COUNTS) > 0;
    if (!uncertain) {
      for (Map.Entry<String, Inventory.Entry> entry : entries.entrySet()) {
        Map<Occurrence, Long> observed = counts.getOrDefault(entry.getKey(), Map.of());
        for (Map.Entry<Occurrence, Long> site : entry.getValue().occurrences().entrySet()) {
          if (!Objects.equals(observed.getOrDefault(site.getKey(), 0L), site.getValue())) {
            throw new IllegalArgumentException(
                "Evidence counts do not conserve CBOM occurrence totals");
          }
        }
      }
    }
    return cbom;
  }

  /** Builds the shared v1 identity fragment for evidence and assessment output. */
  public static Map<String, Object> identity(EvidenceDocument.IdentityMapping value) {
    Map<String, Object> result = new TreeMap<>();
    result.put("id", value.id());
    result.put("deploymentId", value.deploymentId());
    result.put("loaderId", value.loaderId());
    result.put("sourceArtifact", value.sourceArtifact());
    result.put("applicationId", value.applicationId());
    result.put("status", value.status().name());
    result.put("provenance", value.provenance());
    return result;
  }

  private static EvidenceDocument.IdentityMapping identity(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(
        object,
        "id",
        "deploymentId",
        "loaderId",
        "sourceArtifact",
        "applicationId",
        "status",
        "provenance");
    return new EvidenceDocument.IdentityMapping(
        CanonicalJson.text(object, "id"),
        CanonicalJson.text(object, "deploymentId"),
        CanonicalJson.text(object, "loaderId"),
        CanonicalJson.text(object, "sourceArtifact"),
        CanonicalJson.text(object, "applicationId"),
        EvidenceDocument.IdentityStatus.valueOf(CanonicalJson.text(object, "status")),
        CanonicalJson.text(object, "provenance"));
  }

  /** Builds the shared v1 discovered-flow fragment for evidence and assessment output. */
  public static Map<String, Object> discoveredFlow(EvidenceDocument.DiscoveredFlow value) {
    Map<String, Object> result = new TreeMap<>();
    result.put("identityId", value.identityId());
    result.put("definition", ContextJson.flow(value.definition()));
    return result;
  }

  private static EvidenceDocument.DiscoveredFlow discoveredFlow(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "identityId", "definition");
    return new EvidenceDocument.DiscoveredFlow(
        CanonicalJson.text(object, "identityId"), ContextJson.flow(object.get("definition")));
  }

  /** Builds the shared v1 scopeRef fragment for evidence and assessment output. */
  public static Map<String, Object> scopeRef(EvidenceDocument.ScopeRef value) {
    Map<String, Object> result = new TreeMap<>();
    result.put("identityId", value.identityId());
    result.put("subject", ContextJson.subject(value.subject()));
    return result;
  }

  private static EvidenceDocument.ScopeRef scopeRef(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "identityId", "subject");
    return new EvidenceDocument.ScopeRef(
        CanonicalJson.text(object, "identityId"), ContextJson.subject(object.get("subject")));
  }

  /** Builds the shared v1 scope fragment for evidence and assessment output. */
  public static Map<String, Object> scope(EvidenceDocument.ScopeContext value) {
    Map<String, Object> result = new TreeMap<>();
    result.put("identityId", value.identityId());
    result.put("path", value.path().stream().map(EvidenceCodec::scopeRef).toList());
    result.put("association", value.association().name());
    return result;
  }

  private static EvidenceDocument.ScopeContext scope(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "identityId", "path", "association");
    return new EvidenceDocument.ScopeContext(
        CanonicalJson.text(object, "identityId"),
        CanonicalJson.array(object, "path").stream().map(EvidenceCodec::scopeRef).toList(),
        EvidenceDocument.Association.valueOf(CanonicalJson.text(object, "association")));
  }

  private static Map<String, Object> observation(EvidenceDocument.Observation value) {
    Map<String, Object> result = new TreeMap<>();
    result.put("assetRef", value.assetRef());
    result.put("occurrence", occurrence(value.occurrence()));
    result.put("context", scope(value.context()));
    result.put("count", value.count());
    result.put("projectFrame", ContextJson.binding(value.projectFrame()));
    return result;
  }

  private static EvidenceDocument.Observation observation(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "assetRef", "occurrence", "context", "count", "projectFrame");
    return new EvidenceDocument.Observation(
        CanonicalJson.text(object, "assetRef"),
        occurrence(object.get("occurrence")),
        scope(object.get("context")),
        CanonicalJson.integer(object, "count"),
        ContextJson.binding(object.get("projectFrame")));
  }

  /** Builds the shared v1 capability fragment for evidence and assessment output. */
  public static Map<String, Object> capability(EvidenceDocument.Capability value) {
    Map<String, Object> result = new TreeMap<>();
    result.put("feature", value.feature());
    result.put("status", value.status().name());
    result.put("detail", value.detail());
    return result;
  }

  private static EvidenceDocument.Capability capability(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "feature", "status", "detail");
    return new EvidenceDocument.Capability(
        CanonicalJson.text(object, "feature"),
        EvidenceDocument.CapabilityStatus.valueOf(CanonicalJson.text(object, "status")),
        CanonicalJson.text(object, "detail"));
  }

  private static Map<String, Object> negotiation(EvidenceDocument.Negotiation value) {
    Map<String, Object> result = new TreeMap<>();
    result.put("protocol", value.protocol());
    result.put("cipherSuite", value.cipherSuite());
    result.put("selectedGroupId", value.selectedGroupId());
    result.put("selectedGroupName", value.selectedGroupName());
    result.put("localSignatureScheme", value.localSignatureScheme());
    result.put("peerSignatureScheme", value.peerSignatureScheme());
    return result;
  }

  private static EvidenceDocument.Negotiation negotiation(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(
        object,
        "protocol",
        "cipherSuite",
        "selectedGroupId",
        "selectedGroupName",
        "localSignatureScheme",
        "peerSignatureScheme");
    return new EvidenceDocument.Negotiation(
        CanonicalJson.text(object, "protocol"),
        CanonicalJson.text(object, "cipherSuite"),
        intField(object, "selectedGroupId"),
        CanonicalJson.text(object, "selectedGroupName"),
        CanonicalJson.text(object, "localSignatureScheme"),
        CanonicalJson.text(object, "peerSignatureScheme"));
  }

  private static Map<String, Object> attempt(EvidenceDocument.AttemptRef value) {
    Map<String, Object> result = new TreeMap<>();
    result.put("connectionId", value.connectionId());
    result.put("attempt", value.attempt());
    return result;
  }

  private static EvidenceDocument.AttemptRef attempt(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "connectionId", "attempt");
    return new EvidenceDocument.AttemptRef(
        CanonicalJson.text(object, "connectionId"), CanonicalJson.integer(object, "attempt"));
  }

  private static Map<String, Object> resumption(EvidenceDocument.Resumption value) {
    Map<String, Object> result = new TreeMap<>();
    result.put("resumed", value.resumed().name());
    result.put("freshKeyExchange", value.freshKeyExchange().name());
    result.put("pskOrigin", value.pskOrigin() == null ? null : attempt(value.pskOrigin()));
    return result;
  }

  private static EvidenceDocument.Resumption resumption(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "resumed", "freshKeyExchange", "pskOrigin");
    return new EvidenceDocument.Resumption(
        EvidenceDocument.Flag.valueOf(CanonicalJson.text(object, "resumed")),
        EvidenceDocument.Flag.valueOf(CanonicalJson.text(object, "freshKeyExchange")),
        object.get("pskOrigin") == null ? null : attempt(object.get("pskOrigin")));
  }

  /** Builds the shared v1 handshake fragment for evidence and assessment output. */
  public static Map<String, Object> handshake(EvidenceDocument.Handshake value) {
    Map<String, Object> result = new TreeMap<>();
    result.put("connectionId", value.connectionId());
    result.put("attempt", value.attempt());
    result.put("transport", value.transport().name());
    result.put("role", value.role().name());
    result.put("outcome", value.outcome().name());
    result.put("negotiation", negotiation(value.negotiation()));
    result.put("resumption", resumption(value.resumption()));
    result.put("relatedAssetRefs", value.relatedAssetRefs());
    result.put("adapter", value.adapter());
    result.put("diagnostics", value.diagnostics());
    result.put("initiation", scope(value.initiation()));
    return result;
  }

  private static EvidenceDocument.Handshake handshake(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(
        object,
        "connectionId",
        "attempt",
        "transport",
        "role",
        "outcome",
        "negotiation",
        "resumption",
        "relatedAssetRefs",
        "adapter",
        "diagnostics",
        "initiation");
    return new EvidenceDocument.Handshake(
        CanonicalJson.text(object, "connectionId"),
        CanonicalJson.integer(object, "attempt"),
        EvidenceDocument.Transport.valueOf(CanonicalJson.text(object, "transport")),
        EvidenceDocument.PeerRole.valueOf(CanonicalJson.text(object, "role")),
        EvidenceDocument.Outcome.valueOf(CanonicalJson.text(object, "outcome")),
        negotiation(object.get("negotiation")),
        resumption(object.get("resumption")),
        strings(object, "relatedAssetRefs"),
        CanonicalJson.text(object, "adapter"),
        strings(object, "diagnostics"),
        scope(object.get("initiation")));
  }

  /** Builds the shared v1 occurrence fragment for evidence and assessment output. */
  public static Map<String, Object> occurrence(Occurrence value) {
    Map<String, Object> result = new TreeMap<>();
    result.put("location", value.location());
    result.put("symbol", value.symbol());
    result.put("line", value.line());
    result.put("via", value.via());
    return result;
  }

  private static Occurrence occurrence(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "location", "symbol", "line", "via");
    String location =
        ContextModel.text(
            CanonicalJson.text(object, "location"), "location", Names.MAX_LENGTH, false);
    String symbol = nullableText(object, "symbol");
    String via = nullableText(object, "via");
    int line = intField(object, "line");
    if (line < -1) {
      throw new IllegalArgumentException("Invalid occurrence line");
    }
    return new Occurrence(location, symbol, line, via);
  }

  private static String nullableText(Map<String, Object> object, String key) {
    return object.get(key) == null
        ? null
        : ContextModel.text(CanonicalJson.text(object, key), key, Names.MAX_LENGTH, false);
  }

  private static List<String> strings(Map<String, Object> object, String key) {
    List<String> result = new ArrayList<>();
    for (Object item : CanonicalJson.array(object, key)) {
      if (!(item instanceof String text)) {
        throw new IllegalArgumentException("Expected string array " + key);
      }
      result.add(text);
    }
    return List.copyOf(result);
  }

  private static int intField(Map<String, Object> object, String key) {
    long value = CanonicalJson.integer(object, key);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Integer field out of range " + key);
    }
    return (int) value;
  }
}
