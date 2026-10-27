package com.developmentsprint.cryptocensus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.assessment.AssessmentEngine;
import com.developmentsprint.cryptocensus.assessment.AssessmentInputs;
import com.developmentsprint.cryptocensus.assessment.ContextDocument;
import com.developmentsprint.cryptocensus.assessment.PolicyProfile;
import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceIsolationIT {
  @TempDir Path temporary;

  @Test
  void standaloneBystanderLoaderDoesNotCompeteButTwoDeclaringLoadersDo() throws Exception {
    for (int deployments : List.of(1, 2)) {
      Path cbom = temporary.resolve("standalone-" + deployments + ".json");
      run(StandaloneLoaderWorkload.class, cbom, false, List.of(Integer.toString(deployments)));
      var evidence = EvidenceCodec.read(sidecar(cbom));
      assertThat(
              evidence.identities().stream()
                  .map(EvidenceDocument.IdentityMapping::loaderId)
                  .distinct()
                  .count())
          .isGreaterThan(deployments);
      assertThat(evidence.flows()).hasSize(deployments);
      var report = assess(cbom);
      var scoped =
          objects(report.get("findings")).stream()
              .filter(finding -> finding.get("subject") != null)
              .toList();
      assertThat(scoped)
          .hasSize(deployments)
          .allSatisfy(
              finding -> {
                assertThat(CanonicalJson.object(finding.get("decision")))
                    .containsEntry(
                        "displayPriority", deployments == 1 ? "URGENT_PLANNING" : "NEEDS_CONTEXT");
                assertThat(
                        CanonicalJson.array(finding, "gaps")
                            .contains("AMBIGUOUS_DEPLOYMENT_OWNERSHIP"))
                    .isEqualTo(deployments > 1);
              });
    }
  }

  @Test
  void invalidAnnotationCannotEraseGoodTlsNegotiationOrPriority() throws Exception {
    Path store = ChildJvm.keystore(temporary);
    Path cbom = temporary.resolve("annotation-tls.json");
    run(
        AnnotationTlsWorkload.class,
        cbom,
        true,
        List.of(store.toString(), ChildJvm.KEYSTORE_PASSWORD));
    var evidence = EvidenceCodec.read(sidecar(cbom));
    assertThat(evidence.health().get(EvidenceDocument.Counter.ANNOTATION_ERRORS)).isPositive();
    assertThat(evidence.health().get(EvidenceDocument.Counter.TRANSFORM_ERRORS)).isZero();
    assertThat(evidence.capabilities())
        .anySatisfy(
            capability -> {
              assertThat(capability.feature()).isEqualTo("annotation-scopes");
              assertThat(capability.status()).isEqualTo(EvidenceDocument.CapabilityStatus.PARTIAL);
            });
    assertThat(evidence.handshakes())
        .hasSize(2)
        .allSatisfy(
            attempt -> {
              assertThat(attempt.outcome()).isEqualTo(EvidenceDocument.Outcome.COMPLETED);
              assertThat(attempt.resumption().resumed()).isEqualTo(EvidenceDocument.Flag.NO);
            });
    var tls =
        objects(assess(cbom).get("findings")).stream()
            .filter(finding -> "TLS_CONFIDENTIALITY".equals(finding.get("kind")))
            .toList();
    assertThat(tls)
        .hasSize(2)
        .allSatisfy(
            finding -> {
              assertThat(finding).containsEntry("qualified", true);
              assertThat(CanonicalJson.object(finding.get("technical")))
                  .containsEntry("quantumExposure", "VULNERABLE");
            });
    assertThat(tls.stream().filter(finding -> finding.get("subject") != null).toList())
        .hasSize(1)
        .allSatisfy(
            finding ->
                assertThat(CanonicalJson.object(finding.get("decision")))
                    .containsEntry("displayPriority", "URGENT_PLANNING"));
  }

  private void run(Class<?> workload, Path output, boolean tls, List<String> arguments)
      throws Exception {
    var result =
        ChildJvm.run(
            temporary,
            List.of(
                "-Djdk.tls.namedGroups=X25519",
                "-javaagent:"
                    + ChildJvm.agentJar()
                    + "=output="
                    + output
                    + ",summary=false,enrichment=true,applicationId=standalone,tlsCorrelation="
                    + tls),
            List.of(ChildJvm.classpathEntry(workload), ChildJvm.classpathEntry(CryptoFlow.class)),
            workload,
            arguments);
    assertThat(result.exitCode()).as(result.stderr()).isZero();
    EvidenceCodec.validatePair(EvidenceCodec.read(sidecar(output)), Cbom.readBytes(output));
    Cbom.read(output).validateAgentHealth();
  }

  private static Map<String, Object> assess(Path cbom) throws Exception {
    return AssessmentEngine.assess(
            List.of(
                new AssessmentInputs.Input(
                    Cbom.readBytes(cbom), Files.readAllBytes(sidecar(cbom)), "fixture", "test")),
            new ContextDocument(1, List.of(), List.of(), List.of(), List.of()),
            PolicyProfile.defaults())
        .data();
  }

  private static Path sidecar(Path cbom) {
    return cbom.resolveSibling(cbom.getFileName().toString().replace(".json", ".evidence.json"));
  }

  private static List<Map<String, Object>> objects(Object value) {
    return CanonicalJson.array(Map.of("values", value), "values").stream()
        .map(CanonicalJson::object)
        .toList();
  }
}
