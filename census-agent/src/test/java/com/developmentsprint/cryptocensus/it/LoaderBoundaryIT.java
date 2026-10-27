package com.developmentsprint.cryptocensus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.SourceCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoaderBoundaryIT {
  @TempDir Path temporary;

  @Test
  void identicalBytecodeAndSourceUrlRetainExplicitPerLoaderDeploymentIdentity() throws Exception {
    var evidence = run("named");
    var flows =
        evidence.flows().stream()
            .filter(flow -> flow.definition().subject().flowId().equals("shared"))
            .toList();
    assertThat(flows).hasSize(2);
    assertThat(flows)
        .extracting(flow -> flow.definition().subject().applicationId())
        .containsExactlyInAnyOrder("app-one", "app-two");
    var identities =
        evidence.identities().stream()
            .filter(
                identity ->
                    flows.stream().anyMatch(flow -> flow.identityId().equals(identity.id())))
            .toList();
    assertThat(identities)
        .extracting(EvidenceDocument.IdentityMapping::deploymentId)
        .containsExactlyInAnyOrder("deployment-one", "deployment-two");
    assertThat(identities.stream().map(EvidenceDocument.IdentityMapping::loaderId).distinct())
        .hasSize(2);
    assertThat(
            evidence.observations().stream()
                .filter(
                    observation ->
                        observation.occurrence().location().equals("fixture.SharedFlow")))
        .hasSize(2)
        .allSatisfy(
            observation -> {
              assertThat(observation.count()).isEqualTo(1);
              assertThat(observation.context().path()).hasSize(1);
              assertThat(observation.context().association())
                  .isEqualTo(EvidenceDocument.Association.SYNCHRONOUS_SCOPE);
            });
    var observed =
        evidence.observations().stream()
            .filter(observation -> observation.occurrence().location().equals("fixture.SharedFlow"))
            .toList();
    assertThat(observed.stream().map(observation -> observation.context().identityId()).distinct())
        .containsExactlyInAnyOrderElementsOf(
            identities.stream().map(EvidenceDocument.IdentityMapping::id).toList());
    for (var observation : observed) {
      var identity =
          identities.stream()
              .filter(candidate -> candidate.id().equals(observation.context().identityId()))
              .findFirst()
              .orElseThrow();
      assertThat(observation.projectFrame().origin()).isEqualTo(ContextModel.Origin.TEST);
      assertThat(observation.projectFrame().sourceArtifact()).isEqualTo(identity.sourceArtifact());
      assertThat(observation.context().path())
          .allSatisfy(
              scope -> {
                assertThat(scope.identityId()).isEqualTo(identity.id());
                assertThat(scope.subject().applicationId()).isEqualTo(identity.applicationId());
              });
    }
    assertThat(evidence.health().get(EvidenceDocument.Counter.ANNOTATION_ERRORS)).isZero();
  }

  @Test
  void loaderThatCannotSeeBridgePreservesApplicationAndReportsEnrichmentGap() throws Exception {
    var evidence = run("hidden");
    assertThat(evidence.health().get(EvidenceDocument.Counter.ANNOTATION_ERRORS)).isPositive();
    assertThat(evidence.capabilities())
        .anySatisfy(
            capability -> {
              assertThat(capability.feature()).isEqualTo("annotation-scopes");
              assertThat(capability.status()).isEqualTo(EvidenceDocument.CapabilityStatus.PARTIAL);
            });
    assertThat(
            evidence.observations().stream()
                .filter(
                    observation ->
                        observation.occurrence().location().equals("fixture.SharedFlow")))
        .isNotEmpty()
        .allSatisfy(
            observation ->
                assertThat(observation.context().association())
                    .isNotEqualTo(EvidenceDocument.Association.SYNCHRONOUS_SCOPE));
  }

  private EvidenceDocument run(String mode) throws Exception {
    Path classes = Files.createDirectory(temporary.resolve("fixture-classes"));
    Path java = temporary.resolve("SharedFlow.java");
    Files.writeString(
        java,
        """
        package fixture;
        import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
        public final class SharedFlow {
          @CryptoFlow("shared")
          public static void run() throws Exception {
            java.security.MessageDigest.getInstance("SHA3-256").digest(new byte[] {1});
          }
        }
        """);
    assertThat(
            ToolProvider.getSystemJavaCompiler()
                .run(
                    null,
                    null,
                    null,
                    "--release",
                    "17",
                    "-classpath",
                    ChildJvm.classpathEntry(CryptoFlow.class),
                    "-d",
                    classes.toString(),
                    java.toString()))
        .isZero();
    Path sources = temporary.resolve("sources.json");
    Files.write(
        sources,
        new SourceCatalog(1, List.of(mapping(classes, "one"), mapping(classes, "two"))).encode());
    Path output = temporary.resolve("loaders.json");
    var result =
        ChildJvm.run(
            temporary,
            List.of(
                "-javaagent:"
                    + ChildJvm.agentJar()
                    + "=output="
                    + output
                    + ",enrichment=true,sourcesFile="
                    + sources),
            List.of(
                ChildJvm.classpathEntry(LoaderBoundaryWorkload.class),
                ChildJvm.classpathEntry(CryptoFlow.class)),
            LoaderBoundaryWorkload.class,
            List.of(classes.toString(), mode));
    assertThat(result.exitCode()).as(result.stderr()).isZero();
    var evidence = EvidenceCodec.read(temporary.resolve("loaders.evidence.json"));
    var cbom = EvidenceCodec.validatePair(evidence, Cbom.readBytes(output));
    assertThat(cbom.properties())
        .containsEntry("cryptocensus:recordingFailures", "0")
        .containsEntry("cryptocensus:transformErrors", "0");
    return evidence;
  }

  private static SourceCatalog.Entry mapping(Path classes, String suffix) {
    return new SourceCatalog.Entry(
        classes.toUri().toASCIIString(),
        "fixture:shared",
        "app-" + suffix,
        ContextModel.Origin.TEST,
        "deployment-" + suffix,
        "deployment-" + suffix);
  }
}
