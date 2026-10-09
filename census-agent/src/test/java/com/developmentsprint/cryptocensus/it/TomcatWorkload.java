package com.developmentsprint.cryptocensus.it;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;

/**
 * An embedded Tomcat serving {@link CryptoServlet} over HTTPS, run in a child JVM by {@link
 * TomcatIT}. It makes one request to itself and stops.
 *
 * <p>Arguments: PKCS#12 keystore, its password, Tomcat base directory.
 */
public final class TomcatWorkload {

  private TomcatWorkload() {}

  public static void main(String[] args) throws Exception {
    ChildJvmCoverage.dumpAfterAgentShutdown();
    final String keystore = args[0];
    final String password = args[1];
    Path baseDir = Files.createDirectories(Path.of(args[2]));

    Tomcat tomcat = new Tomcat();
    tomcat.setBaseDir(baseDir.toString());
    Connector https = new Connector("org.apache.coyote.http11.Http11NioProtocol");
    https.setPort(0);
    https.setSecure(true);
    https.setScheme("https");
    https.setProperty("SSLEnabled", "true");
    SSLHostConfig ssl = new SSLHostConfig();
    ssl.setProtocols("TLSv1.3");
    SSLHostConfigCertificate certificate =
        new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.UNDEFINED);
    certificate.setCertificateKeystoreFile(keystore);
    certificate.setCertificateKeystorePassword(password);
    certificate.setCertificateKeystoreType("PKCS12");
    ssl.addCertificate(certificate);
    https.addSslHostConfig(ssl);
    tomcat.getService().addConnector(https);

    Context context = tomcat.addContext("", null);
    Tomcat.addServlet(context, "crypto", new CryptoServlet());
    context.addServletMapping("/crypto", "crypto");

    tomcat.start();
    try {
      String body = get(https.getLocalPort(), keystore, password);
      if (!body.startsWith("ok ")) {
        throw new IllegalStateException("unexpected response: " + body);
      }
    } finally {
      tomcat.stop();
      tomcat.destroy();
    }
  }

  private static String get(int port, String keystore, String password)
      throws IOException, GeneralSecurityException {
    KeyStore trust = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(Path.of(keystore))) {
      trust.load(in, password.toCharArray());
    }
    TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
    tmf.init(trust);
    SSLContext context = SSLContext.getInstance("TLSv1.3");
    context.init(null, tmf.getTrustManagers(), null);

    HttpsURLConnection connection =
        (HttpsURLConnection)
            URI.create("https://localhost:" + port + "/crypto").toURL().openConnection();
    connection.setSSLSocketFactory(context.getSocketFactory());
    try (InputStream in = connection.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } finally {
      connection.disconnect();
    }
  }

  /** Application code doing cryptography inside a request: the call site TomcatIT expects. */
  public static final class CryptoServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      byte[] digest;
      try {
        digest =
            MessageDigest.getInstance("SHA3-256")
                .digest(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA3-256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA3-256"));
        mac.doFinal(digest);
      } catch (GeneralSecurityException e) {
        throw new IOException(e);
      }
      response.setContentType("text/plain");
      response.getWriter().write("ok " + HexFormat.of().formatHex(digest));
    }
  }
}
