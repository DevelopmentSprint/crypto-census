package com.developmentsprint.cryptocensus.delivery;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Shared Maven/CLI configuration: explicit proxy and additive custom CA trust. */
public final class TransportConfiguration {
  private static final int MAX_CA_BYTES = 1024 * 1024;

  private TransportConfiguration() {}

  /**
   * Builds default deadline options. A null CA bundle uses default JVM trust; a null proxy host
   * means direct connections. A CA bundle adds X.509 trust anchors without disabling hostname
   * verification. This overload is unauthenticated; automatic ambient proxy selection is never
   * used.
   */
  public static DeliveryOptions options(Path caBundle, String proxyHost, int proxyPort)
      throws IOException {
    return optionsWithCaBytes(
        caBundle == null ? null : readCaBundle(caBundle), proxyHost, proxyPort);
  }

  /** Explicit client-scoped authenticated CONNECT configuration. */
  public static DeliveryOptions options(
      Path caBundle, String proxyHost, int proxyPort, ProxyCredentials credentials)
      throws IOException {
    return optionsWithCaBytes(
        caBundle == null ? null : readCaBundle(caBundle), proxyHost, proxyPort, credentials);
  }

  /** Uses verified CA bytes and separately resolved proxy credentials. */
  public static DeliveryOptions optionsWithCaBytes(
      byte[] caBundle, String proxyHost, int proxyPort, ProxyCredentials credentials)
      throws IOException {
    DeliveryOptions ordinary = optionsWithCaBytes(caBundle, proxyHost, proxyPort);
    return credentials == null
        ? ordinary
        : new DeliveryOptions(
            ordinary.connectTimeout(),
            ordinary.requestTimeout(),
            ordinary.overallTimeout(),
            ordinary.sslContext(),
            null,
            new AuthenticatedProxy(proxyHost, proxyPort, credentials));
  }

  /** Builds trust from the exact already-verified certificate bytes, without reopening a file. */
  public static DeliveryOptions optionsWithCaBytes(byte[] caBundle, String proxyHost, int proxyPort)
      throws IOException {
    ProxySelector proxy = null;
    if (proxyHost != null) {
      if (!proxyHost.matches("[A-Za-z0-9.:-]{1,253}") || proxyPort < 1 || proxyPort > 65535) {
        throw new IllegalArgumentException("Proxy host or port is invalid");
      }
      proxy = ProxySelector.of(InetSocketAddress.createUnresolved(proxyHost, proxyPort));
    }
    DeliveryOptions defaults = DeliveryOptions.defaults();
    return new DeliveryOptions(
        defaults.connectTimeout(),
        defaults.requestTimeout(),
        defaults.overallTimeout(),
        caBundle == null ? null : trust(caBundle),
        proxy);
  }

  /** Reads a bounded regular CA file once; caller can bind its digest before constructing trust. */
  @SuppressWarnings(
      "PMD.PreserveStackTrace") // Certificate and filesystem errors may include private paths/data.
  public static byte[] readCaBundle(Path bundle) throws IOException {
    try {
      if (!Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Custom CA bundle must be a regular file");
      }
      byte[] bytes;
      try (InputStream input = Files.newInputStream(bundle, LinkOption.NOFOLLOW_LINKS)) {
        bytes = input.readNBytes(MAX_CA_BYTES + 1);
      }
      if (bytes.length == 0 || bytes.length > MAX_CA_BYTES) {
        throw new IOException("Custom CA bundle must be nonempty and at most 1 MiB");
      }
      return bytes;
    } catch (IOException | RuntimeException error) {
      throw invalidCa();
    }
  }

  @SuppressWarnings("PMD.PreserveStackTrace")
  private static SSLContext trust(byte[] source) throws IOException {
    try {
      if (source.length == 0 || source.length > MAX_CA_BYTES) {
        throw new IOException("Custom CA bundle must be nonempty and at most 1 MiB");
      }
      byte[] bytes = source.clone();
      Collection<? extends Certificate> certificates =
          CertificateFactory.getInstance("X.509")
              .generateCertificates(new ByteArrayInputStream(bytes));
      if (certificates.isEmpty()) {
        throw new IOException("Custom CA bundle contains no certificates");
      }
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      TrustManagerFactory defaults =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      defaults.init((KeyStore) null);
      int index = 0;
      for (TrustManager manager : defaults.getTrustManagers()) {
        if (manager instanceof X509TrustManager trustManager) {
          for (X509Certificate certificate : trustManager.getAcceptedIssuers()) {
            store.setCertificateEntry("default-" + index++, certificate);
          }
        }
      }
      for (Certificate certificate : certificates) {
        if (!(certificate instanceof X509Certificate x509) || x509.getBasicConstraints() < 0) {
          throw new IOException("Custom CA bundle must contain CA certificates");
        }
        x509.checkValidity();
        store.setCertificateEntry("custom-" + index++, x509);
      }
      TrustManagerFactory combined =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      combined.init(store);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, combined.getTrustManagers(), null);
      return context;
    } catch (IOException | GeneralSecurityException | RuntimeException error) {
      throw invalidCa();
    }
  }

  private static DeliveryException invalidCa() {
    return new DeliveryException(
        "CA_CONFIGURATION_INVALID",
        "Custom CA bundle could not be loaded; provide valid CA certificates in a regular file at"
            + " most 1 MiB");
  }
}
