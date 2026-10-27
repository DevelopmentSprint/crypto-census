package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.Risk;
import com.developmentsprint.cryptocensus.core.TechnicalFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Versioned ordered advisory rules over canonical technical facts and reconciled declarations. */
public final class PriorityRules {
  private PriorityRules() {}

  /** Explicit display rank; missing context never erases stronger independently known findings. */
  public enum Priority {
    TECHNICAL_REMEDIATION_FIRST(0),
    URGENT_PLANNING(1),
    PLANNED_MIGRATION(2),
    NEEDS_CONTEXT(3),
    NO_PQC_ACTION_IDENTIFIED_IN_OBSERVED_SCOPE(4);
    private final int rank;

    Priority(int rank) {
      this.rank = rank;
    }

    /** Stable sorting rank rather than relying on enum declaration order. */
    public int rank() {
      return rank;
    }
  }

  /** Business result and independent current-crypto remediation, with explicit incompleteness. */
  public record Decision(
      String ruleId,
      Priority businessPriority,
      boolean technicalRemediation,
      boolean incomplete,
      List<String> reasons,
      List<String> missingFields,
      String nextAction) {
    /** Snapshots explanatory output; no decision implies overall application safety. */
    public Decision {
      Objects.requireNonNull(ruleId, "ruleId");
      Objects.requireNonNull(businessPriority, "businessPriority");
      reasons = List.copyOf(reasons);
      missingFields = List.copyOf(missingFields);
      Objects.requireNonNull(nextAction, "nextAction");
    }

    /** Technical remediation always outranks a lower business result, including unknown context. */
    public Priority displayPriority() {
      return technicalRemediation ? Priority.TECHNICAL_REMEDIATION_FIRST : businessPriority;
    }
  }

