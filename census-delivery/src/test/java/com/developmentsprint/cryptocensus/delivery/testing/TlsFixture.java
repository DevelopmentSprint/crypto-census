package com.developmentsprint.cryptocensus.delivery.testing;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Test-only ephemeral certificate with a localhost SAN. No private keys in the repository. */
public final class TlsFixture {
  private final SSLContext serverContext;
  private final Path caBundle;

  /** Generates a temporary server identity and its PEM trust anchor in the supplied directory. */
  public TlsFixture(Path directory) throws Exception {
    Path storeFile = directory.resolve("server.p12");
    String password = "ephemeral-test-only";
    String executable =
        System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
    Path keytool = Path.of(System.getProperty("java.home"), "bin", executable);
    Process process =
        new ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias",
                "localhost",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-ext",
                "bc=ca:true",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-storetype",
                "PKCS12",
                "-keystore",
                storeFile.toString(),
                "-storepass",
                password,
                "-noprompt")
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("keytool.log").toFile())
            .start();
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Test certificate generation timed out");
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException("Test certificate generation failed");
    }
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(storeFile)) {
      store.load(input, password.toCharArray());
    }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, password.toCharArray());
    serverContext = SSLContext.getInstance("TLS");
    serverContext.init(keys.getKeyManagers(), null, null);
    caBundle = directory.resolve("ca.pem");
    Files.writeString(
        caBundle,
        "-----BEGIN CERTIFICATE-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'})
                .encodeToString(store.getCertificate("localhost").getEncoded())
            + "\n-----END CERTIFICATE-----\n",
        StandardCharsets.US_ASCII);
  }

  /** Returns the public CA bundle; callers retain responsibility for its temporary directory. */
  public Path caBundle() {
    return caBundle;
  }

  /** Creates an unstarted server on an ephemeral loopback port. */
  public HttpsServer server() throws Exception {
    HttpsServer server =
        HttpsServer.create(
            new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
    return server;
  }
}
