package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.ContextJson;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.TechnicalFacts;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared report projections; presentation consumes these results rather than evaluating rules. */
final class AssessmentRows {
  private AssessmentRows() {}

  record Contribution(
      ContextModel.Subject subject,
      String runId,
      String identityId,
      String bucketId,
      Long count,
      boolean direct,
      boolean countable,
      PriorityRules.Priority priority,
      boolean incomplete,
      String findingId) {}

  record Finding(
      Map<String, Object> wire,
      Contribution contribution,
      String subjectRef,
      String assetRef,
      String ruleId,
      String pathKey,
      String occurrenceKey,
      String contentHash) {}

  static final java.util.Comparator<Finding> ORDER =
      java.util.Comparator.comparingInt(
              (Finding finding) -> finding.contribution().priority().rank())
          .thenComparing(Finding::subjectRef)
          .thenComparing(Finding::assetRef)
          .thenComparing(Finding::ruleId)
          .thenComparing(Finding::pathKey)
          .thenComparing(Finding::occurrenceKey)
          .thenComparing(Finding::contentHash);

  static Finding primitive(
      FlowAttribution.Use use,
      CryptoAsset asset,
      TechnicalFacts facts,
      PriorityRules.Decision decision,
      List<String> gaps) {
    Map<String, Object> value = new TreeMap<>();
    String id =
        CanonicalJson.revision(
            List.of(
                "primitive",
                use.runId(),
                use.bucketId(),
                use.identityId(),
                use.subject() == null ? "" : use.subject().ref()));
    value.put("id", id);
    value.put("kind", "PRIMITIVE");
    value.put("runId", use.runId());
    value.put("bucketId", use.bucketId());
    value.put("identityId", use.identityId());
    value.put("subject", use.subject() == null ? null : ContextJson.subject(use.subject()));
    value.put("asset", asset(asset));
    value.put("occurrence", EvidenceCodec.occurrence(use.occurrence()));
    value.put("count", use.count());
    value.put("path", use.path().stream().map(EvidenceCodec::scopeRef).toList());
    value.put("association", use.association().name());
    value.put("direct", use.direct());
    value.put("projectFrame", ContextJson.binding(use.projectFrame()));
    value.put("technical", technical(facts));
    value.put("decision", decision(decision));
    value.put("gaps", gaps.stream().distinct().sorted().toList());
    boolean incomplete = decision.incomplete() || !gaps.isEmpty();
    Contribution contribution =
        new Contribution(
            use.subject(),
            use.runId(),
            use.identityId(),
            use.bucketId(),
            use.count(),
            use.direct(),
            true,
            decision.displayPriority(),
            incomplete,
            id);
    return finding(
        value,
        contribution,
        use.subject(),
        asset.ref(),
        decision.ruleId(),
        use.path().stream().map(EvidenceCodec::scopeRef).toList(),
        EvidenceCodec.occurrence(use.occurrence()));
  }

  static Finding finding(
      Map<String, Object> wire,
      Contribution contribution,
      ContextModel.Subject subject,
      String assetRef,
      String ruleId,
      Object path,
      Object occurrence) {
    return new Finding(
        java.util.Collections.unmodifiableMap(new TreeMap<>(wire)),
        contribution,
        subject == null ? "" : subject.ref(),
        assetRef,
        ruleId,
        new String(CanonicalJson.encode(path), java.nio.charset.StandardCharsets.UTF_8),
        new String(CanonicalJson.encode(occurrence), java.nio.charset.StandardCharsets.UTF_8),
        CanonicalJson.revision(wire));
  }

  static Map<String, Object> technical(TechnicalFacts facts) {
    return Map.of(
        "risk",
        facts.assessment().risk().name(),
        "reason",
        facts.assessment().reason(),
        "nistQuantumSecurityLevel",
        facts.assessment().nistQuantumSecurityLevel(),
        "cnsa2",
        facts.assessment().cnsa2(),
        "quantumExposure",
        facts.quantumExposure().name(),
        "role",
        facts.role().name(),
        "version",
        facts.version());
  }

  static Map<String, Object> decision(PriorityRules.Decision decision) {
    return Map.of(
        "ruleId",
        decision.ruleId(),
        "businessPriority",
        decision.businessPriority().name(),
        "displayPriority",
        decision.displayPriority().name(),
        "technicalRemediation",
        decision.technicalRemediation(),
        "incomplete",
        decision.incomplete(),
        "reasons",
        decision.reasons(),
        "missingFields",
        decision.missingFields(),
        "nextAction",
        decision.nextAction());
  }

  static Map<String, Object> asset(CryptoAsset asset) {
    Map<String, Object> value = new TreeMap<>();
    value.put("ref", asset.ref());
    value.put("primitive", asset.primitive().name());
    value.put("algorithm", asset.algorithm());
    value.put("keySize", asset.keySize());
    value.put("provider", asset.provider());
    value.put("operation", asset.operation() == null ? null : asset.operation().name());
    value.put("mode", asset.mode());
    value.put("padding", asset.padding());
    value.put("protocolVersion", asset.protocolVersion());
    return value;
  }

  static Map<String, Object> field(Reconciliation.Result result) {
    return Map.of(
        "subject",
        ContextJson.subject(result.subject()),
        "field",
        result.field().name(),
        "status",
        result.status().name(),
        "value",
        result.value(),
        "sources",
        ContextJson.ordered(result.sources().stream().map(ContextJson::declaration).toList()),
        "sourceRefs",
        ContextJson.ordered(
            result.sources().stream()
                .map(ContextDocument.SourceRef::of)
                .map(ContextCodec::sourceRef)
                .toList()));
  }
}
