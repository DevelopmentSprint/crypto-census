package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.ContextJson;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Builds business totals from unique event buckets while retaining deployment-specific views. */
final class FlowSummaries {
  private FlowSummaries() {}

  record BindingRun(String runId, FlowAttribution.BindingReview review) {}

  private record Instance(String runId, String identityId) {}

  private record Count(long value, boolean saturated) {}

  static List<Map<String, Object>> build(
      AssessmentContext context,
      List<AssessmentInputs.Run> runs,
      List<AssessmentRows.Finding> findings,
      List<BindingRun> bindings,
      Map<String, Boolean> coverage) {
    Map<ContextModel.Subject, List<AssessmentRows.Contribution>> contributions = new HashMap<>();
    for (AssessmentRows.Finding finding : findings) {
      AssessmentRows.Contribution contribution = finding.contribution();
      if (contribution.subject() != null) {
        contributions
            .computeIfAbsent(contribution.subject(), ignored -> new ArrayList<>())
            .add(contribution);
      }
    }
    List<Map<String, Object>> summaries = new ArrayList<>();
    for (ContextModel.Subject subject : context.subjects()) {
      List<AssessmentRows.Contribution> uses = contributions.getOrDefault(subject, List.of());
      Map<String, Object> summary = metrics(uses);
      summary.put("subject", ContextJson.subject(subject));
      summary.put("label", context.label(subject));
      summary.put("labels", context.labels(subject));
      summary.put("state", context.state(subject).name());
      summary.put(
          "savedContextFields",
          context.fields(subject).values().stream()
              .sorted(java.util.Comparator.comparing(value -> value.field().name()))
              .map(AssessmentRows::field)
              .toList());
      summary.put(
          "findingIds",
          uses.stream().map(AssessmentRows.Contribution::findingId).distinct().sorted().toList());
      Map<String, Object> readiness = readiness(context.readiness(subject));
      summary.put("readiness", readiness);
      boolean readinessIncomplete =
          !com.developmentsprint.cryptocensus.core.CanonicalJson.array(readiness, "gaps").isEmpty();
      summary.put("readinessIncomplete", readinessIncomplete);
      boolean coverageIncomplete =
          uses.isEmpty() || uses.stream().anyMatch(use -> coverage.getOrDefault(use.runId(), true));
      summary.put("coverageIncomplete", coverageIncomplete);
      summary.put(
          "incomplete",
          coverageIncomplete
              || readinessIncomplete
              || Boolean.TRUE.equals(summary.get("assessmentIncomplete")));
      List<Map<String, Object>> bindingValues =
          bindings.stream()
              .filter(value -> value.review().subject().equals(subject))
              .map(
                  value ->
                      Map.<String, Object>of(
                          "runId",
                          value.runId(),
                          "binding",
                          ContextJson.binding(value.review().binding()),
                          "status",
                          value.review().status().name()))
              .toList();
      summary.put("bindings", ContextJson.ordered(bindingValues));
      Map<Instance, List<AssessmentRows.Contribution>> instances = new HashMap<>();
      for (AssessmentRows.Contribution use : uses) {
        instances
            .computeIfAbsent(
                new Instance(use.runId(), use.identityId()), ignored -> new ArrayList<>())
            .add(use);
      }
      for (AssessmentInputs.Run run : runs) {
        if (run.evidence() != null) {
          run.evidence().flows().stream()
              .filter(flow -> flow.definition().subject().equals(subject))
              .forEach(
                  flow ->
                      instances.computeIfAbsent(
                          new Instance(run.id(), flow.identityId()), ignored -> new ArrayList<>()));
        }
      }
      if (instances.values().stream().anyMatch(List::isEmpty)) {
        summary.put("coverageIncomplete", true);
        summary.put("incomplete", true);
      }
      List<Map<String, Object>> instanceValues = new ArrayList<>();
      instances.forEach(
          (key, values) -> {
            Map<String, Object> instance = metrics(values);
            instance.put("runId", key.runId());
            instance.put("identityId", key.identityId());
            instance.put("identity", identity(runs, key));
            instance.put(
                "fields",
                context.fields(key.runId(), key.identityId(), subject).values().stream()
                    .sorted(java.util.Comparator.comparing(value -> value.field().name()))
                    .map(AssessmentRows::field)
                    .toList());
            instance.put(
                "coverageIncomplete", values.isEmpty() || coverage.getOrDefault(key.runId(), true));
            instanceValues.add(instance);
          });
      summary.put("instances", ContextJson.ordered(instanceValues));
      summaries.add(summary);
    }
    summaries.sort(
        java.util.Comparator.comparingInt(
                (Map<String, Object> value) ->
                    PriorityRules.Priority.valueOf((String) value.get("priority")).rank())
            .thenComparing(value -> ContextJson.subject(value.get("subject")).ref()));
    return List.copyOf(summaries);
  }

