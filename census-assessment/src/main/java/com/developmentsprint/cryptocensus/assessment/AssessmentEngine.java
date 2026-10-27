package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.ContextJson;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Policy;
import com.developmentsprint.cryptocensus.core.TechnicalFacts;
import com.developmentsprint.cryptocensus.core.TlsPolicy;
import com.developmentsprint.cryptocensus.core.Version;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Offline deterministic assessment; no business-policy evaluation runs in the instrumented JVM. */
public final class AssessmentEngine {
  private AssessmentEngine() {}

  /**
   * Validates paired evidence, reconciles every applicable flow and builds an advisory report.
   * Unobserved code and missing association remain gaps; algorithm presence never validates
   * readiness.
   */
  public static AssessmentReport assess(
      List<AssessmentInputs.Input> inputs, ContextDocument context, PolicyProfile profile) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(profile, "profile");
    List<AssessmentInputs.Run> runs = AssessmentInputs.load(inputs);
    AssessmentContext declarations = new AssessmentContext(context, runs);
    List<AssessmentRows.Finding> findings = new ArrayList<>();
    List<FlowSummaries.BindingRun> bindings = new ArrayList<>();
    List<Map<String, Object>> provenance = new ArrayList<>();
    Map<String, Boolean> coverage = new HashMap<>();
    Set<String> gaps = new TreeSet<>();
    if (runs.isEmpty()) {
      gaps.add("NO_INPUT_RUNS");
    }
    boolean anyObservation = false;
    Policy technicalPolicy = new Policy();
    for (AssessmentInputs.Run run : runs) {
      List<String> collectionGaps = collectionGaps(run);
      gaps.addAll(collectionGaps);
      FlowAttribution.Result attributed = FlowAttribution.attribute(run, context);
      gaps.addAll(attributed.gaps());
      attributed
          .bindings()
          .forEach(binding -> bindings.add(new FlowSummaries.BindingRun(run.id(), binding)));
      boolean incompleteCoverage =
          run.incomplete()
              || !attributed.gaps().isEmpty()
              || attributed.uses().stream().anyMatch(use -> use.subject() == null);
      coverage.put(run.id(), incompleteCoverage);
      Map<String, Inventory.Entry> assets = new HashMap<>();
      Map<String, TechnicalFacts> facts = new HashMap<>();
      for (Inventory.Entry entry : run.entries()) {
        assets.put(entry.asset().ref(), entry);
        facts.put(entry.asset().ref(), technicalPolicy.facts(entry.asset()));
        anyObservation |= entry.total() > 0;
      }
      for (FlowAttribution.Use use : attributed.uses()) {
        Inventory.Entry asset = assets.get(use.assetRef());
        if (asset == null) {
          throw new IllegalArgumentException("Attribution references an absent asset");
        }
        TechnicalFacts technical = facts.get(use.assetRef());
        final PriorityRules.Decision decision =
            PriorityRules.evaluate(
                technical,
                declarations.fields(run.id(), use.identityId(), use.subject()),
                use.count() == null ? asset.total() > 0 : use.count() > 0,
                use.subject() != null && !declarations.ambiguous(run.id(), use.identityId()),
                profile);
        List<String> findingGaps = new ArrayList<>(collectionGaps);
        if (declarations.ambiguous(run.id(), use.identityId())) {
          findingGaps.add(DeploymentOwnership.GAP);
          gaps.add(DeploymentOwnership.GAP);
        }
        if (use.subject() == null) {
          findingGaps.add("FLOW_ASSOCIATION_NOT_ESTABLISHED");
        }
        if (use.count() == null) {
          findingGaps.add("COUNT_NOT_RECONCILED");
        }
        add(
            findings,
            AssessmentRows.primitive(use, asset.asset(), technical, decision, findingGaps));
      }
      for (AssessmentRows.Finding finding :
          TlsAssessment.findings(run, declarations, profile, collectionGaps)) {
        add(findings, finding);
      }
      anyObservation |= run.evidence() != null && !run.evidence().handshakes().isEmpty();
      provenance.add(run(run, collectionGaps, attributed.gaps()));
    }
    if (!anyObservation) {
      gaps.add("NO_CRYPTO_OBSERVATION");
    }
    findings.sort(AssessmentRows.ORDER);
    List<Map<String, Object>> flows =
        FlowSummaries.build(declarations, runs, findings, bindings, coverage);
    boolean incomplete =
        !gaps.isEmpty()
            || findings.stream().anyMatch(value -> value.contribution().incomplete())
            || flows.stream().anyMatch(value -> Boolean.TRUE.equals(value.get("incomplete")));
    Map<String, Object> result = new TreeMap<>();
    result.put("schemaVersion", AssessmentReport.VERSION);
    result.put("censusVersion", Version.get());
    result.put("ruleSetVersion", profile.ruleSetVersion());
    result.put("coreFactsVersion", profile.coreFactsVersion());
    result.put("tlsFactsVersion", TlsPolicy.VERSION);
    result.put("contextHash", ContextCodec.revision(context));
    result.put("profileHash", profile.revision());
    result.put("profile", CanonicalJson.decode(profile.encode()));
    result.put("runs", ContextJson.ordered(provenance));
    result.put("findings", findings.stream().map(AssessmentRows.Finding::wire).toList());
    result.put("flows", flows);
    result.put("incomplete", incomplete);
    result.put("gaps", List.copyOf(gaps));
    result.put("baseContext", CanonicalJson.decode(ContextCodec.encode(context)));
    result.put("baseFields", baseFields(context));
    result.put("emptyFieldHash", CanonicalJson.revision(null));
    result.put(
        "limitations",
        List.of(
            "Entry-point counts are not bytes protected, transaction counts or coverage of all"
                + " application code.",
            "Synchronous execution scope is not taint tracking or causal data-flow proof;"
                + " off-thread work may be missing.",
            "Readiness assertions are not authenticated validation or interoperability evidence.",
            "No action identified for an observed primitive says nothing about unobserved paths or"
                + " unassessed dependencies."));
    return AssessmentReport.of(result);
  }

  private static void add(List<AssessmentRows.Finding> findings, AssessmentRows.Finding finding) {
    if (findings.size() == FlowAttribution.MAX_USES) {
      throw new IllegalArgumentException("Assessment exceeds finding budget");
    }
    findings.add(finding);
  }

  private static List<String> collectionGaps(AssessmentInputs.Run run) {
    Set<String> gaps = new TreeSet<>(run.gaps());
    run.coreHealth()
        .forEach(
            (counter, count) -> {
              if (count > 0) {
                gaps.add("CORE_HEALTH:" + counter);
              }
            });
    if (run.evidence() != null) {
      run.evidence()
          .health()
          .forEach(
              (counter, count) -> {
                if (count > 0) {
                  gaps.add("ENRICHMENT_HEALTH:" + counter.name());
                }
              });
    }
    return List.copyOf(gaps);
  }

  private static Map<String, Object> run(
      AssessmentInputs.Run run, List<String> collectionGaps, List<String> attributionGaps) {
    Map<String, Object> result = new TreeMap<>();
    result.put("id", run.id());
    result.put("nonce", run.nonce());
    result.put("cbomHash", run.cbomHash());
    result.put("evidenceHash", run.evidenceHash());
    result.put("workloads", run.workloads());
    result.put("environments", run.environments());
    result.put("metadata", run.metadata());
    result.put("coreHealth", run.coreHealth());
    result.put("evidenceAccepted", run.evidence() != null);
    result.put("collectionGaps", collectionGaps);
    result.put("attributionGaps", attributionGaps);
    EvidenceDocument evidence = run.evidence();
    Map<String, Long> health = new TreeMap<>();
    if (evidence != null) {
      evidence
          .health()
          .forEach(
              (counter, count) -> {
                if (counter != EvidenceDocument.Counter.ANNOTATION_ERRORS || count != 0) {
                  health.put(counter.name(), count);
                }
              });
    }
    result.put("enrichmentHealth", health);
    result.put(
        "capabilities",
        evidence == null
            ? List.of()
            : ContextJson.ordered(
                evidence.capabilities().stream().map(EvidenceCodec::capability).toList()));
    result.put(
        "identities",
        evidence == null
            ? List.of()
            : ContextJson.ordered(
                evidence.identities().stream().map(EvidenceCodec::identity).toList()));
    result.put(
        "flows",
        evidence == null
            ? List.of()
            : ContextJson.ordered(
                evidence.flows().stream().map(EvidenceCodec::discoveredFlow).toList()));
    result.put(
        "handshakes",
        evidence == null
            ? List.of()
            : ContextJson.ordered(
                evidence.handshakes().stream().map(EvidenceCodec::handshake).toList()));
    return result;
  }

  private static List<Map<String, Object>> baseFields(ContextDocument context) {
    List<Map<String, Object>> fields = new ArrayList<>();
    ContextChanges.fields(context)
        .forEach(
            (key, value) ->
                fields.add(
                    Map.of(
                        "section",
                        key.section().name(),
                        "id",
                        key.id(),
                        "value",
                        value,
                        "hash",
                        CanonicalJson.revision(value))));
    return ContextJson.ordered(fields);
  }
}
