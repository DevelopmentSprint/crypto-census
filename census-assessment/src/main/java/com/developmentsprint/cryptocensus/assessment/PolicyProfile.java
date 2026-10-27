package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.Policy;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import java.util.TreeMap;

/** Organization-configurable triage defaults, not cryptographic standards or quantum forecasts. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "mapping() snapshots both maps with Map.copyOf; keys and values are immutable")
public record PolicyProfile(
    int schemaVersion,
    String ruleSetVersion,
    String coreFactsVersion,
    Map<String, Integer> sensitivity,
    Map<String, Integer> impact,
    int confidentialityYears,
    int authenticityYears,
    int sensitiveLevel,
    int highImpactLevel) {
  public static final int VERSION = 1;
  public static final String RULES = "business-v1";

  /** Owns bounded mappings and validates every threshold and policy dependency. */
  public PolicyProfile {
    if (schemaVersion != VERSION
        || !RULES.equals(ruleSetVersion)
        || !Policy.FACTS_VERSION.equals(coreFactsVersion)) {
      throw new IllegalArgumentException("Unsupported assessment policy version");
    }
    sensitivity = mapping(sensitivity, ContextModel.Field.CLASSIFICATION);
    impact = mapping(impact, ContextModel.Field.IMPACT);
    if (confidentialityYears < 1
        || confidentialityYears > 1000
        || authenticityYears < 1
        || authenticityYears > 1000
        || sensitiveLevel < 1
        || sensitiveLevel > 10
        || highImpactLevel < 1
        || highImpactLevel > 10) {
      throw new IllegalArgumentException("Invalid planning threshold");
    }
  }

  /** Product triage defaults; all values participate in the reproducibility hash. */
  public static PolicyProfile defaults() {
    return new PolicyProfile(
        VERSION,
        RULES,
        Policy.FACTS_VERSION,
        Map.of("public", 0, "internal", 1, "sensitive", 2, "restricted", 3),
        Map.of("low", 1, "medium", 2, "high", 3),
        5,
        5,
        2,
        3);
  }

  /** Deterministic full policy profile, including mappings, thresholds and core-facts version. */
  public byte[] encode() {
    return CanonicalJson.encode(
        Map.of(
            "schemaVersion",
            schemaVersion,
            "ruleSetVersion",
            ruleSetVersion,
            "coreFactsVersion",
            coreFactsVersion,
            "sensitivity",
            sensitivity,
            "impact",
            impact,
            "confidentialityYears",
            confidentialityYears,
            "authenticityYears",
            authenticityYears,
            "sensitiveLevel",
            sensitiveLevel,
            "highImpactLevel",
            highImpactLevel));
  }

  /** Content revision changes whenever any decision input changes. */
  public String revision() {
    return CanonicalJson.hash(encode());
  }

  /** Strict bounded import; custom classifications require an explicit sensitivity entry. */
  public static PolicyProfile parse(byte[] bytes) {
    Map<String, Object> value = CanonicalJson.object(CanonicalJson.decode(bytes));
    CanonicalJson.requireKeys(
        value,
        "schemaVersion",
        "ruleSetVersion",
        "coreFactsVersion",
        "sensitivity",
        "impact",
        "confidentialityYears",
        "authenticityYears",
        "sensitiveLevel",
        "highImpactLevel");
    return new PolicyProfile(
        integer(value, "schemaVersion"),
        CanonicalJson.text(value, "ruleSetVersion"),
        CanonicalJson.text(value, "coreFactsVersion"),
        numbers(value.get("sensitivity")),
        numbers(value.get("impact")),
        integer(value, "confidentialityYears"),
        integer(value, "authenticityYears"),
        integer(value, "sensitiveLevel"),
        integer(value, "highImpactLevel"));
  }

  private static Map<String, Integer> mapping(
      Map<String, Integer> values, ContextModel.Field field) {
    if (values.size() > 256) {
      throw new IllegalArgumentException("Too many policy mappings");
    }
    Map<String, Integer> copy = Map.copyOf(values);
    if (copy.size() > 256) {
      throw new IllegalArgumentException("Too many policy mappings");
    }
    copy.forEach(
        (key, value) -> {
          field.validate(key);
          if (!field.substantive(key) || value < 0 || value > 10) {
            throw new IllegalArgumentException("Invalid policy mapping");
          }
        });
    return copy;
  }

  private static Map<String, Integer> numbers(Object value) {
    Map<String, Object> source = CanonicalJson.object(value);
    Map<String, Integer> result = new TreeMap<>();
    source.forEach((key, item) -> result.put(key, integer(source, key)));
    return result;
  }

  private static int integer(Map<String, Object> value, String key) {
    long number = CanonicalJson.integer(value, key);
    if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Policy integer out of range");
    }
    return (int) number;
  }
}
