package com.developmentsprint.cryptocensus.maven;

import com.developmentsprint.cryptocensus.delivery.DeliveryCredentials;
import com.developmentsprint.cryptocensus.delivery.DeliveryEngine;
import com.developmentsprint.cryptocensus.delivery.DeliveryException;
import com.developmentsprint.cryptocensus.delivery.DeliveryReceipt;
import com.developmentsprint.cryptocensus.delivery.DeliveryRequest;
import com.developmentsprint.cryptocensus.delivery.ReceiptWriteException;
import com.developmentsprint.cryptocensus.delivery.TransportConfiguration;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;

/** Publishes explicit existing evidence independently of test execution and policy enforcement. */
@Mojo(name = "publish", threadSafe = true)
public class PublishMojo extends AbstractMojo {
  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  // Maven 3 component injection keeps compatibility with the supported Maven baseline.
  @SuppressWarnings("deprecation")
  @Component
  private SettingsDecrypter settingsDecrypter;

  /** Existing CBOM. This goal does not generate or choose a report. */
  @Parameter(
      property = "census.delivery.input",
      defaultValue = "${project.build.directory}/cryptocensus/cbom.json")
  private File input;

  /** SHA-256 captured by the producer; verifies the exact supplied evidence. */
  @Parameter(property = "census.delivery.sha256")
  private String sha256;

  /** HTTPS endpoint configured by the operator; credentials/query/fragment are rejected. */
  @Parameter(property = "census.delivery.endpoint")
  private String endpoint;

  /** Nonsecret receiver identity stored in the delivery receipt. */
  @Parameter(property = "census.delivery.destinationId")
  private String destinationId;

  @Parameter(property = "census.delivery.application")
  private String application;

  @Parameter(
      property = "census.delivery.module",
      defaultValue = "${project.groupId}:${project.artifactId}")
  private String module;

  @Parameter(property = "census.delivery.version", defaultValue = "${project.version}")
  private String version;

  /** Caller-supplied source-run identity; not an assertion that the report is fresh. */
  @Parameter(property = "census.delivery.runId")
  private String runId;

  /** Optional explicit receipt file; otherwise a new file is allocated under receiptDirectory. */
  @Parameter(property = "census.delivery.receipt")
  private File receipt;

  @Parameter(
      property = "census.delivery.receiptDirectory",
      defaultValue = "${project.build.directory}/cryptocensus/delivery")
  private File receiptDirectory;

  /** Authentication mode: bearer requires tokenEnv or serverId; none selects anonymous delivery. */
  @Parameter(property = "census.delivery.auth", defaultValue = "bearer")
  private String auth;

  /** Environment variable name, not the credential value. */
  @Parameter(property = "census.delivery.tokenEnv")
  private String tokenEnv;

  /** Maven settings server whose decrypted password contains the bearer token. */
  @Parameter(property = "census.delivery.serverId")
  private String serverId;

  /** Optional local PEM CA bundle augmenting JVM default trust for this client only. */
  @Parameter(property = "census.delivery.caBundle")
  private File caBundle;

  /** Explicit unauthenticated HTTP CONNECT proxy. No ambient proxy is inherited. */
  @Parameter(property = "census.delivery.proxyHost")
  private String proxyHost;

  @Parameter(property = "census.delivery.proxyPort", defaultValue = "8080")
  private int proxyPort;

  /** Fail on a recorded unsuccessful delivery; input/configuration errors always fail. */
  @Parameter(property = "census.delivery.failOnError", defaultValue = "true")
  private boolean failOnError;

