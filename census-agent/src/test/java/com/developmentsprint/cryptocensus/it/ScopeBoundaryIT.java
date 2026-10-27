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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopeBoundaryIT {
  @TempDir Path temporary;

  @Test
  void dynamicScopesFollowActualExecutionWithoutAsyncInheritance() throws Exception {
    Path output = temporary.resolve("boundaries.json");
    Path sidecar = temporary.resolve("boundaries.evidence.json");
    Path sources = temporary.resolve("sources.json");
    String classes = ChildJvm.classpathEntry(ScopeBoundaryWorkload.class);
    Files.write(
        sources,
        new SourceCatalog(
                1,
                List.of(
                    new SourceCatalog.Entry(
                        Path.of(classes).toUri().toASCIIString(),
                        "fixture:boundaries",
                        "fixture:boundaries",
                        ContextModel.Origin.TEST,
                        "fixture-deployment")))
            .encode());
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
            List.of(classes, ChildJvm.classpathEntry(CryptoFlow.class)),
            ScopeBoundaryWorkload.class,
            List.of());
    assertThat(result.exitCode()).as(result.stderr()).isZero();
    var evidence = EvidenceCodec.read(sidecar);
    var cbom = EvidenceCodec.validatePair(evidence, Cbom.readBytes(output));
    assertThat(cbom.properties())
        .containsEntry("cryptocensus:recordingFailures", "0")
        .containsEntry("cryptocensus:transformErrors", "0");
    var observations =
        evidence.observations().stream()
            .filter(
                observation ->
                    observation
                            .occurrence()
                            .location()
                            .equals(ScopeBoundaryWorkload.class.getName())
                        && observation.occurrence().symbol().equals("digest"))
            .toList();
    boolean virtual = Runtime.version().feature() >= 21;
    for (String flow : List.of("worker", "future", "caller", "helping", "reactive")) {
      assertThat(
              observations.stream()
                  .filter(
                      observation ->
                          observation.context().path().size() == 1
                              && observation
                                  .context()
                                  .path()
                                  .get(0)
                                  .subject()
                                  .flowId()
                                  .equals(flow))
                  .mapToLong(EvidenceDocument.Observation::count)
                  .sum())
          .as(flow)
          .isEqualTo(1);
    }
    assertThat(
            observations.stream()
                .filter(
                    observation ->
                        observation.context().association()
                            == EvidenceDocument.Association.NO_SCOPE)
                .mapToLong(EvidenceDocument.Observation::count)
                .sum())
        .isEqualTo(virtual ? 5 : 3);
    if (virtual) {
      assertThat(
              observations.stream()
                  .filter(
                      observation ->
                          observation.context().path().size() == 1
                              && observation
                                  .context()
                                  .path()
                                  .get(0)
                                  .subject()
                                  .flowId()
                                  .equals("virtual"))
                  .mapToLong(EvidenceDocument.Observation::count)
                  .sum())
          .isEqualTo(1);
    }
    assertThat(observations.stream().mapToLong(EvidenceDocument.Observation::count).sum())
        .isEqualTo(virtual ? 11 : 8);
    assertThat(evidence.health().get(EvidenceDocument.Counter.SCOPE_INTEGRITY)).isZero();
    assertThat(evidence.health().get(EvidenceDocument.Counter.SCOPE_RESTORE_FAILURES)).isZero();
    assertThat(evidence.health().get(EvidenceDocument.Counter.TRANSFORM_ERRORS)).isZero();
  }
}