  /** Evaluates one primitive for one applicable flow; callers preserve outer-flow results too. */
  public static Decision evaluate(
      TechnicalFacts facts,
      Map<ContextModel.Field, Reconciliation.Result> context,
      boolean observed,
      boolean associationEstablished,
      PolicyProfile profile) {
    Objects.requireNonNull(facts, "facts");
    Objects.requireNonNull(profile, "profile");
    final Map<ContextModel.Field, Reconciliation.Result> fields = Map.copyOf(context);
    java.util.Set<ContextModel.Subject> subjects = new java.util.HashSet<>();
    fields.forEach(
        (field, result) -> {
          if (field != result.field()) {
            throw new IllegalArgumentException("Context field key mismatch");
          }
          subjects.add(result.subject());
        });
    if (subjects.size() > 1) {
      throw new IllegalArgumentException("Context combines different flow subjects");
    }
    if (!facts.version().equals(profile.coreFactsVersion())) {
      throw new IllegalArgumentException("Core facts version differs from policy profile");
    }
    boolean technical =
        facts.assessment().risk() == Risk.BROKEN || facts.assessment().risk() == Risk.WEAK;
    List<String> reasons = new ArrayList<>();
    if (technical) {
      reasons.add("CURRENT_TECHNICAL_REMEDIATION");
    }
    if (facts.quantumExposure() == TechnicalFacts.QuantumExposure.VULNERABLE) {
      reasons.add("CLASSICAL_QUANTUM_EXPOSURE");
    }
    if (!observed || !associationEstablished || facts.assessment().risk() == Risk.UNKNOWN) {
      reasons.add(
          !observed
              ? "NO_OBSERVATION"
              : !associationEstablished
                  ? "ASSOCIATION_NOT_ESTABLISHED"
                  : "TECHNICAL_VERDICT_UNKNOWN");
      return decision(
          "evidence-required",
          Priority.NEEDS_CONTEXT,
          technical,
          true,
          reasons,
          List.of(),
          "Collect supported execution evidence and review its association and technical gaps.");
    }
    boolean reviewRequired = fields.values().stream().anyMatch(Reconciliation.Result::conflict);
    boolean conflict =
        fields.entrySet().stream()
            .anyMatch(entry -> applicableConflict(entry.getValue(), facts.role()));
    String classification = value(fields, ContextModel.Field.CLASSIFICATION);
    Integer sensitivity = profile.sensitivity().get(classification);
    if (!classification.isEmpty() && sensitivity == null) {
      reasons.add("CLASSIFICATION_UNMAPPED");
      reviewRequired = true;
      conflict |= applicable(ContextModel.Field.CLASSIFICATION, facts.role());
    }
    String declaredImpact = value(fields, ContextModel.Field.IMPACT);
    if (!declaredImpact.isEmpty() && !profile.impact().containsKey(declaredImpact)) {
      reasons.add("IMPACT_UNMAPPED");
      reviewRequired = true;
      conflict |= applicable(ContextModel.Field.IMPACT, facts.role());
    }
    if (contradictoryPurpose(facts.role(), value(fields, ContextModel.Field.PURPOSE))) {
      reasons.add("PURPOSE_CONTRADICTS_OBSERVED_ROLE");
      conflict = true;
    }
    if (conflict) {
      reasons.add("DECLARATION_REVIEW_REQUIRED");
      String unresolvedUrgency = urgency(facts, fields, profile);
      if (!unresolvedUrgency.isEmpty()) {
        reasons.add(unresolvedUrgency);
        reasons.add("POSITIVE_FINDING_UNRESOLVED");
      }
      return decision(
          "declaration-review",
          Priority.NEEDS_CONTEXT,
          technical,
          true,
          reasons,
          List.of(),
          "Review conflicting, stale or unmapped declarations while retaining technical findings.");
    }
    if (reviewRequired) {
      reasons.add("NON_APPLICABLE_DECLARATION_REVIEW_REQUIRED");
    }
    List<String> missing = missing(fields, facts.role(), profile);
    String urgency = urgency(facts, fields, profile);
    if (!urgency.isEmpty()) {
      reasons.add(urgency);
    }
    if (technical) {
      reasons.add("TECHNICAL_PRIORITY_INDEPENDENT_OF_CONTEXT");
      return decision(
          "technical-remediation",
          Priority.TECHNICAL_REMEDIATION_FIRST,
          true,
          reviewRequired || !missing.isEmpty(),
          reasons,
          missing,
          "Remediate the current technical finding and assess its quantum-exposed dependencies.");
    }
    if (facts.quantumExposure() == TechnicalFacts.QuantumExposure.VULNERABLE) {
      if (!urgency.isEmpty()) {
        boolean confidentiality = facts.role() == TechnicalFacts.ObservedRole.CONFIDENTIALITY;
        return decision(
            confidentiality ? "long-confidentiality" : "authenticity-urgency",
            Priority.URGENT_PLANNING,
            false,
            reviewRequired || !missing.isEmpty(),
            reasons,
            missing,
            confidentiality
                ? "Prioritize migration planning for this confidential flow and verify protocol and"
                    + " key-protection dependencies."
                : "Prioritize signature migration planning, verification lifetimes and replacement"
                    + " dependencies.");
      }
      if (missing.isEmpty()) {
        reasons.add("CLASSICAL_PUBLIC_KEY_DEPENDENCY");
        return decision(
            "planned-classical-migration",
            Priority.PLANNED_MIGRATION,
            false,
            reviewRequired,
            reasons,
            missing,
            "Plan replacement of this classical dependency; shorter lifetimes do not establish"
                + " safety.");
      }
      reasons.add("APPLICABLE_CONTEXT_MISSING_OR_BROAD");
      return decision(
          "context-required",
          Priority.NEEDS_CONTEXT,
          false,
          true,
          reasons,
          missing,
          "Provide the missing flow-specific requirements and review application-wide"
              + " assumptions.");
    }
    if (facts.quantumExposure() == TechnicalFacts.QuantumExposure.RESISTANT
        && facts.role() != TechnicalFacts.ObservedRole.NOT_ESTABLISHED
        && (facts.assessment().risk() == Risk.QUANTUM_SAFE
            || facts.assessment().risk() == Risk.ACCEPTABLE)) {
      reasons.add("RESISTANT_PRIMITIVE_IN_OBSERVED_SCOPE");
      return decision(
          "observed-resistant-primitive",
          Priority.NO_PQC_ACTION_IDENTIFIED_IN_OBSERVED_SCOPE,
          false,
          reviewRequired,
          reasons,
          List.of(),
          "Review unobserved paths and key-establishment, key-protection and protocol dependencies"
              + " separately.");
    }
    reasons.add("QUANTUM_EXPOSURE_OR_ROLE_NOT_ESTABLISHED");
    return decision(
        "exposure-not-established",
        Priority.NEEDS_CONTEXT,
        false,
        true,
        reasons,
        missing,
        "Collect the missing technical or relationship evidence; algorithm presence alone does not"
            + " establish readiness.");
  }

  private static boolean applicableConflict(
      Reconciliation.Result result, TechnicalFacts.ObservedRole role) {
    if (!result.conflict() || !applicable(result.field(), role)) {
      return false;
    }
    if (result.field() != ContextModel.Field.PURPOSE
        || role == TechnicalFacts.ObservedRole.NOT_ESTABLISHED
        || result.sources().isEmpty()) {
      return true;
    }
    boolean hasSpecific =
        result.sources().stream()
            .anyMatch(
                source ->
                    source.subject().equals(result.subject())
                        && result.field().substantive(source.value()));
    return result.sources().stream()
        .filter(source -> !hasSpecific || source.subject().equals(result.subject()))
        .anyMatch(source -> contradictoryPurpose(role, source.value()));
  }

