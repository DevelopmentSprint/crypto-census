package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.Assessment;
import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.ContextJson;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Policy;
import com.developmentsprint.cryptocensus.core.Risk;
import com.developmentsprint.cryptocensus.core.TechnicalFacts;
import com.developmentsprint.cryptocensus.core.TlsFacts;
import com.developmentsprint.cryptocensus.core.TlsPolicy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Capability-qualified relationship assessment, separate from the original primitive findings. */
final class TlsAssessment {
  private TlsAssessment() {}

  private enum Axis {
    CONFIDENTIALITY,
    LOCAL_AUTHENTICATION,
    PEER_AUTHENTICATION
  }

  static List<AssessmentRows.Finding> findings(
      AssessmentInputs.Run run,
      AssessmentContext context,
      PolicyProfile profile,
      List<String> collectionGaps) {
    EvidenceDocument evidence = run.evidence();
    if (evidence == null) {
      return List.of();
    }
    TlsPolicy policy = new TlsPolicy(evidence.handshakes());
    boolean supported =
        evidence.capabilities().stream()
                .anyMatch(
                    capability ->
                        "tls-sunjsse-1.3-correlation".equals(capability.feature())
                            && capability.status() == EvidenceDocument.CapabilityStatus.SUPPORTED)
            && evidence.health().get(EvidenceDocument.Counter.RECORDING_FAILURES) == 0
            && evidence.health().get(EvidenceDocument.Counter.TRANSFORM_ERRORS) == 0;
    boolean scopeValid =
        evidence.capabilities().stream()
                .anyMatch(
                    capability ->
                        "annotation-scopes".equals(capability.feature())
                            && capability.status() != EvidenceDocument.CapabilityStatus.UNAVAILABLE)
            && evidence.health().get(EvidenceDocument.Counter.SCOPE_INTEGRITY) == 0
            && evidence.health().get(EvidenceDocument.Counter.SCOPE_RESTORE_FAILURES) == 0;
    List<AssessmentRows.Finding> result = new ArrayList<>();
    for (EvidenceDocument.Handshake handshake : evidence.handshakes()) {
      TlsFacts facts = policy.facts(handshake);
      boolean qualified = supported && handshake.outcome() == EvidenceDocument.Outcome.COMPLETED;
      List<String> gaps = new ArrayList<>(collectionGaps);
      if (!supported) {
        gaps.add("TLS_CAPABILITY_OR_HEALTH_NOT_ESTABLISHED");
      }
      if (handshake.outcome() != EvidenceDocument.Outcome.COMPLETED) {
        gaps.add("TLS_HANDSHAKE_NOT_COMPLETED");
      }
      List<EvidenceDocument.ScopeRef> scopes =
          scopeValid
                  && handshake.initiation().association()
                      == EvidenceDocument.Association.SYNCHRONOUS_SCOPE
              ? List.copyOf(new LinkedHashSet<>(handshake.initiation().path()))
              : List.of();
      if (scopes.isEmpty()) {
        gaps.add("TLS_INITIATION_SCOPE_NOT_ESTABLISHED");
        for (Axis axis : Axis.values()) {
          result.add(finding(run, handshake, facts, axis, null, context, profile, qualified, gaps));
        }
      } else {
        for (EvidenceDocument.ScopeRef scope : scopes) {
          for (Axis axis : Axis.values()) {
            result.add(
                finding(run, handshake, facts, axis, scope, context, profile, qualified, gaps));
          }
        }
      }
      if (result.size() > FlowAttribution.MAX_USES) {
        throw new IllegalArgumentException("TLS assessment exceeds finding budget");
      }
    }
    return List.copyOf(result);
  }

