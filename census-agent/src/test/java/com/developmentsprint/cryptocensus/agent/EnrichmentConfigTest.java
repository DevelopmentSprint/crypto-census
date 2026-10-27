package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EnrichmentConfigTest {
  @Test
  void displayNameNeverSuppliesStableIdentityAndEnrichmentIsOptIn() {
    var config = AgentConfig.parse("app=Friendly%20Name");
    assertThat(config.enrichment).isFalse();
    assertThat(config.applicationId).isNull();
    assertThat(config.sourcesFile).isNull();
    assertThat(config.evidenceOutput).isEqualTo(Path.of("cryptocensus-cbom.evidence.json"));
    assertThat(config.maxScopeDepth).isEqualTo(32);
  }

  @Test
  void explicitEnrichmentOptionsAreIndependentOfDisplayMetadata() {
    var config =
        AgentConfig.parse(
            "enrichment=true,evidenceOutput=out/evidence.json,"
                + "sourcesFile=out/sources.json,applicationId=com.example:billing,maxScopeDepth=8");
    assertThat(config.enrichment).isTrue();
    assertThat(config.applicationId).isEqualTo("com.example:billing");
    assertThat(config.app).isNull();
    assertThat(config.evidenceOutput).isEqualTo(Path.of("out/evidence.json"));
    assertThat(config.sourcesFile).isEqualTo(Path.of("out/sources.json"));
    assertThat(config.maxScopeDepth).isEqualTo(8);
    assertThat(config.warnings).isEmpty();
  }

  @Test
  void sidecarNamingHandlesDistinctCbomNamesAndRejectsDirectOverwrite() {
    assertThat(AgentConfig.parse("output=out/custom").evidenceOutput)
        .isEqualTo(Path.of("out/custom.evidence.json"));
    assertThat(AgentConfig.parse("output=out/custom.json").evidenceOutput)
        .isEqualTo(Path.of("out/custom.evidence.json"));
    var same = AgentConfig.parse("output=out/custom.json,evidenceOutput=out/./custom.json");
    assertThat(same.evidenceOutput).isEqualTo(Path.of("out/custom.evidence.json"));
    assertThat(same.warnings)
        .containsExactly("evidenceOutput must differ from output; using the default sidecar path");
    assertThat(AgentConfig.parse("output=/").evidenceOutput.getFileName().toString())
        .isEqualTo("cryptocensus-cbom.evidence.json");
  }

  @Test
  void invalidEnrichmentPathsAndBoundsDoNotChangeCoreConfiguration() {
    var config =
        AgentConfig.parse(
            "output=core.json,evidenceOutput=bad\u0000name,"
                + "sourcesFile= ,maxScopeDepth=33,enrichment=invalid");
    assertThat(config.output).isEqualTo(Path.of("core.json"));
    assertThat(config.evidenceOutput).isEqualTo(Path.of("core.evidence.json"));
    assertThat(config.sourcesFile).isNull();
    assertThat(config.maxScopeDepth).isEqualTo(32);
    assertThat(config.enrichment).isFalse();
    assertThat(config.warnings).hasSize(4);
  }
}
