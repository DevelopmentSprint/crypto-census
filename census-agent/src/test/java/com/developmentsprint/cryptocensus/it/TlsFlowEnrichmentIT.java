package com.developmentsprint.cryptocensus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.DocumentFiles;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.SourceCatalog;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsFlowEnrichmentIT {
  @TempDir Path tmp;

  @Test
  void actualAgentJoinsRetainedAssetsAndInitiatingScopesWithoutDanglingReferences()
      throws Exception {
    Path keystore = ChildJvm.keystore(tmp);
    Path output = tmp.resolve("tls.json");
    Path sources = tmp.resolve("sources.json");
    String classes = ChildJvm.classpathEntry(TlsFlowWorkload.class);
    DocumentFiles.write(
        sources,
        new SourceCatalog(
                1,
                List.of(
                    new SourceCatalog.Entry(
                        Path.of(classes).toUri().toString(),
                        "fixture:tls",
                        "fixture:tls",
                        ContextModel.Origin.TEST,
                        "tls-deployment")))
            .encode());
    var result =
        ChildJvm.run(
            tmp,
            List.of(
                "-Djdk.tls.namedGroups=X25519",
                "-javaagent:"
                    + ChildJvm.agentJar()
                    + "=output="
                    + output
                    + ",summary=false,enrichment=true,tlsCorrelation=true,sourcesFile="
                    + sources),
            List.of(classes, ChildJvm.classpathEntry(CryptoFlow.class)),
            TlsFlowWorkload.class,
            List.of(keystore.toString(), ChildJvm.KEYSTORE_PASSWORD));
    assertThat(result.exitCode()).as(result.stderr()).isZero();
    var evidence = EvidenceCodec.read(tmp.resolve("tls.evidence.json"));
    var cbom = EvidenceCodec.validatePair(evidence, Cbom.readBytes(output));
    assertThat(cbom.properties().get("cryptocensus:recordingFailures")).isEqualTo("0");
    assertThat(evidence.handshakes()).isNotEmpty();
    assertThat(evidence.handshakes())
        .anySatisfy(
            attempt -> {
              assertThat(attempt.outcome()).isEqualTo(EvidenceDocument.Outcome.COMPLETED);
              assertThat(attempt.relatedAssetRefs()).isNotEmpty();
              assertThat(attempt.initiation().path())
                  .extracting(scope -> scope.subject().flowId())
                  .contains("network");
            });
    assertThat(evidence.handshakes())
        .anySatisfy(
            attempt -> assertThat(attempt.outcome()).isEqualTo(EvidenceDocument.Outcome.FAILED));
    assertThat(evidence.capabilities())
        .anySatisfy(
            capability -> {
              assertThat(capability.feature()).isEqualTo("tls-correlation");
              assertThat(capability.status())
                  .isEqualTo(EvidenceDocument.CapabilityStatus.SUPPORTED);
            });
    assertThat(evidence.health().get(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED)).isZero();
  }
}
