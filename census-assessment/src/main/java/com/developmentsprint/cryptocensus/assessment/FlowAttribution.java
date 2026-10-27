package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact execution/binding attribution; it never infers protected-data flow from nearby events. */
public final class FlowAttribution {
  public static final int MAX_USES = 32768;

  private FlowAttribution() {}

  /** Strength and provenance of the association, independent of the asset's technical facts. */
  public enum Association {
    SYNCHRONOUS_SCOPE,
    EXPLICIT_BINDING,
    NO_SCOPE,
    SCOPE_LOST,
    UNVERIFIED
  }

  /**
   * One applicable flow for an event bucket; parents are inclusive relationships, not new events.
   */
  public record Use(
      String runId,
      String bucketId,
      String identityId,
      ContextModel.Subject subject,
      String assetRef,
      Occurrence occurrence,
      Long count,
      List<EvidenceDocument.ScopeRef> path,
      Association association,
      ContextModel.Binding projectFrame,
      boolean direct) {
    /** Preserves ordered paths; null count explicitly means unreconciled, not zero observations. */
    public Use {
      Objects.requireNonNull(runId, "runId");
      bucketId = ContextModel.hash(bucketId, "event bucket");
      identityId = ContextModel.text(identityId, "applicable identity", 128, true);
      Objects.requireNonNull(assetRef, "assetRef");
      Objects.requireNonNull(occurrence, "occurrence");
      if (count != null && count < 0) {
        throw new IllegalArgumentException("Negative use count");
      }
      path = List.copyOf(path);
      Objects.requireNonNull(association, "association");
      Objects.requireNonNull(projectFrame, "projectFrame");
      if ((subject != null)
          != (association == Association.SYNCHRONOUS_SCOPE
              || association == Association.EXPLICIT_BINDING)) {
        throw new IllegalArgumentException("Association and subject disagree");
      }
    }
  }

  /** Binding review distinguishes absence in this workload from an observed exact match. */
  public enum BindingStatus {
    OBSERVED,
    NOT_OBSERVED,
    INELIGIBLE,
    AMBIGUOUS
  }

  /** A saved binding is never silently moved to a similarly named method or artifact. */
  public record BindingReview(
      ContextModel.Subject subject, ContextModel.Binding binding, BindingStatus status) {}

  /** Bounded direct/inclusive rows and explicit binding/evidence review gaps. */
  public record Result(List<Use> uses, List<BindingReview> bindings, List<String> gaps) {
    /** Snapshots results without exposing mutable collector or context state. */
    public Result {
      uses = List.copyOf(uses);
      bindings = List.copyOf(bindings);
      gaps = List.copyOf(gaps);
    }
  }

  private record Site(String assetRef, Occurrence occurrence) {}

  private record BindingKey(ContextModel.Subject subject, ContextModel.Binding binding) {}

