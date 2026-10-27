package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.assessment.ContextCodec;
import com.developmentsprint.cryptocensus.assessment.ContextDocument;
import com.developmentsprint.cryptocensus.assessment.PolicyProfile;
import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.Inventory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssessMojoTest {
  @TempDir Path temporary;

  @Test
  void requiresExplicitEvidenceAndReassessesWithoutChangingInputs() throws Exception {
    var mojo = new AssessMojo();
    assertThatThrownBy(mojo::execute).hasMessageContaining("Select saved evidence");
    Path cbom = temporary.resolve("cbom-one.json");
    Files.writeString(cbom, Cbom.toJson(new Inventory(), new Cbom.Metadata("test", Map.of())));
    final var before = Files.getLastModifiedTime(cbom);
    Path output = temporary.resolve("report");
    ContextApplyMojoTest.set(mojo, "inputDir", temporary.toFile());
    ContextApplyMojoTest.set(mojo, "inputDirectories", List.of(temporary.toFile()));
    ContextApplyMojoTest.set(mojo, "assessmentOutputDirectory", output.toFile());
    ContextApplyMojoTest.set(mojo, "workload", "tests");
    ContextApplyMojoTest.set(mojo, "environment", "local");
    mojo.execute();
    byte[] first = Files.readAllBytes(output.resolve("assessment.json"));
    mojo.execute();
    assertThat(Files.readAllBytes(output.resolve("assessment.json"))).isEqualTo(first);
    assertThat(Files.getLastModifiedTime(cbom)).isEqualTo(before);
    var data = CanonicalJson.object(CanonicalJson.decode(first));
    assertThat(CanonicalJson.array(data, "runs")).hasSize(1);
    assertThat(data.get("incomplete")).isEqualTo(true);
    ContextApplyMojoTest.set(mojo, "contextFile", temporary.resolve("missing.json").toFile());
    assertThatThrownBy(mojo::execute).hasMessageContaining("Could not assess");
    assertThat(Files.readAllBytes(output.resolve("assessment.json"))).isEqualTo(first);
  }

  @Test
  void explicitlyNamedLegacyInputsNeedNoRunDirectoryAndCannotBeOverwritten() throws Exception {
    Path manual = temporary.resolve("manual-output");
    Files.writeString(manual, Cbom.toJson(new Inventory(), new Cbom.Metadata("legacy", Map.of())));
    var mojo = new AssessMojo();
    ContextApplyMojoTest.set(mojo, "inputFile", manual.toFile());
    ContextApplyMojoTest.set(mojo, "inputFiles", List.of(manual.toFile()));
    ContextApplyMojoTest.set(
        mojo, "assessmentOutputDirectory", temporary.resolve("report").toFile());
    ContextApplyMojoTest.set(mojo, "workload", "standalone");
    ContextApplyMojoTest.set(mojo, "environment", "local");
    mojo.execute();
    assertThat(temporary.resolve("report/assessment.json")).isRegularFile();
    Path colliding = temporary.resolve("assessment.json");
    Files.copy(manual, colliding);
    final byte[] before = Files.readAllBytes(colliding);
    assertThatThrownBy(
            () ->
                ReportWorkflow.write(
                    List.of(), List.of(colliding), null, null, temporary, "tests", "local"))
        .hasMessageContaining("must not replace");
    assertThat(Files.readAllBytes(colliding)).isEqualTo(before);
  }

  @Test
  void validatesExplicitPolicyAndProtectsContextFromOutputCollision() throws Exception {
    Files.writeString(
        temporary.resolve("cbom-one.json"),
        Cbom.toJson(new Inventory(), new Cbom.Metadata("test", Map.of())));
    Path context = temporary.resolve("assessment.json");
    byte[] contextBytes = ContextCodec.encode(ContextDocument.empty());
    Files.write(context, contextBytes);
    Path policy = temporary.resolve("policy.json");
    Files.write(policy, PolicyProfile.defaults().encode());
    assertThatThrownBy(
            () ->
                ReportWorkflow.write(
                    List.of(temporary),
                    context.toFile(),
                    policy.toFile(),
                    temporary,
                    "tests",
                    "local"))
        .hasMessageContaining("must not replace");
    assertThat(Files.readAllBytes(context)).isEqualTo(contextBytes);
    var report =
        ReportWorkflow.write(
            List.of(temporary),
            context.toFile(),
            policy.toFile(),
            temporary.resolve("output"),
            "tests",
            "local");
    assertThat(report.data().get("profileHash")).isEqualTo(PolicyProfile.defaults().revision());
  }
}
