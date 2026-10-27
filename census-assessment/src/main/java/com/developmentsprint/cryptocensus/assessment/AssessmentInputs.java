package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Inventory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Bounded paired-input loading, provenance preservation and run-local identity namespacing. */
public final class AssessmentInputs {
  public static final int MAX_RUNS = 64;
  public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

  private AssessmentInputs() {}

  /**
   * Owns exact input bytes; labels describe the workload and never establish application identity.
   */
  public static final class Input {
    private final byte[] cbom;
    private final byte[] evidence;
    private final String workload;
    private final String environment;
    private final boolean evidenceUnreadable;

    /** Validates sizes before copying; a null evidence buffer explicitly means no sidecar. */
    public Input(byte[] cbom, byte[] evidence, String workload, String environment) {
      this(cbom, evidence, workload, environment, false);
    }

    private Input(
        byte[] cbom, byte[] evidence, String workload, String environment, boolean unreadable) {
      evidenceUnreadable = unreadable;
      Objects.requireNonNull(cbom, "cbom");
      if (cbom.length > Cbom.MAX_INPUT_BYTES
          || (evidence != null && evidence.length > CanonicalJson.MAX_BYTES)) {
        throw new IllegalArgumentException("Assessment input exceeds file budget");
      }
      this.cbom = cbom.clone();
      this.evidence = evidence == null ? null : evidence.clone();
      this.workload = ContextModel.text(workload, "workload label", 256, false);
      this.environment = ContextModel.text(environment, "environment label", 256, false);
    }
  }

  /**
   * Retains valid inventory when a present optional sidecar cannot be read; no digest is invented.
   */
  public static Input unreadableEvidence(byte[] cbom, String workload, String environment) {
    return new Input(cbom, null, workload, environment, true);
  }

  /** Immutable inventory snapshot; invalid sidecars never supply relationship evidence. */
  public record Run(
      String id,
      String nonce,
      String cbomHash,
      String evidenceHash,
      List<Inventory.Entry> entries,
      Map<String, String> metadata,
      Map<String, Long> coreHealth,
      EvidenceDocument evidence,
      List<String> gaps,
      List<String> workloads,
      List<String> environments) {
    /** Snapshots all collections; use load() to establish exact-byte pairing. */
    public Run {
      id = ContextModel.text(id, "run identity", 128, false);
      nonce = ContextModel.text(nonce, "run nonce", 64, true);
      cbomHash = ContextModel.hash(cbomHash, "CBOM hash");
      if (!evidenceHash.isEmpty()) {
        evidenceHash = ContextModel.hash(evidenceHash, "evidence hash");
      }
      entries = List.copyOf(entries);
      metadata = Map.copyOf(metadata);
      coreHealth = Map.copyOf(coreHealth);
      gaps = List.copyOf(gaps);
      workloads = List.copyOf(workloads);
      environments = List.copyOf(environments);
    }

    /** Collection completeness is independent of known technical or business priority. */
    public boolean incomplete() {
      return !gaps.isEmpty()
          || coreHealth.values().stream().anyMatch(value -> value > 0)
          || (evidence != null && evidence.health().values().stream().anyMatch(value -> value > 0));
    }
  }

  /**
   * Loads canonical run snapshots; conflicting reused nonces or sidecar associations are rejected.
   */
  public static List<Run> load(List<Input> inputs) {
    List<Input> snapshot = ContextModel.bounded(inputs, MAX_RUNS, "assessment inputs");
    long bytes = 0;
    for (Input input : snapshot) {
      bytes += input.cbom.length + (input.evidence == null ? 0L : input.evidence.length);
      if (bytes > MAX_TOTAL_BYTES) {
        throw new IllegalArgumentException("Assessment inputs exceed total byte budget");
      }
    }
    Map<String, Run> runs = new TreeMap<>();
    for (Input input : snapshot) {
      Run run = load(input);
      Run previous = runs.get(run.id());
      if (previous == null) {
        runs.put(run.id(), run);
      } else {
        if (!previous.cbomHash().equals(run.cbomHash())
            || (!previous.evidenceHash().isEmpty()
                && !run.evidenceHash().isEmpty()
                && !previous.evidenceHash().equals(run.evidenceHash()))) {
          throw new IllegalArgumentException("Conflicting documents reuse a run identity");
        }
        Run preferred = previous.evidenceHash().isEmpty() ? run : previous;
        List<String> mergedGaps = union(previous.gaps(), run.gaps());
        if (preferred.evidence() != null) {
          // A validated duplicate supplies the missing relationship evidence. Preserve other
          // discovery failures (for example unreadable copies), but not a contradictory absence.
          mergedGaps =
              mergedGaps.stream()
                  .filter(
                      gap ->
                          !"EXPECTED_SIDECAR_MISSING".equals(gap)
                              && !"NO_FLOW_OR_CORRELATION_EVIDENCE".equals(gap))
                  .toList();
        }
        runs.put(
            run.id(),
            new Run(
                run.id(),
                run.nonce(),
                run.cbomHash(),
                preferred.evidenceHash(),
                preferred.entries(),
                preferred.metadata(),
                preferred.coreHealth(),
                preferred.evidence(),
                mergedGaps,
                union(previous.workloads(), run.workloads()),
                union(previous.environments(), run.environments())));
      }
    }
    return List.copyOf(runs.values());
  }

  private static Run load(Input input) {
    Cbom.Document cbom = Cbom.parse(input.cbom);
    final String cbomHash = CanonicalJson.hash(input.cbom);
    final String evidenceHash = input.evidence == null ? "" : CanonicalJson.hash(input.evidence);
    List<String> gaps = new ArrayList<>();
    String nonce = nonce(cbom.properties().get(EvidenceDocument.RUN_PROPERTY));
    if (nonce.isEmpty()) {
      gaps.add("RUN_NONCE_NOT_ESTABLISHED");
    }
    Map<String, Long> health = new TreeMap<>();
    if (cbom.hasAgentHealth()) {
      for (String property : Cbom.HEALTH_PROPERTIES) {
        health.put(property, cbom.health(property));
      }
    } else {
      gaps.add("CORE_COLLECTION_HEALTH_NOT_ESTABLISHED");
    }
    EvidenceDocument evidence = null;
    if (input.evidence == null) {
      gaps.add(
          input.evidenceUnreadable
              ? "SIDECAR_UNREADABLE"
              : nonce.isEmpty() ? "NO_FLOW_OR_CORRELATION_EVIDENCE" : "EXPECTED_SIDECAR_MISSING");
    } else {
      try {
        EvidenceDocument candidate = EvidenceCodec.parse(input.evidence);
        EvidenceCodec.validatePair(candidate, input.cbom);
        evidence = candidate;
      } catch (IllegalArgumentException | java.time.DateTimeException e) {
        gaps.add("INVALID_OR_MISMATCHED_SIDECAR");
      }
    }
    return new Run(
        nonce.isEmpty() ? "cbom:" + cbomHash : "run:" + nonce,
        nonce,
        cbomHash,
        evidenceHash,
        cbom.inventory().entries(),
        cbom.properties(),
        health,
        evidence,
        gaps,
        List.of(input.workload),
        List.of(input.environment));
  }

  private static String nonce(String value) {
    if (value == null) {
      return "";
    }
    try {
      return UUID.fromString(value).toString().equals(value) ? value : "";
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private static List<String> union(List<String> first, List<String> second) {
    java.util.Set<String> values = new TreeSet<>(first);
    values.addAll(second);
    return List.copyOf(values);
  }
}
