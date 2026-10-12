package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Primitive;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrentRunTest {
  @TempDir Path directory;
  private static final String MODULE = "example:payments";
  private static final String VERSION = "1.0";
  private final String runId = UUID.randomUUID().toString();

  @Test
  void reportRetainsFailingPolicyAndProducerWithoutThrowingAndEnforceRechecks() throws Exception {
    Path run = prepare();
    raw(run, "cbom-1.json", true, 0);
    ReportMojo report = configured(new ReportMojo());
    report.execute();
    Path manifest = manifest(run, "1");
    var seal = RunEvidence.verify(run, runId, MODULE, VERSION, "1");
    assertThat(seal)
        .containsEntry("testOutcome", "failed")
        .containsEntry("policyOutcome", "failed");
    byte[] original = Files.readAllBytes(manifest);
    byte[] artifact = Files.readAllBytes(manifest.resolveSibling(RunEvidence.ARTIFACT));
    report.execute();
    assertThat(Files.readAllBytes(manifest)).isEqualTo(original);
    assertThat(Files.readAllBytes(manifest.resolveSibling(RunEvidence.ARTIFACT)))
        .isEqualTo(artifact);
    EnforceMojo enforce = configured(new EnforceMojo());
    set(enforce, "manifest", manifest.toFile());
    assertThatThrownBy(enforce::execute)
        .isInstanceOf(MojoFailureException.class)
        .hasMessageContaining("cryptographic asset");
    assertThat(Files.readAllBytes(manifest.resolveSibling(RunEvidence.ARTIFACT)))
        .isEqualTo(artifact);
  }

  @Test
  void missingOrWrongRunNeverUsesEarlierEvidenceEvenWithMissingDataOptOut() throws Exception {
    Path run = prepare();
    raw(run, "cbom-1.json", false, 0);
    configured(new ReportMojo()).execute();
    ReportMojo next = configured(new ReportMojo());
    set(next, "runId", UUID.randomUUID().toString());
    set(next, "failOnMissingData", false);
    assertThatThrownBy(next::execute).hasMessageContaining("missing or ambiguous");
    String emptyId = UUID.randomUUID().toString();
    RunEvidence.prepare(directory, emptyId, MODULE, VERSION);
    set(next, "runId", emptyId);
    assertThatThrownBy(next::execute).hasMessageContaining("No current-run JVM evidence");
  }

  @Test
  void newRevisionCapturesLateInputWithoutChangingEarlierSealedBytes() throws Exception {
    Path run = prepare();
    raw(run, "cbom-1.json", false, 0);
    ReportMojo report = configured(new ReportMojo());
    report.execute();
    Path first = manifest(run, "1");
    final byte[] firstBytes = Files.readAllBytes(first);
    raw(run, "cbom-2.json", true, 0);
    assertThatThrownBy(report::execute).hasMessageContaining("Inputs changed");
    set(report, "revision", "2");
    report.execute();
    assertThat(Files.readAllBytes(first)).isEqualTo(firstBytes);
    assertThat(RunEvidence.verify(run, runId, MODULE, VERSION, "1").get("policyOutcome"))
        .isEqualTo("passed");
    assertThat(RunEvidence.verify(run, runId, MODULE, VERSION, "2").get("policyOutcome"))
        .isEqualTo("failed");
    EnforceMojo enforce = configured(new EnforceMojo());
    set(enforce, "manifest", first.toFile());
    assertThatCode(enforce::execute).doesNotThrowAnyException();
    set(enforce, "manifest", manifest(run, "2").toFile());
    assertThatThrownBy(enforce::execute).isInstanceOf(MojoFailureException.class);
  }

  @Test
  void policyAndOutcomeCannotChangeAnExistingSeal() throws Exception {
    Path run = prepare();
    raw(run, "cbom-1.json", false, 0);
    ReportMojo report = configured(new ReportMojo());
    report.execute();
    set(report, "testOutcome", "passed");
    assertThatThrownBy(report::execute).hasMessageContaining("Producer outcome differs");
    set(report, "testOutcome", "failed");
    set(report, "failOn", "none");
    assertThatThrownBy(report::execute).hasMessageContaining("Effective policy differs");
  }

  @Test
  void tamperedArtifactsAndRawFilesRejectBeforeEnforcement() throws Exception {
    Path run = prepare();
    raw(run, "cbom-1.json", false, 0);
    configured(new ReportMojo()).execute();
    Path artifact = manifest(run, "1").resolveSibling(RunEvidence.ARTIFACT);
    byte[] original = Files.readAllBytes(artifact);
    Files.writeString(artifact, Files.readString(artifact) + " ");
    assertThatThrownBy(() -> RunEvidence.verify(run, runId, MODULE, VERSION, "1"))
        .hasMessageContaining("digest mismatch");
    Files.write(artifact, original);
    Files.writeString(run.resolve("cbom-1.json"), "{}");
    assertThatThrownBy(() -> RunEvidence.verify(run, runId, MODULE, VERSION, "1"))
        .hasMessageContaining("input digest");
  }

  @Test
  void corruptedMissingAmbiguousAndWrongModuleDescriptorsReject() throws Exception {
    Path run = prepare();
    assertThatThrownBy(() -> RunEvidence.prepare(directory, runId, MODULE, VERSION))
        .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    Path second = RunEvidence.prepare(directory, runId, "example:other", VERSION);
    assertThat(second).isNotEqualTo(run);
    assertThat(RunEvidence.select(directory, runId, MODULE, VERSION)).isEqualTo(run);
    assertThatThrownBy(() -> RunEvidence.select(directory, runId, MODULE, "2.0"))
        .hasMessageContaining("does not match");
    Files.createDirectory(directory.resolve(run.getFileName() + "-copy"));
    assertThatThrownBy(() -> RunEvidence.select(directory, runId, MODULE, VERSION))
        .hasMessageContaining("ambiguous");
    Files.delete(directory.resolve(run.getFileName() + "-copy"));
    Files.writeString(run.resolve(RunEvidence.DESCRIPTOR), "{}");
    assertThatThrownBy(() -> RunEvidence.select(directory, runId, MODULE, VERSION))
        .hasMessageContaining("does not match");
  }

  @Test
  void incompleteCollectionIsSealedButEnforcedIndependentlyOfPolicy() throws Exception {
    Path run = prepare();
    raw(run, "cbom-1.json", false, 1);
    configured(new ReportMojo()).execute();
    var seal = RunEvidence.verify(run, runId, MODULE, VERSION, "1");
    assertThat(seal).containsEntry("health", "incomplete").containsEntry("policyOutcome", "passed");
    EnforceMojo enforce = configured(new EnforceMojo());
    set(enforce, "manifest", manifest(run, "1").toFile());
    assertThatThrownBy(enforce::execute)
        .isInstanceOf(MojoFailureException.class)
        .hasMessageContaining("Incomplete collection");
  }

  @Test
  void malformedRawOrManifestAndSymlinksCannotBecomeEvidence() throws Exception {
    Path run = prepare();
    Files.writeString(run.resolve("cbom-bad.json"), "{}");
    assertThatThrownBy(configured(new ReportMojo())::execute).hasMessageContaining("Invalid CBOM");
    assertThatThrownBy(() -> RunEvidence.verify(run, runId, MODULE, VERSION, "1"))
        .isInstanceOf(java.io.IOException.class);
    Files.delete(run.resolve("cbom-bad.json"));
    Path source = directory.resolve("source.json");
    Files.writeString(source, "{}");
    try {
      Files.createSymbolicLink(run.resolve("cbom-link.json"), source);
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
      org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable");
    }
    assertThatThrownBy(() -> RunEvidence.inputs(run)).hasMessageContaining("symlink");
  }

  @Test
  void explicitManifestRunAndRevisionAreRequiredAndNoInMemoryRunMayBeReplaced() throws Exception {
    assertThatThrownBy(configured(new EnforceMojo())::execute)
        .hasMessageContaining("requires census.manifest");
    assertThatThrownBy(() -> RunEvidence.prefix("previous-run", MODULE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RunEvidence.prefix(null, MODULE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RunEvidence.sealedDirectory(directory, "../2"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RunEvidence.directoryForManifest(directory.resolve("other.json")))
        .isInstanceOf(IllegalArgumentException.class);
    Path run = prepare();
    raw(run, "cbom-1.json", false, 0);
    ReportMojo report = configured(new ReportMojo());
    MavenProject project = project();
    project.setContextValue(
        PrepareAgentMojo.RUN_DIR_CONTEXT, directory.resolve("elsewhere").toString());
    set(report, "project", project);
    assertThatThrownBy(report::execute).hasMessageContaining("differs from this Maven invocation");
  }

  @Test
  void unsupportedRawNamesAndOutputPathAliasesFailClosed() throws Exception {
    Path run = prepare();
    raw(run, "cbom-bad name.json", false, 0);
    assertThatThrownBy(configured(new ReportMojo())::execute)
        .hasMessageContaining("Could not list valid CBOMs");
    assertThatThrownBy(() -> RunEvidence.rejectReceiptAlias(run, run.resolve("new.json")))
        .hasMessageContaining("immutable");
    Files.createDirectory(run.resolve("child"));
    Path link = directory.resolve("linked-child");
    try {
      Files.createSymbolicLink(link, run.resolve("child"));
    } catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
      org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable");
    }
    assertThatThrownBy(() -> RunEvidence.rejectReceiptAlias(run, link.resolve("../run.json")))
        .hasMessageContaining("dot segments");
    assertThatThrownBy(() -> RunEvidence.rejectReceiptAlias(run, link.resolve("new.json")))
        .hasMessageContaining("immutable");
  }

  @Test
  void concurrentFinalizersNeverOverwriteClaimedRevisions() throws Exception {
    Path run = prepare();
    raw(run, "cbom-1.json", false, 0);
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
      for (int index = 0; index < 2; index++) {
        ReportMojo report = configured(new ReportMojo());
        tasks.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    report.execute();
                    return true;
                  } catch (org.apache.maven.plugin.MojoExecutionException expected) {
                    return false;
                  }
                }));
      }
      start.countDown();
      int successes = 0;
      for (var task : tasks) {
        if (task.get()) {
          successes++;
        }
      }
      assertThat(successes).isBetween(1, 2);
      RunEvidence.verify(run, runId, MODULE, VERSION, "1");
      byte[] original = Files.readAllBytes(manifest(run, "1"));
      configured(new ReportMojo()).execute();
      assertThat(Files.readAllBytes(manifest(run, "1"))).isEqualTo(original);
    } finally {
      pool.shutdownNow();
    }
  }

  private Path prepare() throws Exception {
    return RunEvidence.prepare(directory, runId, MODULE, VERSION);
  }

  private static Path manifest(Path run, String revision) {
    return RunEvidence.sealedDirectory(run, revision).resolve(RunEvidence.MANIFEST);
  }

  private void raw(Path run, String name, boolean broken, int errors) throws Exception {
    Inventory inventory = new Inventory();
    if (broken) {
      inventory.record(
          CryptoAsset.of(Primitive.MESSAGE_DIGEST, "MD5", 0, "test"),
          new Occurrence("example.Payment", "hash", 12, null));
    }
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    Cbom.HEALTH_PROPERTIES.forEach(key -> properties.put(key, "0"));
    properties.put("cryptocensus:transformErrors", Integer.toString(errors));
    Cbom.write(inventory, new Cbom.Metadata("payments", properties), run.resolve(name));
  }

  private <T extends CheckMojo> T configured(T mojo) throws Exception {
    set(mojo, "project", project());
    set(mojo, "destDir", directory.toFile());
    set(mojo, "runId", runId);
    set(mojo, "revision", "1");
    set(mojo, "testOutcome", "failed");
    set(mojo, "failOn", "broken");
    set(mojo, "failOnUnknown", true);
    set(mojo, "failOnMissingData", true);
    set(mojo, "failOnIncompleteCollection", true);
    set(mojo, "output", directory.resolve("unused.json").toFile());
    return mojo;
  }

  private MavenProject project() {
    MavenProject project = new MavenProject();
    project.setGroupId("example");
    project.setArtifactId("payments");
    project.setVersion(VERSION);
    Build build = new Build();
    build.setDirectory(directory.toString());
    build.setTestOutputDirectory(directory.resolve("test-classes").toString());
    project.setBuild(build);
    return project;
  }

  private static void set(CheckMojo mojo, String name, Object value) throws Exception {
    Field field = CheckMojo.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(mojo, value);
  }
}
