package com.developmentsprint.cryptocensus.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Operation;
import com.developmentsprint.cryptocensus.core.Policy;
import com.developmentsprint.cryptocensus.core.Primitive;
import com.developmentsprint.cryptocensus.core.TechnicalFacts;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PriorityRulesTest {
  private static final ContextModel.Subject SUBJECT = new ContextModel.Subject("app", "archive");
  private static final PolicyProfile PROFILE = PolicyProfile.defaults();
  private static final Policy POLICY = new Policy();

  private static Map<ContextModel.Field, Reconciliation.Result> fields(
      Map<ContextModel.Field, String> values) {
    Map<ContextModel.Field, Reconciliation.Result> result = new EnumMap<>(ContextModel.Field.class);
    values.forEach(
        (field, value) ->
            result.put(
                field,
                new Reconciliation.Result(
                    SUBJECT, field, Reconciliation.Status.AGREED, value, List.of())));
    return result;
  }

  private static Map<ContextModel.Field, Reconciliation.Result> confidential() {
    return fields(
        Map.of(
            ContextModel.Field.CLASSIFICATION,
            "sensitive",
            ContextModel.Field.CONFIDENTIALITY_YEARS,
            "20"));
  }

  private static TechnicalFacts rsa(int bits) {
    return POLICY.facts(
        CryptoAsset.cipher(
            "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", bits, "SunJCE", Operation.ENCRYPT));
  }

  @Test
  void unmappedImpactRequiresReviewEvenWhenLifetimeWouldEstablishUrgency() {
    PolicyProfile profile =
        new PolicyProfile(
            1,
            PolicyProfile.RULES,
            Policy.FACTS_VERSION,
            PROFILE.sensitivity(),
            Map.of("low", 1),
            5,
            5,
            2,
            3);
    TechnicalFacts signature =
        POLICY.facts(
            CryptoAsset.of(Primitive.SIGNATURE, "SHA256withRSA", 2048, "provider", Operation.SIGN));
    PriorityRules.Decision result =
        PriorityRules.evaluate(
            signature,
            fields(
                Map.of(
                    ContextModel.Field.IMPACT,
                    "high",
                    ContextModel.Field.AUTHENTICITY_YEARS,
                    "20")),
            true,
            true,
            profile);
    assertThat(result.businessPriority()).isEqualTo(PriorityRules.Priority.NEEDS_CONTEXT);
    assertThat(result.reasons()).contains("IMPACT_UNMAPPED");
  }

  @Test
  void mismatchedKeysAndMixedSubjectsCannotAssembleInventedContext() {
    Map<ContextModel.Field, Reconciliation.Result> mixed = new EnumMap<>(confidential());
    mixed.put(
        ContextModel.Field.CONFIDENTIALITY_YEARS,
        new Reconciliation.Result(
            new ContextModel.Subject("app", "other"),
            ContextModel.Field.CONFIDENTIALITY_YEARS,
            Reconciliation.Status.AGREED,
            "20",
            List.of()));
    assertThatThrownBy(() -> PriorityRules.evaluate(rsa(2048), mixed, true, true, PROFILE))
        .hasMessageContaining("different flow subjects");
    Map<ContextModel.Field, Reconciliation.Result> wrongKey =
        Map.of(ContextModel.Field.OWNER, confidential().get(ContextModel.Field.CLASSIFICATION));
    assertThatThrownBy(() -> PriorityRules.evaluate(rsa(2048), wrongKey, true, true, PROFILE))
        .hasMessageContaining("field key mismatch");
  }

  @Test
  void brokenRsaKeepsTechnicalPriorityAndIndependentLongConfidentialityReason() {
    PriorityRules.Decision broken =
        PriorityRules.evaluate(rsa(1024), confidential(), true, true, PROFILE);
    PriorityRules.Decision classical =
        PriorityRules.evaluate(rsa(2048), confidential(), true, true, PROFILE);
    assertThat(broken.displayPriority())
        .isEqualTo(PriorityRules.Priority.TECHNICAL_REMEDIATION_FIRST);
    assertThat(broken.reasons())
        .contains("CLASSICAL_QUANTUM_EXPOSURE", "LONG_LIVED_SENSITIVE_CONFIDENTIALITY");
    assertThat(broken.missingFields()).isEmpty();
    assertThat(classical.displayPriority()).isEqualTo(PriorityRules.Priority.URGENT_PLANNING);
    assertThat(broken.displayPriority().rank()).isLessThan(classical.displayPriority().rank());
    assertThat(
            PriorityRules.evaluate(rsa(1024), Map.of(), true, true, PROFILE).technicalRemediation())
        .isTrue();
  }

  @Test
  void missingAssociationAndConflictingDeclarationsCannotDemoteTechnicalRemediation() {
    PriorityRules.Decision missing =
        PriorityRules.evaluate(rsa(1024), confidential(), true, false, PROFILE);
    assertThat(missing.businessPriority()).isEqualTo(PriorityRules.Priority.NEEDS_CONTEXT);
    assertThat(missing.displayPriority())
        .isEqualTo(PriorityRules.Priority.TECHNICAL_REMEDIATION_FIRST);
    Map<ContextModel.Field, Reconciliation.Result> conflict = new EnumMap<>(confidential());
    conflict.put(
        ContextModel.Field.CLASSIFICATION,
        new Reconciliation.Result(
            SUBJECT,
            ContextModel.Field.CLASSIFICATION,
            Reconciliation.Status.CONFLICT,
            "",
            List.of()));
    PriorityRules.Decision decision =
        PriorityRules.evaluate(rsa(1024), conflict, true, true, PROFILE);
    assertThat(decision.businessPriority()).isEqualTo(PriorityRules.Priority.NEEDS_CONTEXT);
    assertThat(decision.displayPriority())
        .isEqualTo(PriorityRules.Priority.TECHNICAL_REMEDIATION_FIRST);
    assertThat(decision.incomplete()).isTrue();
  }

  @Test
  void signaturesCanBeUrgentFromEitherLifetimeOrHighImpact() {
    TechnicalFacts signature =
        POLICY.facts(
            CryptoAsset.of(
                Primitive.SIGNATURE, "SHA256withRSA", 2048, "SunRsaSign", Operation.SIGN));
    PriorityRules.Decision highImpact =
        PriorityRules.evaluate(
            signature, fields(Map.of(ContextModel.Field.IMPACT, "high")), true, true, PROFILE);
    assertThat(highImpact.businessPriority()).isEqualTo(PriorityRules.Priority.URGENT_PLANNING);
    assertThat(highImpact.missingFields()).containsExactly("authenticityYears");
    assertThat(highImpact.incomplete()).isTrue();
    assertThat(
            PriorityRules.evaluate(
                    signature,
                    fields(Map.of(ContextModel.Field.AUTHENTICITY_YEARS, "20")),
                    true,
                    true,
                    PROFILE)
                .businessPriority())
        .isEqualTo(PriorityRules.Priority.URGENT_PLANNING);
    assertThat(
            PriorityRules.evaluate(
                    signature,
                    fields(
                        Map.of(
                            ContextModel.Field.AUTHENTICITY_YEARS,
                            "1",
                            ContextModel.Field.IMPACT,
                            "low")),
                    true,
                    true,
                    PROFILE)
                .businessPriority())
        .isEqualTo(PriorityRules.Priority.PLANNED_MIGRATION);
    assertThat(PriorityRules.evaluate(signature, Map.of(), true, true, PROFILE).missingFields())
        .containsExactly("authenticityYears", "impact");
  }

  @Test
  void completeShorterContextMeansPlannedMigrationAndBroadAssumptionsRemainIncomplete() {
    Map<ContextModel.Field, Reconciliation.Result> below =
        fields(
            Map.of(
                ContextModel.Field.CLASSIFICATION,
                "public",
                ContextModel.Field.CONFIDENTIALITY_YEARS,
                "1"));
    assertThat(PriorityRules.evaluate(rsa(2048), below, true, true, PROFILE).businessPriority())
        .isEqualTo(PriorityRules.Priority.PLANNED_MIGRATION);
    Map<ContextModel.Field, Reconciliation.Result> broad = new EnumMap<>(confidential());
    broad.put(
        ContextModel.Field.CLASSIFICATION,
        new Reconciliation.Result(
            SUBJECT,
            ContextModel.Field.CLASSIFICATION,
            Reconciliation.Status.APPLICATION_DEFAULT,
            "sensitive",
            List.of()));
    PriorityRules.Decision result = PriorityRules.evaluate(rsa(2048), broad, true, true, PROFILE);
    assertThat(result.businessPriority()).isEqualTo(PriorityRules.Priority.NEEDS_CONTEXT);
    assertThat(result.missingFields()).containsExactly("classification");
  }

  @Test
  void unrecognizedClassificationAndContradictoryPurposeRequireReview() {
    Map<ContextModel.Field, Reconciliation.Result> custom =
        fields(
            Map.of(
                ContextModel.Field.CLASSIFICATION,
                "custom",
                ContextModel.Field.CONFIDENTIALITY_YEARS,
                "20"));
    assertThat(PriorityRules.evaluate(rsa(2048), custom, true, true, PROFILE).reasons())
        .contains("CLASSIFICATION_UNMAPPED");
    Map<ContextModel.Field, Reconciliation.Result> purpose = new EnumMap<>(confidential());
    purpose.putAll(fields(Map.of(ContextModel.Field.PURPOSE, "SIGNING")));
    assertThat(PriorityRules.evaluate(rsa(2048), purpose, true, true, PROFILE).reasons())
        .contains("PURPOSE_CONTRADICTS_OBSERVED_ROLE");
    purpose.putAll(fields(Map.of(ContextModel.Field.PURPOSE, "UNSPECIFIED")));
    assertThat(PriorityRules.evaluate(rsa(2048), purpose, true, true, PROFILE).businessPriority())
        .isEqualTo(PriorityRules.Priority.URGENT_PLANNING);
  }

  @Test
  void resistantPrimitiveDoesNotMakeUnknownTlsOrUnobservedUseSafe() {
    TechnicalFacts kem = POLICY.facts(CryptoAsset.of(Primitive.KEM, "ML-KEM-768", 0, "provider"));
    assertThat(PriorityRules.evaluate(kem, confidential(), true, true, PROFILE).businessPriority())
        .isEqualTo(PriorityRules.Priority.NO_PQC_ACTION_IDENTIFIED_IN_OBSERVED_SCOPE);
    assertThat(PriorityRules.evaluate(kem, confidential(), false, true, PROFILE).businessPriority())
        .isEqualTo(PriorityRules.Priority.NEEDS_CONTEXT);
    TechnicalFacts tls = POLICY.facts(CryptoAsset.tls("TLSv1.3", "TLS_AES_256_GCM_SHA384"));
    assertThat(PriorityRules.evaluate(tls, confidential(), true, true, PROFILE).businessPriority())
        .isEqualTo(PriorityRules.Priority.NEEDS_CONTEXT);
    TechnicalFacts generation =
        POLICY.facts(CryptoAsset.of(Primitive.KEY_PAIR_GENERATION, "RSA", 2048, "provider"));
    assertThat(
            PriorityRules.evaluate(generation, confidential(), true, true, PROFILE).missingFields())
        .contains("observedProtectionRole");
    TechnicalFacts unknown =
        POLICY.facts(CryptoAsset.of(Primitive.CIPHER, "Mystery", 0, "provider"));
    assertThat(PriorityRules.evaluate(unknown, confidential(), true, true, PROFILE).reasons())
        .contains("TECHNICAL_VERDICT_UNKNOWN");
  }

  @Test
  void policyRevisionIncludesMappingsThresholdsAndVersions() {
    assertThat(PolicyProfile.parse(PROFILE.encode())).isEqualTo(PROFILE);
    PolicyProfile changed =
        new PolicyProfile(
            1,
            PolicyProfile.RULES,
            Policy.FACTS_VERSION,
            PROFILE.sensitivity(),
            PROFILE.impact(),
            21,
            5,
            2,
            3);
    assertThat(changed.revision()).isNotEqualTo(PROFILE.revision());
    assertThat(
            PriorityRules.evaluate(rsa(2048), confidential(), true, true, changed)
                .businessPriority())
        .isEqualTo(PriorityRules.Priority.PLANNED_MIGRATION);
    Map<String, Integer> mutable = new java.util.HashMap<>(PROFILE.sensitivity());
    PolicyProfile owned =
        new PolicyProfile(
            1, PolicyProfile.RULES, Policy.FACTS_VERSION, mutable, PROFILE.impact(), 5, 5, 2, 3);
    mutable.put("sensitive", 0);
    assertThat(owned.sensitivity().get("sensitive")).isEqualTo(2);
    assertThatThrownBy(
            () ->
                new PolicyProfile(
                    2, PolicyProfile.RULES, Policy.FACTS_VERSION, Map.of(), Map.of(), 5, 5, 2, 3))
        .hasMessageContaining("version");
    assertThatThrownBy(
            () ->
                new PolicyProfile(
                    1, PolicyProfile.RULES, Policy.FACTS_VERSION, Map.of(), Map.of(), 0, 5, 2, 3))
        .hasMessageContaining("threshold");
    assertThatThrownBy(
            () ->
                new PolicyProfile(
                    1,
                    PolicyProfile.RULES,
                    Policy.FACTS_VERSION,
                    Map.of("sensitive", -1),
                    Map.of(),
                    5,
                    5,
                    2,
                    3))
        .hasMessageContaining("mapping");
    TechnicalFacts facts = rsa(2048);
    TechnicalFacts unsupported =
        new TechnicalFacts(facts.assessment(), facts.quantumExposure(), facts.role(), "future");
    assertThatThrownBy(
            () -> PriorityRules.evaluate(unsupported, confidential(), true, true, PROFILE))
        .hasMessageContaining("version");
  }
}
