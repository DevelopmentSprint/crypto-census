package com.developmentsprint.cryptocensus.cli;

import com.developmentsprint.cryptocensus.delivery.DeliveryCredentials;
import com.developmentsprint.cryptocensus.delivery.DeliveryEngine;
import com.developmentsprint.cryptocensus.delivery.DeliveryException;
import com.developmentsprint.cryptocensus.delivery.DeliveryReceipt;
import com.developmentsprint.cryptocensus.delivery.DeliveryRequest;
import com.developmentsprint.cryptocensus.delivery.ProxyCredentials;
import com.developmentsprint.cryptocensus.delivery.ReceiptWriteException;
import com.developmentsprint.cryptocensus.delivery.TransportConfiguration;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Publishes an existing CBOM without launching or instrumenting the observed application. */
public final class CensusCli {
  private static final Set<String> OPTIONS =
      Set.of(
          "input",
          "sha256",
          "receipt",
          "endpoint",
          "destination-id",
          "application",
          "module",
          "version",
          "run-id",
          "token-env",
          "auth",
          "ca-bundle",
          "proxy-host",
          "proxy-port",
          "proxy-user-env",
          "proxy-password-env");

  private CensusCli() {}

  /** Runs the command with process-local credentials and a documented exit code. */
  public static void main(String[] args) {
    System.exit(run(args, System.getenv(), System.out, System.err));
  }

