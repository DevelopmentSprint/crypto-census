package com.developmentsprint.cryptocensus.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Exercises the shaded production collector; reflection respects its private core namespace. */
public final class TlsProductionWorkload {
  private static final String AGENT = "com.developmentsprint.cryptocensus.agent.";

  private TlsProductionWorkload() {}

  public static void main(String[] args) throws Exception {
    TlsProbeWorkload.run(Path.of(args[0]), args[1]);
    Class<?> collector = Class.forName(AGENT + "TlsCollector", true, null);
    if (Boolean.getBoolean("census.tls.fixtureGc")) {
      collectAbandonedEngine();
      collector.getMethod("snapshot").invoke(null);
    }
    collector.getMethod("close").invoke(null);
    List<?> handshakes = (List<?>) collector.getMethod("snapshot").invoke(null);
    List<String> rows = new ArrayList<>();
    for (Object handshake : handshakes) {
      Object negotiation = call(handshake, "negotiation");
      Object resumption = call(handshake, "resumption");
      rows.add(
          String.join(
              "\t",
              value(handshake, "outcome"),
              value(handshake, "connectionId"),
              value(handshake, "attempt"),
              value(handshake, "transport"),
              value(handshake, "role"),
              value(negotiation, "protocol"),
              value(negotiation, "selectedGroupId"),
              value(resumption, "resumed"),
              value(negotiation, "localSignatureScheme"),
              value(negotiation, "peerSignatureScheme"),
              Boolean.toString(call(resumption, "pskOrigin") != null),
              value(call(handshake, "initiation"), "identityId"),
              value(handshake, "diagnostics")));
    }
    List<?> capabilities =
        (List<?>)
            Class.forName(AGENT + "TlsInstrumentation", true, null)
                .getMethod("capabilities")
                .invoke(null);
    for (Object capability : capabilities) {
      rows.add("capability\t" + value(capability, "feature") + "\t" + value(capability, "status"));
    }
    Files.write(Path.of(args[2]), rows);
  }

  private static void collectAbandonedEngine() throws Exception {
    java.lang.ref.ReferenceQueue<Object> queue = new java.lang.ref.ReferenceQueue<>();
    java.lang.ref.WeakReference<Object> abandoned = abandonedEngine(queue);
    System.gc();
    if (!abandoned.equals(queue.remove(5000))) {
      throw new AssertionError("TLS collector retained an abandoned engine");
    }
  }

  private static java.lang.ref.WeakReference<Object> abandonedEngine(
      java.lang.ref.ReferenceQueue<Object> queue) throws Exception {
    javax.net.ssl.SSLContext context = javax.net.ssl.SSLContext.getInstance("TLSv1.3");
    context.init(null, null, null);
    javax.net.ssl.SSLEngine engine = context.createSSLEngine();
    engine.setUseClientMode(true);
    engine.beginHandshake();
    return new java.lang.ref.WeakReference<>(engine, queue);
  }

  private static String value(Object record, String accessor) throws ReflectiveOperationException {
    return String.valueOf(call(record, accessor));
  }

  private static Object call(Object record, String accessor) throws ReflectiveOperationException {
    return record.getClass().getMethod(accessor).invoke(record);
  }
}
