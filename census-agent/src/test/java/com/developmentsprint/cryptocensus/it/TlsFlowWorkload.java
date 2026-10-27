package com.developmentsprint.cryptocensus.it;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.annotations.CryptoPurpose;
import com.developmentsprint.cryptocensus.annotations.DataProtection;
import java.nio.file.Path;

/** Full-agent workload shares the adapter's real sockets, resumption and delegated engines. */
public final class TlsFlowWorkload {
  private TlsFlowWorkload() {}

  @CryptoFlow(value = "network", purpose = CryptoPurpose.TRANSPORT)
  @DataProtection(confidentialityYears = 20)
  public static void main(String[] args) throws Exception {
    ChildJvmCoverage.dumpAfterAgentShutdown();
    TlsProbeWorkload.run(Path.of(args[0]), args[1]);
  }
}
