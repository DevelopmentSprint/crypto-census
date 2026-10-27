package com.developmentsprint.cryptocensus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import java.nio.file.Path;
import java.util.List;
import org.bouncycastle.asn1.cms.CMSObjectIdentifiers;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlternativeProviderIT {
  @TempDir Path temporary;

  @Test
  void realAlternateProviderCompletesWithoutInventedSunJsseJoins() throws Exception {
    final Path store = ChildJvm.keystore(temporary);
    for (boolean enrichment : List.of(false, true)) {
      Path output = temporary.resolve("provider-" + enrichment + ".json");
      var result =
          ChildJvm.run(
              temporary,
              List.of(
                  "-javaagent:"
                      + ChildJvm.agentJar()
                      + "=output="
                      + output
                      + ",summary=false,enrichment="
                      + enrichment
                      + ",tlsCorrelation=true,applicationId=provider-fixture"),
              List.of(
                  ChildJvm.classpathEntry(AlternativeProviderWorkload.class),
                  ChildJvm.classpathEntry(CryptoFlow.class),
                  ChildJvm.classpathEntry(BouncyCastleJsseProvider.class),
                  ChildJvm.classpathEntry(BouncyCastleProvider.class),
                  ChildJvm.classpathEntry(CMSObjectIdentifiers.class)),
              AlternativeProviderWorkload.class,
              List.of(store.toString(), ChildJvm.KEYSTORE_PASSWORD));
      assertThat(result.exitCode()).as(result.stderr()).isZero();
      assertThat(result.stderr()).contains("completed-provider=BCJSSE");
      var cbom = Cbom.read(output);
      cbom.validateAgentHealth();
      assertThat(cbom.properties().get("cryptocensus:recordingFailures")).isEqualTo("0");
      assertThat(
              cbom.inventory().entries().stream()
                  .flatMap(entry -> entry.occurrences().entrySet().stream())
                  .filter(entry -> "marker".equals(entry.getKey().symbol()))
                  .mapToLong(java.util.Map.Entry::getValue)
                  .sum())
          .isEqualTo(1);
      if (enrichment) {
        var evidence = EvidenceCodec.read(temporary.resolve("provider-true.evidence.json"));
        EvidenceCodec.validatePair(evidence, Cbom.readBytes(output));
        assertThat(evidence.handshakes()).isEmpty();
        assertThat(evidence.capabilities())
            .anySatisfy(
                capability -> {
                  assertThat(capability.feature()).isEqualTo("tls-other-providers");
                  assertThat(capability.status())
                      .isEqualTo(EvidenceDocument.CapabilityStatus.UNAVAILABLE);
                });
        assertThat(evidence.flows())
            .anySatisfy(
                flow ->
                    assertThat(flow.definition().subject().flowId())
                        .isEqualTo("alternative-provider"));
        assertThat(evidence.health().get(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED))
            .isZero();
      }
    }
  }
}
