package com.developmentsprint.cryptocensus.maven;

import com.developmentsprint.cryptocensus.delivery.DeliveryException;
import com.developmentsprint.cryptocensus.delivery.ReceiptWriteException;
import com.developmentsprint.cryptocensus.delivery.profile.ProfileCommand;
import com.developmentsprint.cryptocensus.delivery.profile.SecretReference;
import java.io.File;
import java.io.IOException;
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

/**
 * Plans, preflights or executes a versioned profile using the standalone command's shared engine.
 */
@Mojo(name = "delivery", threadSafe = true)
public class DeliveryMojo extends AbstractMojo {
  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @SuppressWarnings("deprecation")
  @Component
  private SettingsDecrypter settingsDecrypter;

  @Parameter(property = "census.delivery.profile", required = true)
  private File profile;

  @Parameter(property = "census.delivery.action", defaultValue = "plan")
  private String action;

  @Parameter(property = "census.delivery.planSha256")
  private String planSha256;

  @Parameter(property = "census.delivery.failOnError", defaultValue = "true")
  private boolean failOnError;

  @Override
  @SuppressWarnings("PMD.PreserveStackTrace") // Maven -e must not expose URI/auth/remote causes.
  public void execute() throws MojoExecutionException, MojoFailureException {
    ProfileCommand.Result result;
    try {
      if (profile == null) {
        throw new ConfigurationException("Missing census.delivery.profile");
      }
      if (action == null) {
        throw new ConfigurationException("Missing census.delivery.action");
      }
      if (!"plan".equals(action) && (planSha256 == null || planSha256.isBlank())) {
        throw new ConfigurationException(
            "Missing census.delivery.planSha256 from the planning step");
      }
      result = ProfileCommand.run(action, profile.toPath(), planSha256, this::resolve);
    } catch (ConfigurationException error) {
      throw new MojoExecutionException(error.getMessage());
    } catch (ReceiptWriteException error) {
      throw new MojoExecutionException(
          "Receipt persistence failed; remote acceptance=" + error.remoteAccepted());
    } catch (DeliveryException error) {
      throw new MojoExecutionException(error.reasonCode() + ": " + error.getMessage());
    } catch (IOException | RuntimeException error) {
      throw new MojoExecutionException(
          "PROFILE_INVALID: Check profile schema, artifact/plan hashes, capability/loss policy and"
              + " output permissions.");
    }
    getLog().info("Delivery plan SHA-256: " + result.planSha256());
    if (result.receipt() != null) {
      getLog()
          .info(
              "Delivery: upload="
                  + result.receipt().observation().upload()
                  + ", ingestion="
                  + result.receipt().observation().ingestion()
                  + " ("
                  + result.receipt().observation().reasonCode()
                  + ")");
    }
    if (!result.successful()) {
      if (failOnError) {
        throw new MojoFailureException(
            "Delivery profile requirements were not met; inspect the retained staged receipt");
      }
      getLog()
          .warn(
              "Delivery requirements were not met; census.delivery.failOnError=false retains this"
                  + " outcome");
    }
  }

  private String resolve(SecretReference reference) {
    if (reference.kind() == SecretReference.Kind.ENV) {
      String token = environment(reference.name());
      if (token == null || token.isEmpty()) {
        throw new ConfigurationException(
            "Configured authentication environment variable is absent or empty");
      }
      return token;
    }
    if (reference.kind() != SecretReference.Kind.MAVEN_SERVER
        || session == null
        || settingsDecrypter == null) {
      throw new ConfigurationException("Maven server credential resolution is unavailable");
    }
    Server server = session.getSettings().getServer(reference.name());
    if (server == null) {
      throw new ConfigurationException("Configured Maven settings server is missing");
    }
    var decrypted = settingsDecrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
    if (decrypted == null
        || !decrypted.getProblems().isEmpty()
        || decrypted.getServer() == null
        || decrypted.getServer().getPassword() == null
        || decrypted.getServer().getPassword().isEmpty()) {
      throw new ConfigurationException(
          "Maven server credential decryption failed or password is empty");
    }
    return decrypted.getServer().getPassword();
  }

  String environment(String name) {
    return System.getenv(name);
  }

  private static final class ConfigurationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    ConfigurationException(String message) {
      super(message);
    }
  }
}
