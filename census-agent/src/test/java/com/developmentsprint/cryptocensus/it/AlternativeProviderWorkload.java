package com.developmentsprint.cryptocensus.it;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Security;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

/** A real alternate TLS stack remains outside the SunJSSE correlation capability. */
public final class AlternativeProviderWorkload {
  private AlternativeProviderWorkload() {}

  public static void main(String[] args) throws Exception {
    ChildJvmCoverage.dumpAfterAgentShutdown();
    Security.addProvider(new BouncyCastleProvider());
    Security.addProvider(new BouncyCastleJsseProvider());
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
      keys.load(input, args[1].toCharArray());
    }
    KeyManagerFactory managers = KeyManagerFactory.getInstance("PKIX", "BCJSSE");
    managers.init(keys, args[1].toCharArray());
    TrustManagerFactory trust = TrustManagerFactory.getInstance("PKIX", "BCJSSE");
    trust.init(keys);
    SSLContext context = SSLContext.getInstance("TLSv1.3", "BCJSSE");
    context.init(managers.getKeyManagers(), trust.getTrustManagers(), null);
    exchange(context);
    System.err.println("completed-provider=" + context.getProvider().getName());
  }

  @CryptoFlow("alternative-provider")
  private static void exchange(SSLContext context) throws Exception {
    var worker = Executors.newSingleThreadExecutor();
    try (SSLServerSocket listener =
        (SSLServerSocket)
            context
                .getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      listener.setEnabledProtocols(new String[] {"TLSv1.3"});
      listener.setSoTimeout(10_000);
      var server =
          worker.submit(
              () -> {
                try (SSLSocket socket = (SSLSocket) listener.accept()) {
                  socket.setSoTimeout(10_000);
                  socket.startHandshake();
                  if (socket.getInputStream().read() != 7) {
                    throw new IllegalStateException("Missing client data");
                  }
                  socket.getOutputStream().write(9);
                  return socket.getSession().getProtocol();
                }
              });
      try (SSLSocket client =
          (SSLSocket)
              context
                  .getSocketFactory()
                  .createSocket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
        client.setSoTimeout(10_000);
        client.startHandshake();
        client.getOutputStream().write(7);
        if (client.getInputStream().read() != 9
            || !"TLSv1.3".equals(client.getSession().getProtocol())
            || !client.getClass().getName().startsWith("org.bouncycastle.")) {
          throw new IllegalStateException("Alternate TLS exchange did not complete");
        }
      }
      if (!"TLSv1.3".equals(server.get(15, TimeUnit.SECONDS))) {
        throw new IllegalStateException("Alternate server protocol differs");
      }
      marker();
    } finally {
      worker.shutdownNow();
    }
  }

  private static void marker() throws Exception {
    MessageDigest.getInstance("SHA3-256").digest(new byte[] {1});
  }
}