  @Override
  // Maven -e prints cause chains, which may expose endpoint credentials or remote response text.
  @SuppressWarnings("PMD.PreserveStackTrace")
  public void execute() throws MojoExecutionException, MojoFailureException {
    DeliveryReceipt result;
    try {
      result = new DeliveryEngine().deliver(request());
    } catch (ConfigurationException e) {
      throw new MojoExecutionException(e.getMessage());
    } catch (ReceiptWriteException e) {
      throw new MojoExecutionException(
          "Could not persist delivery receipt; remote HTTP acceptance: " + e.remoteAccepted());
    } catch (DeliveryException e) {
      throw new MojoExecutionException(e.reasonCode() + ": " + e.getMessage());
    } catch (IllegalArgumentException | IOException e) {
      // Do not retain cause text: URI, credential, settings and transport errors can contain
      // secrets.
      throw new MojoExecutionException(
          "Cannot publish CBOM: the artifact, digest, endpoint, TLS/proxy configuration or receipt"
              + " location is invalid. Check the documented input requirements.");
    }
    String summary =
        "Crypto Census delivery: "
            + result.outcome()
            + " ("
            + result.reasonCode()
            + "), SHA-256 "
            + result.artifactSha256();
    getLog().info(summary);
    getLog().info("Delivery ID: " + result.deliveryId());
    if (!result.successful()) {
      if (failOnError) {
        throw new MojoFailureException(
            "CBOM delivery did not receive a successful HTTP acknowledgment; original evidence and"
                + " receipt are retained");
      }
      getLog()
          .warn(
              "Delivery failed; census.delivery.failOnError=false records this outcome without"
                  + " failing the delivery goal");
    }
  }

  private DeliveryRequest request() throws IOException {
    if (input == null || endpoint == null || (receipt == null && receiptDirectory == null)) {
      throw new ConfigurationException(
          "Configure census.delivery.input, endpoint and a receipt location");
    }
    require(sha256, "sha256");
    require(destinationId, "destinationId");
    require(application, "application");
    require(module, "module");
    require(version, "version");
    require(runId, "runId");
    Path receiptPath =
        receipt != null
            ? receipt.toPath()
            : receiptDirectory.toPath().resolve("delivery-" + UUID.randomUUID() + ".json");
    return new DeliveryRequest(
        input.toPath(),
        receiptPath,
        sha256,
        destinationId,
        URI.create(endpoint),
        application,
        module,
        version,
        runId,
        credentials(),
        TransportConfiguration.options(
            caBundle == null ? null : caBundle.toPath(), proxyHost, proxyPort));
  }

  private DeliveryCredentials credentials() {
    if ("none".equals(auth)) {
      if (tokenEnv != null || serverId != null) {
        throw new ConfigurationException(
            "census.delivery.auth=none cannot specify tokenEnv or serverId");
      }
      return DeliveryCredentials.none();
    }
    if (!"bearer".equals(auth) || (tokenEnv == null) == (serverId == null)) {
      throw new ConfigurationException(
          "Use census.delivery.auth=bearer with exactly one tokenEnv or serverId, or explicitly"
              + " select auth=none");
    }
    String token;
    if (tokenEnv != null) {
      if (!tokenEnv.matches("[A-Za-z_][A-Za-z0-9_]*")) {
        throw new ConfigurationException(
            "census.delivery.tokenEnv must name an environment variable");
      }
      token = environment(tokenEnv);
    } else {
      Server server = session.getSettings().getServer(serverId);
      if (server == null) {
        throw new ConfigurationException(
            "The configured census.delivery.serverId is absent from Maven settings");
      }
      SettingsDecryptionResult decrypted =
          settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
      if (!decrypted.getProblems().isEmpty() || decrypted.getServer() == null) {
        throw new ConfigurationException(
            "Maven could not decrypt the selected delivery server credential");
      }
      token = decrypted.getServer().getPassword();
    }
    if (token == null || token.isBlank()) {
      throw new ConfigurationException("The selected delivery credential is absent or empty");
    }
    return DeliveryCredentials.bearer(token);
  }

  private static void require(String value, String parameter) {
    if (value == null || value.isBlank()) {
      throw new ConfigurationException("Missing census.delivery." + parameter);
    }
  }

  private static final class ConfigurationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    ConfigurationException(String message) {
      super(message);
    }
  }

  String environment(String name) {
    return System.getenv(name);
  }
}
