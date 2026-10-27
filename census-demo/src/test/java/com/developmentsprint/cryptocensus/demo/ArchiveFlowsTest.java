package com.developmentsprint.cryptocensus.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ArchiveFlowsTest {
  @Test
  void sharedHelperExecutesInDistinctBusinessScopesAndWithoutScope() throws Exception {
    ArchiveFlows flows = new ArchiveFlows();
    byte[] syntheticKey = "synthetic-key".getBytes(StandardCharsets.UTF_8);
    assertThat(flows.archiveKey(syntheticKey)).hasSize(256);
    assertThat(flows.exportKey(syntheticKey)).hasSize(256);
    assertThat(flows.protectWithoutContext(syntheticKey)).hasSize(256);
    var worker = Executors.newSingleThreadExecutor();
    try {
      assertThat(flows.archiveLater(syntheticKey, worker).get(10, TimeUnit.SECONDS)).hasSize(256);
    } finally {
      worker.shutdownNow();
    }
  }
}