  private static Object identity(List<AssessmentInputs.Run> runs, Instance instance) {
    for (AssessmentInputs.Run run : runs) {
      if (run.id().equals(instance.runId()) && run.evidence() != null) {
        return run.evidence().identities().stream()
            .filter(value -> value.id().equals(instance.identityId()))
            .findFirst()
            .map(EvidenceCodec::identity)
            .orElse(null);
      }
    }
    return null;
  }

  private static Map<String, Object> metrics(List<AssessmentRows.Contribution> uses) {
    Map<String, Long> inclusive = new HashMap<>();
    Map<String, Long> direct = new HashMap<>();
    java.util.Set<String> handshakes = new TreeSet<>();
    boolean unknown = false;
    boolean incomplete = uses.isEmpty();
    PriorityRules.Priority priority = null;
    for (AssessmentRows.Contribution use : uses) {
      incomplete |= use.incomplete();
      if (priority == null || use.priority().rank() < priority.rank()) {
        priority = use.priority();
      }
      if (use.countable()) {
        if (use.count() == null) {
          unknown = true;
        } else {
          retain(inclusive, use.bucketId(), use.count());
          if (use.direct()) {
            retain(direct, use.bucketId(), use.count());
          }
        }
      } else {
        handshakes.add(use.bucketId());
      }
    }
    Count inclusiveCount = sum(inclusive.values());
    Count directCount = sum(direct.values());
    boolean observed = inclusiveCount.value() > 0 || !handshakes.isEmpty();
    boolean saturated = inclusiveCount.saturated() || directCount.saturated();
    Map<String, Object> result = new TreeMap<>();
    result.put("directCount", directCount.value());
    result.put("inclusiveCount", inclusiveCount.value());
    result.put("handshakeAttempts", handshakes.size());
    result.put("unknownCount", unknown);
    result.put("countsSaturated", saturated);
    result.put("observed", observed);
    result.put(
        "priority", (priority == null ? PriorityRules.Priority.NEEDS_CONTEXT : priority).name());
    result.put("assessmentIncomplete", incomplete || unknown || saturated || !observed);
    result.put("gaps", observed ? List.of() : List.of("NO_OBSERVATION"));
    return result;
  }

  private static void retain(Map<String, Long> counts, String bucket, long count) {
    Long previous = counts.putIfAbsent(bucket, count);
    if (previous != null && previous != count) {
      throw new IllegalArgumentException("Inconsistent event bucket counts");
    }
  }

  private static Count sum(java.util.Collection<Long> values) {
    long total = 0;
    boolean saturated = false;
    for (long value : values) {
      if (total >= Long.MAX_VALUE - value) {
        total = Long.MAX_VALUE;
        saturated = true;
      } else {
        total += value;
      }
    }
    return new Count(total, saturated);
  }

  private static Map<String, Object> readiness(ContextDocument.Readiness assertion) {
    Map<String, Object> result = new TreeMap<>();
    ContextDocument.ReadinessState effective = ContextDocument.ReadinessState.UNASSESSED;
    List<String> gaps = new ArrayList<>();
    if (assertion != null) {
      if (assertion.state() == ContextDocument.ReadinessState.VALIDATED_IN_TESTED_SCOPE) {
        gaps.add("VALIDATION_EVIDENCE_NOT_VERIFIED");
      } else if (assertion.state() != ContextDocument.ReadinessState.UNASSESSED) {
        if (assertion.note().isBlank()) {
          gaps.add("READINESS_PREREQUISITES_NOT_DECLARED");
        } else {
          effective = assertion.state();
        }
      }
    }
    result.put("effectiveState", effective.name());
    result.put("assertedState", assertion == null ? null : assertion.state().name());
    result.put("provenance", assertion == null ? null : ContextJson.source(assertion.source()));
    result.put("note", assertion == null ? "" : assertion.note());
    result.put(
        "validationRefs",
        assertion == null ? List.of() : assertion.validationRefs().stream().sorted().toList());
    result.put("basis", assertion == null ? "UNASSESSED" : "USER_OR_IMPORT_ASSERTION");
    result.put("gaps", gaps);
    return result;
  }
}