  /** Expands each scoped bucket once per distinct subject and marks only its active flow direct. */
  public static Result attribute(AssessmentInputs.Run run, ContextDocument context) {
    List<Use> uses = new ArrayList<>();
    Set<String> gaps = new java.util.TreeSet<>();
    Map<BindingKey, BindingStatus> reviews = new HashMap<>();
    for (ContextDocument.Flow flow : context.flows()) {
      for (ContextModel.Binding binding : flow.bindings()) {
        reviews.put(
            new BindingKey(flow.subject(), binding),
            eligible(binding) ? BindingStatus.NOT_OBSERVED : BindingStatus.INELIGIBLE);
      }
    }
    EvidenceDocument evidence = run.evidence();
    if (evidence == null) {
      for (Inventory.Entry entry : run.entries()) {
        entry
            .occurrences()
            .forEach(
                (site, count) ->
                    add(
                        uses,
                        new Use(
                            run.id(),
                            siteBucket(run.id(), entry.asset().ref(), site),
                            "",
                            null,
                            entry.asset().ref(),
                            site,
                            count,
                            List.of(),
                            Association.UNVERIFIED,
                            unknownFrame(site),
                            true)));
      }
      gaps.add("NO_TRUSTED_FLOW_EVIDENCE");
    } else {
      Set<String> ambiguous = DeploymentOwnership.ambiguous(evidence);
      if (!ambiguous.isEmpty()) {
        gaps.add(DeploymentOwnership.GAP);
      }
      Map<String, EvidenceDocument.IdentityMapping> identities = new HashMap<>();
      evidence.identities().forEach(identity -> identities.put(identity.id(), identity));
      boolean scopesSupported =
          evidence.health().get(EvidenceDocument.Counter.SCOPE_INTEGRITY) == 0
              && evidence.health().get(EvidenceDocument.Counter.SCOPE_RESTORE_FAILURES) == 0
              && evidence.capabilities().stream()
                  .anyMatch(
                      capability ->
                          "annotation-scopes".equals(capability.feature())
                              && capability.status()
                                  != EvidenceDocument.CapabilityStatus.UNAVAILABLE);
      Map<Site, Long> totals = new HashMap<>();
      for (EvidenceDocument.Observation observation : grouped(run.id(), evidence.observations())) {
        Site site = new Site(observation.assetRef(), observation.occurrence());
        totals.merge(site, observation.count(), FlowAttribution::sum);
        EvidenceDocument.IdentityMapping identity =
            identities.get(observation.context().identityId());
        List<ContextModel.Subject> matches =
            matching(
                identity,
                observation.projectFrame(),
                reviews,
                ambiguous.contains(observation.context().identityId()));
        if (observation.context().association() == EvidenceDocument.Association.SYNCHRONOUS_SCOPE
            && scopesSupported) {
          List<EvidenceDocument.ScopeRef> path = observation.context().path();
          EvidenceDocument.ScopeRef active = path.get(path.size() - 1);
          Set<EvidenceDocument.ScopeRef> subjects = new LinkedHashSet<>(path);
          for (EvidenceDocument.ScopeRef reference : subjects) {
            add(
                uses,
                use(
                    run.id(),
                    observation,
                    reference.identityId(),
                    reference.subject(),
                    Association.SYNCHRONOUS_SCOPE,
                    reference.equals(active)));
          }
        } else if (observation.context().association() == EvidenceDocument.Association.NO_SCOPE
            && matches.size() == 1) {
          add(
              uses,
              use(
                  run.id(),
                  observation,
                  observation.context().identityId(),
                  matches.get(0),
                  Association.EXPLICIT_BINDING,
                  true));
        } else {
          Association association =
              switch (observation.context().association()) {
                case SCOPE_LOST -> Association.SCOPE_LOST;
                case NO_SCOPE -> Association.NO_SCOPE;
                case SYNCHRONOUS_SCOPE -> Association.UNVERIFIED;
              };
          add(
              uses,
              use(
                  run.id(),
                  observation,
                  observation.context().identityId(),
                  null,
                  association,
                  true));
          if (matches.size() > 1) {
            gaps.add("AMBIGUOUS_SAVED_BINDING");
            matches.forEach(
                subject ->
                    reviews.put(
                        new BindingKey(subject, observation.projectFrame()),
                        BindingStatus.AMBIGUOUS));
          }
          if (association == Association.SCOPE_LOST || association == Association.UNVERIFIED) {
            gaps.add("SCOPE_ASSOCIATION_NOT_ESTABLISHED");
          }
        }
      }
      for (Inventory.Entry entry : run.entries()) {
        for (Map.Entry<Occurrence, Long> occurrence : entry.occurrences().entrySet()) {
          long attributed =
              totals.getOrDefault(new Site(entry.asset().ref(), occurrence.getKey()), 0L);
          boolean unobserved = occurrence.getValue() == 0;
          if (attributed != occurrence.getValue() || unobserved) {
            gaps.add(unobserved ? "NO_OBSERVATION" : "UNRECONCILED_OBSERVATION_COUNTS");
            add(
                uses,
                new Use(
                    run.id(),
                    siteBucket(run.id(), entry.asset().ref(), occurrence.getKey()),
                    "",
                    null,
                    entry.asset().ref(),
                    occurrence.getKey(),
                    unobserved ? 0L : null,
                    List.of(),
                    Association.UNVERIFIED,
                    unknownFrame(occurrence.getKey()),
                    true));
          }
        }
      }
    }
    List<BindingReview> bindings =
        reviews.entrySet().stream()
            .map(
                entry ->
                    new BindingReview(
                        entry.getKey().subject(), entry.getKey().binding(), entry.getValue()))
            .sorted(
                java.util.Comparator.comparing((BindingReview value) -> value.subject().ref())
                    .thenComparing(
                        value ->
                            com.developmentsprint.cryptocensus.core.CanonicalJson.revision(
                                com.developmentsprint.cryptocensus.core.ContextJson.binding(
                                    value.binding()))))
            .toList();
    return new Result(uses, bindings, List.copyOf(gaps));
  }