  private static AssessmentRows.Finding finding(
      AssessmentInputs.Run run,
      EvidenceDocument.Handshake handshake,
      TlsFacts facts,
      Axis axis,
      EvidenceDocument.ScopeRef scope,
      AssessmentContext context,
      PolicyProfile profile,
      boolean qualified,
      List<String> collectionGaps) {
    TechnicalFacts.QuantumExposure exposure =
        qualified ? exposure(facts, axis) : TechnicalFacts.QuantumExposure.NOT_ESTABLISHED;
    TechnicalFacts.ObservedRole role =
        axis == Axis.CONFIDENTIALITY
            ? TechnicalFacts.ObservedRole.CONFIDENTIALITY
            : TechnicalFacts.ObservedRole.AUTHENTICITY;
    Risk risk =
        switch (exposure) {
          case VULNERABLE -> Risk.QUANTUM_VULNERABLE;
          case RESISTANT -> Risk.QUANTUM_SAFE;
          case NOT_ESTABLISHED -> Risk.UNKNOWN;
        };
    String description =
        axis == Axis.CONFIDENTIALITY
            ? "Negotiated TLS confidentiality dependency"
            : axis == Axis.LOCAL_AUTHENTICATION
                ? "Observed local TLS authentication signature"
                : "Observed peer TLS authentication signature";
    TechnicalFacts technical =
        new TechnicalFacts(
            new Assessment(risk, description + "; " + exposure.name(), -1, false),
            exposure,
            role,
            Policy.FACTS_VERSION);
    ContextModel.Subject subject = scope == null ? null : scope.subject();
    final PriorityRules.Decision decision =
        PriorityRules.evaluate(
            technical,
            context.fields(run.id(), scope == null ? "" : scope.identityId(), subject),
            true,
            scope != null,
            profile);
    List<String> gaps = new ArrayList<>(collectionGaps);
    if (exposure == TechnicalFacts.QuantumExposure.NOT_ESTABLISHED) {
      gaps.add("TLS_" + axis.name() + "_NOT_ESTABLISHED");
    }
    String bucket =
        CanonicalJson.revision(
            List.of("handshake", run.id(), handshake.connectionId(), handshake.attempt()));
    String identity = scope == null ? handshake.initiation().identityId() : scope.identityId();
    String id =
        CanonicalJson.revision(
            List.of(bucket, axis.name(), identity, subject == null ? "" : subject.ref()));
    List<EvidenceDocument.ScopeRef> path = handshake.initiation().path();
    boolean direct = scope == null || scope.equals(path.get(path.size() - 1));
    Map<String, Object> value = new TreeMap<>();
    value.put("id", id);
    value.put("kind", "TLS_" + axis.name());
    value.put("runId", run.id());
    value.put("bucketId", bucket);
    value.put("identityId", identity);
    value.put("subject", subject == null ? null : ContextJson.subject(subject));
    value.put(
        "handshakeRef",
        Map.of("connectionId", handshake.connectionId(), "attempt", handshake.attempt()));
    value.put("association", scope == null ? "UNVERIFIED" : "HANDSHAKE_INITIATION_SCOPE");
    value.put(
        "associationLimitation",
        "Initiating execution scope only; no request-to-connection or protected-data-flow proof.");
    value.put("direct", direct);
    value.put("entryPointCount", null);
    value.put("qualified", qualified);
    value.put("technical", AssessmentRows.technical(technical));
    value.put("tlsFactsVersion", facts.version());
    value.put("lineage", qualified ? facts.lineage().name() : TlsFacts.Lineage.UNKNOWN.name());
    value.put(
        "selectedKeyExchangeExposure",
        qualified
            ? facts.selectedKeyExchangeExposure().name()
            : TechnicalFacts.QuantumExposure.NOT_ESTABLISHED.name());
    value.put(
        "pskOriginExposure",
        qualified
            ? facts.pskOriginExposure().name()
            : TechnicalFacts.QuantumExposure.NOT_ESTABLISHED.name());
    value.put("limitations", facts.limitations().stream().distinct().sorted().toList());
    value.put("decision", AssessmentRows.decision(decision));
    value.put("gaps", gaps.stream().distinct().sorted().toList());
    AssessmentRows.Contribution contribution =
        new AssessmentRows.Contribution(
            subject,
            run.id(),
            identity,
            bucket,
            1L,
            direct,
            false,
            decision.displayPriority(),
            decision.incomplete() || !gaps.isEmpty(),
            id);
    return AssessmentRows.finding(
        value,
        contribution,
        subject,
        "",
        decision.ruleId(),
        path.stream().map(EvidenceCodec::scopeRef).toList(),
        List.of(handshake.connectionId(), handshake.attempt(), axis.name()));
  }

  private static TechnicalFacts.QuantumExposure exposure(TlsFacts facts, Axis axis) {
    return switch (axis) {
      case CONFIDENTIALITY -> facts.confidentialityExposure();
      case LOCAL_AUTHENTICATION -> facts.localAuthenticationExposure();
      case PEER_AUTHENTICATION -> facts.peerAuthenticationExposure();
    };
  }
}
