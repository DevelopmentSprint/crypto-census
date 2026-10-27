package com.developmentsprint.cryptocensus.it;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.annotations.DataProtection;
import java.nio.file.Path;
import javax.net.ssl.SSLContext;

/** A discovered invalid annotation must not erase independently completed TLS evidence. */
public final class AnnotationTlsWorkload {
  private AnnotationTlsWorkload() {}

  @CryptoFlow("network")
  @DataProtection(classification = "restricted", confidentialityYears = 20)
  public static void main(String[] args) throws Exception {
    ChildJvmCoverage.dumpAfterAgentShutdown();
    Class.forName(BadDeclaration.class.getName());
    var contextMethod =
        TlsProbeWorkload.class.getDeclaredMethod("context", Path.class, String.class);
    contextMethod.setAccessible(true);
    SSLContext context = (SSLContext) contextMethod.invoke(null, Path.of(args[0]), args[1]);
    var connection =
        TlsProbeWorkload.class.getDeclaredMethod(
            "socketConnections", SSLContext.class, SSLContext.class, int.class, boolean.class);
    connection.setAccessible(true);
    connection.invoke(null, context, context, 1, false);
  }

  public static final class BadDeclaration {
    private BadDeclaration() {}

    @CryptoFlow("Export")
    public static void invalid() {}
  }
}
