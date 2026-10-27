package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Primitive;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckAssessmentFailureTest {
  @Test
  void optionalReportFailureCannotMaskCryptographicGate(@TempDir Path directory) throws Exception {
    Map<String, String> metadata = new HashMap<>();
    metadata.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    Cbom.HEALTH_PROPERTIES.forEach(key -> metadata.put(key, "0"));
    Inventory inventory = new Inventory();
    inventory.record(CryptoAsset.of(Primitive.MESSAGE_DIGEST, "MD5", 0, null), Occurrence.UNKNOWN);
    Files.writeString(
        directory.resolve("cbom-run.json"),
        Cbom.toJson(inventory, new Cbom.Metadata("test", metadata)));
    MavenProject project = new MavenProject();
    project.setArtifactId("test");
    project.setContextValue(PrepareAgentMojo.RUN_DIR_CONTEXT, directory.toString());
    CheckMojo mojo = new CheckMojo();
    ContextApplyMojoTest.set(mojo, "project", project);
    ContextApplyMojoTest.set(mojo, "output", directory.resolve("merged.json").toFile());
    ContextApplyMojoTest.set(mojo, "failOn", "broken");
    ContextApplyMojoTest.set(mojo, "assessment", true);
    ContextApplyMojoTest.set(mojo, "contextFile", directory.resolve("missing.json").toFile());
    ContextApplyMojoTest.set(
        mojo, "assessmentOutputDirectory", directory.resolve("report").toFile());
    ContextApplyMojoTest.set(mojo, "workload", "tests");
    ContextApplyMojoTest.set(mojo, "environment", "local");
    assertThatThrownBy(mojo::execute)
        .isInstanceOf(MojoFailureException.class)
        .hasMessageContaining("MD5")
        .hasMessageContaining("at or above 'broken'")
        .hasMessageContaining("Could not generate")
        .satisfies(
            failure ->
                org.assertj.core.api.Assertions.assertThat(failure.getSuppressed())
                    .singleElement()
                    .isInstanceOf(MojoExecutionException.class));
    ContextApplyMojoTest.set(mojo, "failOn", "none");
    assertThatThrownBy(mojo::execute)
        .isInstanceOf(MojoExecutionException.class)
        .hasMessageContaining("Could not generate");
  }
}