  static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
    if (args.length == 1 && "--help".equals(args[0])) {
      help(out);
      return 0;
    }
    if (args.length > 0 && "delivery".equals(args[0])) {
      return ProfileCli.run(args, environment, out, err);
    }
    try {
      Map<String, String> values = arguments(args);
      DeliveryRequest request = request(values, environment);
      DeliveryReceipt result = new DeliveryEngine().deliver(request);
      out.println("Crypto Census delivery: " + result.outcome() + " (" + result.reasonCode() + ")");
      out.println("Artifact SHA-256: " + result.artifactSha256());
      out.println("Delivery ID: " + result.deliveryId());
      if (result.successful() || values.containsKey("report-only")) {
        return 0;
      }
      return "REJECTED".equals(result.outcome().name()) ? 3 : 4;
    } catch (ConfigurationException e) {
      err.println(e.getMessage());
      err.println("Use --help for the required publish options.");
      return 2;
    } catch (ReceiptWriteException e) {
      err.println(
          "Could not persist delivery receipt; remote HTTP acceptance: " + e.remoteAccepted());
      return 5;
    } catch (DeliveryException e) {
      err.println(e.reasonCode() + ": " + e.getMessage());
      return 2;
    } catch (IllegalArgumentException | IOException e) {
      // Neither malformed command values nor transport exceptions may expose credentials or URLs.
      err.println(
          "Delivery could not complete. Check input/digest, configuration and receipt"
              + " permissions.");
      err.println(
          "Remote acceptance may be unknown; inspect any receipt before manually retrying.");
      return 2;
    }
  }

  private static DeliveryRequest request(
      Map<String, String> values, Map<String, String> environment) throws IOException {
    String proxy = values.get("proxy-host");
    int port = Integer.parseInt(values.getOrDefault("proxy-port", "8080"));
    if (proxy == null && values.containsKey("proxy-port")) {
      throw new ConfigurationException("--proxy-port requires --proxy-host");
    }
    return new DeliveryRequest(
        Path.of(required(values, "input")),
        Path.of(required(values, "receipt")),
        required(values, "sha256"),
        required(values, "destination-id"),
        URI.create(required(values, "endpoint")),
        required(values, "application"),
        required(values, "module"),
        required(values, "version"),
        required(values, "run-id"),
        credentials(values, environment),
        TransportConfiguration.options(
            values.containsKey("ca-bundle") ? Path.of(values.get("ca-bundle")) : null,
            proxy,
            port,
            proxyCredentials(values, environment)));
  }

  private static ProxyCredentials proxyCredentials(
      Map<String, String> values, Map<String, String> environment) {
    if (!values.containsKey("proxy-user-env") && !values.containsKey("proxy-password-env")) {
      return null;
    }
    if (!values.containsKey("proxy-host")) {
      throw new ConfigurationException("Proxy credential references require --proxy-host");
    }
    String user = required(values, "proxy-user-env");
    String password = required(values, "proxy-password-env");
    if (!user.matches("[A-Za-z_][A-Za-z0-9_]*") || !password.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new ConfigurationException("Invalid proxy environment reference");
    }
    return ProxyCredentials.basic(environment.get(user), environment.get(password));
  }

  private static DeliveryCredentials credentials(
      Map<String, String> values, Map<String, String> environment) {
    String auth = values.getOrDefault("auth", "bearer");
    if ("none".equals(auth)) {
      if (values.containsKey("token-env")) {
        throw new ConfigurationException("--auth none cannot be combined with --token-env");
      }
      return DeliveryCredentials.none();
    }
    if (!"bearer".equals(auth)) {
      throw new ConfigurationException("--auth must be bearer or none");
    }
    String reference = required(values, "token-env");
    if (!reference.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      throw new ConfigurationException("--token-env must name an environment variable");
    }
    String token = environment.get(reference);
    if (token == null || token.isBlank()) {
      throw new ConfigurationException(
          "The environment variable selected by --token-env is absent or empty");
    }
    return DeliveryCredentials.bearer(token);
  }

  private static Map<String, String> arguments(String[] args) {
    if (args.length == 0 || !"publish".equals(args[0])) {
      throw new ConfigurationException("Expected the publish command");
    }
    Map<String, String> values = new HashMap<>();
    for (int i = 1; i < args.length; i++) {
      if (!args[i].startsWith("--")) {
        throw new ConfigurationException("Expected a named --option");
      }
      String name = args[i].substring(2);
      if (values.containsKey(name)) {
        throw new ConfigurationException("Each option may be supplied only once");
      }
      if ("report-only".equals(name)) {
        values.put(name, "true");
      } else if (OPTIONS.contains(name) && i + 1 < args.length) {
        values.put(name, args[++i]);
      } else {
        throw new ConfigurationException("Unknown option or missing option value");
      }
    }
    return values;
  }

  private static String required(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null || value.isBlank()) {
      throw new ConfigurationException("Missing required --" + name);
    }
    return value;
  }

  private static final class ConfigurationException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    ConfigurationException(String message) {
      super(message);
    }
  }

  private static void help(PrintStream out) {
    out.println("Crypto Census: publish an existing runtime CBOM over HTTPS");
    out.println("Profiles: delivery --action plan --profile delivery.json");
    out.println(
        "  delivery --action preflight|publish --profile delivery.json --plan-sha256 EXPECTED");
    out.println(
        "Profile planning is offline; preflight is authenticated read-only; publish may replace"
            + " configured inventory.");
    out.println(
        "Usage: java -jar census-cli.jar publish --input cbom.json --sha256 EXPECTED_SHA256");
    out.println("  --receipt delivery.json --endpoint https://inventory.example/ingest");
    out.println("  --destination-id inventory --application payments --module example:payments");
    out.println("  --version 1.0 --run-id CURRENT_RUN_ID --token-env CENSUS_UPLOAD_TOKEN");
    out.println("Options: --auth none (explicit anonymous), --ca-bundle roots.pem,");
    out.println("  --proxy-host HOST --proxy-port PORT, --report-only");
    out.println("  --proxy-user-env NAME --proxy-password-env NAME (explicit CONNECT credentials)");
    out.println("Exactly one attempt; HTTPS only; no redirects; 60-second operation deadline.");
    out.println("HTTP_ACCEPTED means HTTP acknowledgment, not verified inventory ingestion.");
    out.println(
        "Run identity is caller supplied; the publisher does not establish evidence freshness.");
    out.println("Publish exit: 0 acknowledged/report-only, 2 configuration/evidence error,");
    out.println("  3 rejected, 4 unknown or failed delivery, 5 receipt failure.");
    out.println("Profile exit: 0 gate passed/report-only, 2 invalid input or credentials,");
    out.println("  4 delivery gate failed, 5 receipt failure.");
  }
}