  private static boolean applicable(ContextModel.Field field, TechnicalFacts.ObservedRole role) {
    return switch (field) {
      case PURPOSE -> true;
      case CLASSIFICATION, CONFIDENTIALITY_YEARS ->
          role == TechnicalFacts.ObservedRole.CONFIDENTIALITY;
      case AUTHENTICITY_YEARS, IMPACT -> role == TechnicalFacts.ObservedRole.AUTHENTICITY;
      case OWNER, EXPOSURE -> false;
    };
  }

  private static String urgency(
      TechnicalFacts facts,
      Map<ContextModel.Field, Reconciliation.Result> fields,
      PolicyProfile profile) {
    if (facts.quantumExposure() != TechnicalFacts.QuantumExposure.VULNERABLE) {
      return "";
    }
    Integer sensitivity =
        profile.sensitivity().get(value(fields, ContextModel.Field.CLASSIFICATION));
    if (facts.role() == TechnicalFacts.ObservedRole.CONFIDENTIALITY
        && specific(fields, ContextModel.Field.CLASSIFICATION)
        && sensitivity != null
        && sensitivity >= profile.sensitiveLevel()
        && specific(fields, ContextModel.Field.CONFIDENTIALITY_YEARS)
        && years(fields, ContextModel.Field.CONFIDENTIALITY_YEARS)
            >= profile.confidentialityYears()) {
      return "LONG_LIVED_SENSITIVE_CONFIDENTIALITY";
    }
    Integer impact = profile.impact().get(value(fields, ContextModel.Field.IMPACT));
    if (facts.role() == TechnicalFacts.ObservedRole.AUTHENTICITY
        && ((specific(fields, ContextModel.Field.IMPACT)
                && impact != null
                && impact >= profile.highImpactLevel())
            || (specific(fields, ContextModel.Field.AUTHENTICITY_YEARS)
                && years(fields, ContextModel.Field.AUTHENTICITY_YEARS)
                    >= profile.authenticityYears()))) {
      return "LONG_LIVED_OR_HIGH_IMPACT_AUTHENTICITY";
    }
    return "";
  }

  private static Decision decision(
      String rule,
      Priority priority,
      boolean technical,
      boolean incomplete,
      List<String> reasons,
      List<String> missing,
      String action) {
    return new Decision(rule, priority, technical, incomplete, reasons, missing, action);
  }

  private static List<String> missing(
      Map<ContextModel.Field, Reconciliation.Result> fields,
      TechnicalFacts.ObservedRole role,
      PolicyProfile profile) {
    List<String> result = new ArrayList<>();
    if (role == TechnicalFacts.ObservedRole.CONFIDENTIALITY) {
      if (!specific(fields, ContextModel.Field.CLASSIFICATION)
          || !profile.sensitivity().containsKey(value(fields, ContextModel.Field.CLASSIFICATION))) {
        result.add("classification");
      }
      if (!specific(fields, ContextModel.Field.CONFIDENTIALITY_YEARS)) {
        result.add("confidentialityYears");
      }
    } else if (role == TechnicalFacts.ObservedRole.AUTHENTICITY) {
      if (!specific(fields, ContextModel.Field.AUTHENTICITY_YEARS)) {
        result.add("authenticityYears");
      }
      if (!specific(fields, ContextModel.Field.IMPACT)
          || !profile.impact().containsKey(value(fields, ContextModel.Field.IMPACT))) {
        result.add("impact");
      }
    } else {
      result.add("observedProtectionRole");
    }
    return List.copyOf(result);
  }

  private static String value(
      Map<ContextModel.Field, Reconciliation.Result> fields, ContextModel.Field field) {
    Reconciliation.Result result = fields.get(field);
    return result == null || result.value().isEmpty() || !field.substantive(result.value())
        ? ""
        : result.value();
  }

  private static boolean specific(
      Map<ContextModel.Field, Reconciliation.Result> fields, ContextModel.Field field) {
    Reconciliation.Result result = fields.get(field);
    return result != null && result.specific() && !result.value().isEmpty();
  }

  private static int years(
      Map<ContextModel.Field, Reconciliation.Result> fields, ContextModel.Field field) {
    String value = value(fields, field);
    return value.isEmpty() ? 0 : Integer.parseInt(value);
  }

  private static boolean contradictoryPurpose(TechnicalFacts.ObservedRole role, String purpose) {
    return switch (purpose) {
      case "TRANSPORT", "STORAGE", "KEY_PROTECTION" ->
          role == TechnicalFacts.ObservedRole.AUTHENTICITY;
      case "SIGNING", "VERIFICATION" -> role == TechnicalFacts.ObservedRole.CONFIDENTIALITY;
      default -> false;
    };
  }
}
