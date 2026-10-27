package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.assessment.AssessmentInputs;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.Inventory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportInputsTest {
  @TempDir Path temporary;

  @Test
  void preservesTechnicalEvidenceAndReportsUnusableSidecarWithoutDoubleCounting() throws Exception {
    String cbom = Cbom.toJson(new Inventory(), new Cbom.Metadata("test", Map.of()));
    Files.writeString(temporary.resolve("cbom-first.json"), cbom);
    Files.writeString(temporary.resolve("cbom-second.json"), cbom);
    Files.writeString(temporary.resolve("cbom.json"), "not a run input");
    var inputs = ReportInputs.read(List.of(temporary, temporary), "unit", "local");
    assertThat(inputs).hasSize(2);
    assertThat(AssessmentInputs.load(inputs)).hasSize(1);
    Files.writeString(temporary.resolve("cbom-first.evidence.json"), "{}");
    var changed = ReportInputs.read(List.of(temporary), "unit", "local");
    assertThat(changed).hasSize(2);
    // Identical run identity with two distinct sidecars is rejected by the canonical loader.
    assertThatThrownBy(() -> AssessmentInputs.load(changed))
        .hasMessageContaining("Conflicting documents");
    Files.delete(temporary.resolve("cbom-second.json"));
    assertThat(AssessmentInputs.load(ReportInputs.read(List.of(temporary), "unit", "local")))
        .singleElement()
        .satisfies(run -> assertThat(run.gaps()).contains("INVALID_OR_MISMATCHED_SIDECAR"));
  }

  @Test
  void unreadableSidecarPreservesCbomWithoutInventingItsHash() throws Exception {
    Files.writeString(
        temporary.resolve("cbom-one.json"),
        Cbom.toJson(new Inventory(), new Cbom.Metadata("test", Map.of())));
    Path sidecar = temporary.resolve("cbom-one.evidence.json");
    Files.createDirectory(sidecar);
    assertThat(AssessmentInputs.load(ReportInputs.read(List.of(temporary), "unit", "local")))
        .singleElement()
        .satisfies(
            run -> {
              assertThat(run.gaps()).contains("SIDECAR_UNREADABLE");
              assertThat(run.evidenceHash()).isEmpty();
              assertThat(run.metadata()).isNotNull();
            });
  }

  @Test
  void requiresExplicitNonemptyBoundedRegularInputs() throws Exception {
    assertThatThrownBy(() -> ReportInputs.read(List.of(), "unit", "local"))
        .hasMessageContaining("Select between");
    assertThatThrownBy(() -> ReportInputs.read(List.of(temporary), "unit", "local"))
        .hasMessageContaining("No cbom");
    Path file = temporary.resolve("cbom-one.json");
    Files.createDirectory(file);
    assertThatThrownBy(() -> ReportInputs.read(List.of(temporary), "unit", "local"))
        .hasMessageContaining("regular files");
    Files.delete(file);
    for (int index = 0; index <= AssessmentInputs.MAX_RUNS; index++) {
      Files.writeString(temporary.resolve("cbom-" + index + ".json"), "{}");
    }
    assertThatThrownBy(() -> ReportInputs.read(List.of(temporary), "unit", "local"))
        .hasMessageContaining("Too many");
  }
}
