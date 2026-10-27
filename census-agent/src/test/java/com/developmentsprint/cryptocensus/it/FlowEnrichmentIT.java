package com.developmentsprint.cryptocensus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.DocumentFiles;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.SourceCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowEnrichmentIT {
  @TempDir Path tmp;

  @Test
  void lateAnnotationDiscoveryPreservesCoreInventoryAndNestedRequirements() throws Exception {
    Path baseline = run("baseline", false, true, "", List.of());
    Path enriched = run("enriched", true, true, "", List.of());
    var ordinary = Cbom.read(baseline);
    var cbom = Cbom.read(enriched);
    assertThat(cbom.inventory().entries()).isEqualTo(ordinary.inventory().entries());
    assertThat(cbom.properties().get("cryptocensus:recordingFailures")).isEqualTo("0");
    var evidence = EvidenceCodec.read(sidecar("enriched"));
    EvidenceCodec.validatePair(evidence, Cbom.readBytes(enriched));
    assertThat(evidence.flows())
        .extracting(flow -> flow.definition().subject().flowId())
        .contains("archive", "transport", "overload", "unobserved");
    assertThat(evidence.observations())
        .anySatisfy(
            observation ->
                assertThat(observation.context().path())
                    .extracting(scope -> scope.subject().flowId())
                    .containsExactly("archive", "transport"));
    assertThat(evidence.observations())
        .anySatisfy(
            observation ->
                assertThat(observation.context().association())
                    .isEqualTo(EvidenceDocument.Association.NO_SCOPE));
    assertThat(
            evidence.flows().stream()
                .filter(flow -> flow.definition().subject().flowId().equals("overload"))
                .findFirst()
                .orElseThrow()
                .definition()
                .bindings())
        .hasSize(2);
    assertThat(
            evidence.flows().stream()
                .filter(flow -> flow.definition().subject().flowId().equals("archive"))
                .findFirst()
                .orElseThrow()
                .definition()
                .declarations())
        .anySatisfy(
            declaration -> {
              assertThat(declaration.field()).isEqualTo(ContextModel.Field.CONFIDENTIALITY_YEARS);
              assertThat(declaration.value()).isEqualTo("20");
            });
    assertThat(evidence.observations())
        .allSatisfy(
            observation ->
                assertThat(observation.projectFrame().origin())
                    .isEqualTo(ContextModel.Origin.TEST));
    assertThat(evidence.capabilities())
        .extracting(EvidenceDocument.Capability::feature)
        .contains("annotation-scopes", "scope-propagation", "scope-resource-failure");
  }

  @Test
  void absentAnnotationDependencyPreservesApplicationAndDiagnosesLostScope() throws Exception {
    Path cbom = run("missing-api", true, false, "", List.of());
    var evidence = EvidenceCodec.read(sidecar("missing-api"));
    EvidenceCodec.validatePair(evidence, Cbom.readBytes(cbom));
    assertThat(evidence.health().get(EvidenceDocument.Counter.ANNOTATION_ERRORS)).isPositive();
    assertThat(evidence.observations())
        .anySatisfy(
            observation ->
                assertThat(observation.context().association())
                    .isEqualTo(EvidenceDocument.Association.SCOPE_LOST));
    assertThat(Cbom.read(cbom).properties().get("cryptocensus:recordingFailures")).isEqualTo("0");
  }

  @Test
  void invalidDeclarationsIdentifyMethodsWithoutLeakingValues() throws Exception {
    Path cbom = run("invalid", true, true, "", List.of("invalid"));
    var evidence = EvidenceCodec.read(sidecar("invalid"));
    EvidenceCodec.validatePair(evidence, Cbom.readBytes(cbom));
    assertThat(evidence.health().get(EvidenceDocument.Counter.ANNOTATION_ERRORS)).isPositive();
    assertThat(evidence.flows()).isEmpty();
    assertThat(evidence.observations())
        .allSatisfy(
            value ->
                assertThat(value.context().association())
                    .isEqualTo(EvidenceDocument.Association.SCOPE_LOST));
  }

  @Test
  void caughtStackExhaustionDoesNotClaimTrustedParentInTheCleanupWindow() throws Exception {
    Path cbom = run("stack", true, true, "", List.of("stack"));
    var evidence = EvidenceCodec.read(sidecar("stack"));
    EvidenceCodec.validatePair(evidence, Cbom.readBytes(cbom));
    assertThat(evidence.observations())
        .anySatisfy(
            observation ->
                assertThat(observation.context().association())
                    .isEqualTo(EvidenceDocument.Association.SCOPE_LOST));
    assertThat(evidence.health().get(EvidenceDocument.Counter.SCOPE_INTEGRITY)).isPositive();
  }

  @Test
  void sidecarOutputFailureDoesNotAlterCoreCollectionHealth() throws Exception {
    Path directory = Files.createDirectory(tmp.resolve("blocked-output"));
    Path cbom = run("output-failure", true, true, ",evidenceOutput=" + directory, List.of());
    assertThat(Cbom.read(cbom).properties().get("cryptocensus:recordingFailures")).isEqualTo("0");
    assertThat(Files.isDirectory(directory)).isTrue();
  }

  private Path run(String name, boolean enrichment, boolean api, String extra, List<String> args)
      throws Exception {
    Path output = tmp.resolve(name + ".json");
    Path sources = tmp.resolve(name + "-sources.json");
    String classes = ChildJvm.classpathEntry(FlowWorkload.class);
    var catalog =
        new SourceCatalog(
            1,
            List.of(
                new SourceCatalog.Entry(
                    Path.of(classes).toUri().toString(),
                    "fixture:app",
                    "fixture:app",
                    ContextModel.Origin.TEST,
                    "fixture-deployment")));
    DocumentFiles.write(sources, catalog.encode());
    List<String> classpath = new ArrayList<>(List.of(classes));
    if (api) {
      classpath.add(ChildJvm.classpathEntry(CryptoFlow.class));
    }
    var result =
        ChildJvm.run(
            tmp,
            List.of(
                "-Xss512k",
                "-javaagent:"
                    + ChildJvm.agentJar()
                    + "=output="
                    + output
                    + ",summary=false,enrichment="
                    + enrichment
                    + ",sourcesFile="
                    + sources
                    + extra),
            classpath,
            FlowWorkload.class,
            args);
    assertThat(result.exitCode()).as(result.stderr()).isZero();
    if (enrichment && !api) {
      assertThat(result.stderr())
          .contains(
              "INVALID_OR_UNRESOLVED_DECLARATION",
              "LateAnnotatedWorkload",
              "method=run descriptor=(Z)V");
    }
    if (args.contains("invalid")) {
      assertThat(result.stderr())
          .contains(
              "INVALID_OR_UNRESOLVED_DECLARATION",
              "InvalidAnnotationsWorkload",
              "method=invalid descriptor=()V",
              "UNSUPPORTED_METHOD",
              "method=unsupported descriptor=()V")
          .doesNotContain("private invalid value", "private-classification");
    }
    return output;
  }

  private Path sidecar(String name) {
    return tmp.resolve(name + ".evidence.json");
  }
}
