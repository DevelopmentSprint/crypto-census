package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Thread-confined indexes for one assessment; reconciliation remains in the canonical resolver. */
final class AssessmentContext {
  private record Deployment(String runId, String identityId) {}

  private record ResolutionKey(Deployment deployment, ContextModel.Subject subject) {}

  private final ContextDocument context;
  private final Set<Deployment> ambiguous = new HashSet<>();
  private final Map<Deployment, Map<ContextModel.Subject, List<ContextModel.Declaration>>>
      observed = new HashMap<>();
  private final Map<ContextModel.Subject, List<ContextModel.Declaration>> saved = new HashMap<>();
  private final Map<ContextModel.Subject, Set<String>> labels = new HashMap<>();
  private final Map<ResolutionKey, Map<ContextModel.Field, Reconciliation.Result>> resolved =
      new HashMap<>();
  private final Map<ContextModel.Subject, ContextDocument.Flow> flows = new HashMap<>();
  private final Map<ContextModel.Subject, ContextDocument.Readiness> readiness = new HashMap<>();

  AssessmentContext(ContextDocument context, List<AssessmentInputs.Run> runs) {
    this.context = context;
    Set<ContextModel.Declaration> declarations = new HashSet<>();
    for (ContextDocument.Flow flow : context.flows()) {
      flows.put(flow.subject(), flow);
      addLabel(flow.subject(), flow.label());
    }
    for (ContextModel.Declaration declaration : context.declarations()) {
      declarations.add(declaration);
      add(saved, declaration);
    }
    for (ContextDocument.Readiness assertion : context.readiness()) {
      readiness.put(assertion.subject(), assertion);
      known(assertion.subject());
    }
    context.resolutions().forEach(value -> known(value.subject()));
    for (AssessmentInputs.Run run : runs) {
      DeploymentOwnership.ambiguous(run.evidence())
          .forEach(identity -> ambiguous.add(new Deployment(run.id(), identity)));
      if (run.evidence() != null) {
        for (EvidenceDocument.DiscoveredFlow flow : run.evidence().flows()) {
          addLabel(flow.definition().subject(), flow.definition().label());
          for (ContextModel.Declaration declaration : flow.definition().declarations()) {
            if (declarations.add(declaration)) {
              if (declarations.size() > ContextModel.MAX_DECLARATIONS) {
                throw new IllegalArgumentException("Assessment declaration budget exceeded");
              }
            }
            Map<ContextModel.Subject, List<ContextModel.Declaration>> deployment =
                observed.computeIfAbsent(
                    new Deployment(run.id(), flow.identityId()), ignored -> new HashMap<>());
            if (!deployment.getOrDefault(declaration.subject(), List.of()).contains(declaration)) {
              add(deployment, declaration);
            }
          }
        }
      }
    }
    if (declarations.size() > ContextModel.MAX_DECLARATIONS) {
      throw new IllegalArgumentException("Assessment declaration budget exceeded");
    }
  }

  private void add(
      Map<ContextModel.Subject, List<ContextModel.Declaration>> index,
      ContextModel.Declaration declaration) {
    index.computeIfAbsent(declaration.subject(), ignored -> new ArrayList<>()).add(declaration);
    known(declaration.subject());
  }

  private void known(ContextModel.Subject subject) {
    if (!subject.applicationDefault()) {
      if (!labels.containsKey(subject) && labels.size() == ContextModel.MAX_FLOWS) {
        throw new IllegalArgumentException("Assessment flow budget exceeded");
      }
      labels.computeIfAbsent(subject, ignored -> new TreeSet<>());
    }
  }

  private void addLabel(ContextModel.Subject subject, String label) {
    known(subject);
    labels.get(subject).add(label);
  }

  List<ContextModel.Subject> subjects() {
    return labels.keySet().stream()
        .sorted(java.util.Comparator.comparing(ContextModel.Subject::ref))
        .toList();
  }

  Map<ContextModel.Field, Reconciliation.Result> fields(ContextModel.Subject subject) {
    if (subject == null) {
      return Map.of();
    }
    return fields("", "", subject);
  }

  Map<ContextModel.Field, Reconciliation.Result> fields(
      String runId, String identityId, ContextModel.Subject subject) {
    if (subject == null) {
      return Map.of();
    }
    return resolved.computeIfAbsent(
        new ResolutionKey(new Deployment(runId, identityId), subject), this::resolve);
  }

  boolean ambiguous(String runId, String identityId) {
    return ambiguous.contains(new Deployment(runId, identityId));
  }

  private Map<ContextModel.Field, Reconciliation.Result> resolve(ResolutionKey key) {
    if (ambiguous.contains(key.deployment())) {
      return Map.of();
    }
    ContextModel.Subject subject = key.subject();
    ContextModel.Subject application = new ContextModel.Subject(subject.applicationId(), "");
    List<ContextModel.Declaration> input =
        candidates(observed.getOrDefault(key.deployment(), Map.of()), subject, application);
    ContextDocument scoped =
        new ContextDocument(
            1,
            List.of(),
            candidates(saved, subject, application),
            context.resolutions().stream()
                .filter(value -> value.subject().equals(subject))
                .toList(),
            List.of());
    Map<ContextModel.Field, Reconciliation.Result> result = new EnumMap<>(ContextModel.Field.class);
    for (ContextModel.Field field : ContextModel.Field.values()) {
      result.put(field, Reconciliation.resolve(subject, field, input, scoped));
    }
    return Map.copyOf(result);
  }

  private static List<ContextModel.Declaration> candidates(
      Map<ContextModel.Subject, List<ContextModel.Declaration>> source,
      ContextModel.Subject subject,
      ContextModel.Subject application) {
    List<ContextModel.Declaration> result =
        new ArrayList<>(source.getOrDefault(subject, List.of()));
    if (!subject.equals(application)) {
      result.addAll(source.getOrDefault(application, List.of()));
    }
    return result;
  }

  String label(ContextModel.Subject subject) {
    ContextDocument.Flow flow = flows.get(subject);
    if (flow != null) {
      return flow.label();
    }
    return labels.get(subject).stream().findFirst().orElse(subject.flowId());
  }

  List<String> labels(ContextModel.Subject subject) {
    return List.copyOf(labels.get(subject));
  }

  ContextDocument.State state(ContextModel.Subject subject) {
    ContextDocument.Flow flow = flows.get(subject);
    return flow == null ? ContextDocument.State.ACTIVE : flow.state();
  }

  ContextDocument.Readiness readiness(ContextModel.Subject subject) {
    return readiness.get(subject);
  }
}