  private static List<ContextModel.Subject> matching(
      EvidenceDocument.IdentityMapping identity,
      ContextModel.Binding frame,
      Map<BindingKey, BindingStatus> reviews,
      boolean ambiguous) {
    if (identity == null
        || identity.status() != EvidenceDocument.IdentityStatus.RESOLVED
        || !identity.sourceArtifact().equals(frame.sourceArtifact())
        || !eligible(frame)) {
      return List.of();
    }
    Set<ContextModel.Subject> subjects = new HashSet<>();
    reviews.replaceAll(
        (key, status) -> {
          if (key.subject().applicationId().equals(identity.applicationId())
              && key.binding().equals(frame)) {
            if (ambiguous) {
              return BindingStatus.AMBIGUOUS;
            }
            subjects.add(key.subject());
            return status == BindingStatus.AMBIGUOUS ? status : BindingStatus.OBSERVED;
          }
          return status;
        });
    return subjects.stream()
        .sorted(java.util.Comparator.comparing(ContextModel.Subject::ref))
        .toList();
  }

  private static boolean eligible(ContextModel.Binding binding) {
    return !binding.descriptor().isEmpty()
        && !binding.sourceArtifact().isEmpty()
        && (binding.origin() == ContextModel.Origin.MAIN
            || binding.origin() == ContextModel.Origin.TEST);
  }

  private static Use use(
      String run,
      EvidenceDocument.Observation observation,
      String identityId,
      ContextModel.Subject subject,
      Association association,
      boolean direct) {
    return new Use(
        run,
        bucket(run, observation),
        identityId,
        subject,
        observation.assetRef(),
        observation.occurrence(),
        observation.count(),
        observation.context().path(),
        association,
        observation.projectFrame(),
        direct);
  }

  private record ObservationKey(
      String assetRef,
      Occurrence occurrence,
      EvidenceDocument.ScopeContext context,
      ContextModel.Binding frame) {}

  private static List<EvidenceDocument.Observation> grouped(
      String run, List<EvidenceDocument.Observation> observations) {
    Map<ObservationKey, Long> groups = new HashMap<>();
    for (EvidenceDocument.Observation observation : observations) {
      groups.merge(
          new ObservationKey(
              observation.assetRef(),
              observation.occurrence(),
              observation.context(),
              observation.projectFrame()),
          observation.count(),
          FlowAttribution::sum);
    }
    return groups.entrySet().stream()
        .map(
            entry ->
                new EvidenceDocument.Observation(
                    entry.getKey().assetRef(),
                    entry.getKey().occurrence(),
                    entry.getKey().context(),
                    entry.getValue(),
                    entry.getKey().frame()))
        .sorted(java.util.Comparator.comparing(value -> bucket(run, value)))
        .toList();
  }

  private static String bucket(String run, EvidenceDocument.Observation observation) {
    return com.developmentsprint.cryptocensus.core.CanonicalJson.revision(
        List.of(
            "observation",
            run,
            observation.assetRef(),
            siteValue(observation.occurrence()),
            observation.context().identityId(),
            observation.context().association().name(),
            observation.context().path().stream()
                .map(reference -> List.of(reference.identityId(), reference.subject().ref()))
                .toList(),
            com.developmentsprint.cryptocensus.core.ContextJson.binding(
                observation.projectFrame())));
  }

  private static String siteBucket(String run, String asset, Occurrence site) {
    return com.developmentsprint.cryptocensus.core.CanonicalJson.revision(
        List.of("unassigned-site", run, asset, siteValue(site)));
  }

  private static List<Object> siteValue(Occurrence site) {
    return java.util.Arrays.asList(site.location(), site.symbol(), site.line(), site.via());
  }

  private static ContextModel.Binding unknownFrame(Occurrence occurrence) {
    return new ContextModel.Binding(
        occurrence.location(),
        occurrence.symbol() == null ? "<unknown>" : occurrence.symbol(),
        "",
        "",
        ContextModel.Origin.UNKNOWN);
  }

  private static void add(List<Use> uses, Use use) {
    if (uses.size() == MAX_USES) {
      throw new IllegalArgumentException("Assessment attribution exceeds row budget");
    }
    uses.add(use);
  }

  private static long sum(long left, long right) {
    return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }
}
