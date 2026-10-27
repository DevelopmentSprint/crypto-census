package com.developmentsprint.cryptocensus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeAcceptanceIT {
  @TempDir Path temporary;

  @Test
  void realRetransformationAndProxyDelegationPreserveCountsAndApplicationResults()
      throws Exception {
    Path control = controlAgent();
    for (boolean enrichment : List.of(false, true)) {
      Path output = temporary.resolve("retransform-" + enrichment + ".json");
      Path proof = temporary.resolve("retransform-" + enrichment + ".txt");
      var result =
          ChildJvm.run(
              temporary,
              List.of(
                  "-javaagent:" + control,
                  "-javaagent:"
                      + ChildJvm.agentJar()
                      + "=output="
                      + output
                      + ",summary=false,enrichment="
                      + enrichment
                      + ",applicationId=fixture"),
              List.of(
                  ChildJvm.classpathEntry(RetransformWorkload.class),
                  ChildJvm.classpathEntry(CryptoFlow.class)),
              RetransformWorkload.class,
              List.of(proof.toString(), Boolean.toString(enrichment)));
      assertThat(result.exitCode()).as(result.stderr()).isZero();
      assertThat(Files.readString(proof))
          .contains(enrichment ? "bindings=2;after=2" : "bindings=0;after=0");
    }
    var baseline = Cbom.read(temporary.resolve("retransform-false.json"));
    Path output = temporary.resolve("retransform-true.json");
    var cbom = Cbom.read(output);
    assertThat(cbom.inventory().entries()).isEqualTo(baseline.inventory().entries());
    assertThat(cbom.health("cryptocensus:recordingFailures")).isZero();
    var evidence = EvidenceCodec.read(temporary.resolve("retransform-true.evidence.json"));
    EvidenceCodec.validatePair(evidence, Cbom.readBytes(output));
    assertThat(evidence.flows())
        .extracting(flow -> flow.definition().subject().flowId())
        .containsExactlyInAnyOrder("instance", "static");
    assertThat(evidence.flows())
        .allSatisfy(flow -> assertThat(flow.definition().bindings()).hasSize(1));
    assertThat(evidence.observations().stream().filter(row -> !row.context().path().isEmpty()))
        .allSatisfy(row -> assertThat(row.context().path()).hasSize(1));
    assertThat(count(evidence, "instance")).isEqualTo(9);
    assertThat(count(evidence, "static")).isEqualTo(3);
    assertThat(
            evidence.observations().stream()
                .filter(row -> row.context().association() == EvidenceDocument.Association.NO_SCOPE)
                .mapToLong(EvidenceDocument.Observation::count)
                .sum())
        .isEqualTo(6);
    assertThat(evidence.health().get(EvidenceDocument.Counter.SCOPE_INTEGRITY)).isZero();
    assertThat(evidence.health().get(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED)).isZero();
  }

  @Test
  void positiveShutdownBudgetExpiresWhileRealPublisherIsStillBlocked() throws Exception {
    Path output = temporary.resolve("delayed.json");
    Path sidecar = temporary.resolve("delayed.evidence.json");
    Path proof = temporary.resolve("delayed.txt");
    var result = delayed(output, sidecar, proof, "delay");
    assertThat(result.exitCode()).as(result.stderr()).isZero();
    assertThat(Files.readString(proof))
        .isEqualTo(
            "queued=true;core-finished-while-blocked=true;output-failures=1;late-output=false");
    assertThat(result.stderr()).contains("Enrichment evidence publication incomplete");
    var cbom = Cbom.read(output);
    assertThat(cbom.inventory().entries()).isNotEmpty();
    assertThat(cbom.health("cryptocensus:recordingFailures")).isZero();
    assertThat(cbom.health("cryptocensus:drainTimedOut")).isZero();
    assertThat(sidecar).doesNotExist();
  }

  @Test
  void abruptExitCannotLeaveApparentCompletedEvidence() throws Exception {
    Path output = temporary.resolve("halt.json");
    Path sidecar = temporary.resolve("halt.evidence.json");
    Path proof = temporary.resolve("halt.txt");
    var result = delayed(output, sidecar, proof, "halt");
    assertThat(result.exitCode()).as(result.stderr()).isZero();
    assertThat(Files.readString(proof)).isEqualTo("crypto-completed-before-halt");
    assertThat(output).doesNotExist();
    assertThat(sidecar).doesNotExist();
  }

  @Test
  void overlappingSuccessAndFailureKeepTheirOwnMarkersOnOneDelegatedWorker() throws Exception {
    Path store = ChildJvm.keystore(temporary);
    Path output = temporary.resolve("mixed.json");
    Path proof = temporary.resolve("mixed.txt");
    var result =
        ChildJvm.run(
            temporary,
            List.of(
                "-Djdk.tls.namedGroups=X25519",
                "-javaagent:"
                    + ChildJvm.agentJar()
                    + "=output="
                    + output
                    + ",summary=false,enrichment=true,tlsCorrelation=true,applicationId=fixture"),
            List.of(
                ChildJvm.classpathEntry(MixedTlsWorkload.class),
                ChildJvm.classpathEntry(CryptoFlow.class)),
            MixedTlsWorkload.class,
            List.of(store.toString(), ChildJvm.KEYSTORE_PASSWORD, proof.toString()));
    assertThat(result.exitCode()).as(result.stderr()).isZero();
    assertThat(Files.readString(proof))
        .isEqualTo("accepted=true;rejected=true;submitters=2;workers=1");
    var evidence = EvidenceCodec.read(temporary.resolve("mixed.evidence.json"));
    var cbom = EvidenceCodec.validatePair(evidence, Cbom.readBytes(output));
    String acceptedMarker = marker(cbom, "SHA3-256");
    String rejectedMarker = marker(cbom, "SHA3-512");
    assertThat(evidence.handshakes()).hasSize(4);
    assertThat(evidence.handshakes())
        .extracting(EvidenceDocument.Handshake::connectionId)
        .doesNotHaveDuplicates();
    assertThat(evidence.handshakes())
        .allSatisfy(
            attempt -> {
              assertThat(attempt.initiation().path()).hasSize(1);
              String flow = attempt.initiation().path().get(0).subject().flowId();
              if ("accepted".equals(flow)) {
                assertThat(attempt.outcome()).isEqualTo(EvidenceDocument.Outcome.COMPLETED);
                assertThat(attempt.relatedAssetRefs()).doesNotContain(rejectedMarker);
              } else {
                assertThat(flow).isEqualTo("rejected");
                assertThat(attempt.outcome()).isNotEqualTo(EvidenceDocument.Outcome.COMPLETED);
                assertThat(attempt.relatedAssetRefs()).doesNotContain(acceptedMarker);
              }
            });
    assertThat(
            evidence.handshakes().stream()
                .filter(attempt -> attempt.relatedAssetRefs().contains(acceptedMarker)))
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.role()).isEqualTo(EvidenceDocument.PeerRole.CLIENT);
              assertThat(attempt.outcome()).isEqualTo(EvidenceDocument.Outcome.COMPLETED);
            });
    assertThat(
            evidence.handshakes().stream()
                .filter(attempt -> attempt.relatedAssetRefs().contains(rejectedMarker)))
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.role()).isEqualTo(EvidenceDocument.PeerRole.CLIENT);
              assertThat(attempt.outcome()).isEqualTo(EvidenceDocument.Outcome.FAILED);
            });
    assertThat(evidence.health().get(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED)).isZero();
    assertThat(cbom.health("cryptocensus:recordingFailures")).isZero();
  }

  private static String marker(Cbom.Document cbom, String algorithm) {
    var entries =
        cbom.inventory().entries().stream()
            .filter(entry -> algorithm.equals(entry.asset().algorithm()))
            .toList();
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).total()).isEqualTo(1);
    return entries.get(0).asset().ref();
  }

  private ChildJvm.Result delayed(Path output, Path sidecar, Path proof, String mode)
      throws Exception {
    return ChildJvm.run(
        temporary,
        List.of(
            "-javaagent:"
                + ChildJvm.agentJar()
                + "=output="
                + output
                + ",summary=false,enrichment=true,tlsCorrelation=true,applicationId=fixture"),
        List.of(
            ChildJvm.classpathEntry(DelayedPublicationWorkload.class),
            ChildJvm.classpathEntry(CryptoFlow.class)),
        DelayedPublicationWorkload.class,
        List.of(output.toString(), sidecar.toString(), proof.toString(), mode));
  }

  private static long count(EvidenceDocument evidence, String flow) {
    return evidence.observations().stream()
        .filter(
            row ->
                !row.context().path().isEmpty()
                    && flow.equals(
                        row.context()
                            .path()
                            .get(row.context().path().size() - 1)
                            .subject()
                            .flowId()))
        .mapToLong(EvidenceDocument.Observation::count)
        .sum();
  }

  private Path controlAgent() throws Exception {
    var manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("Premain-Class", RuntimeControlAgent.class.getName());
    manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
    Path jar = temporary.resolve("runtime-control.jar");
    String resource = RuntimeControlAgent.class.getName().replace('.', '/') + ".class";
    try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest);
        var input = RuntimeControlAgent.class.getClassLoader().getResourceAsStream(resource)) {
      output.putNextEntry(new JarEntry(resource));
      java.util.Objects.requireNonNull(input).transferTo(output);
      output.closeEntry();
    }
    return jar;
  }
}
