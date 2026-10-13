package com.developmentsprint.cryptocensus.cli;

import com.developmentsprint.cryptocensus.delivery.DeliveryException;
import com.developmentsprint.cryptocensus.delivery.ReceiptWriteException;
import com.developmentsprint.cryptocensus.delivery.profile.ProfileCommand;
import com.developmentsprint.cryptocensus.delivery.profile.SecretReference;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Thin profile command: all planning, validation and protocol behavior belongs to shared delivery.
 */
final class ProfileCli {
  private ProfileCli() {}

  static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
    try {
      Map<String, String> options = parse(args);
      String action = required(options, "action");
      String profile = required(options, "profile");
      if (!"plan".equals(action)) {
        required(options, "plan-sha256");
      }
      ProfileCommand.Result result =
          ProfileCommand.run(
              action,
              Path.of(profile),
              options.get("plan-sha256"),
              reference -> resolve(reference, environment));
      out.println("Delivery plan SHA-256: " + result.planSha256());
      if (result.receipt() != null) {
        out.println(
            "Delivery: upload="
                + result.receipt().observation().upload()
                + ", ingestion="
                + result.receipt().observation().ingestion()
                + " ("
                + result.receipt().observation().reasonCode()
                + ")");
      }
      return result.successful() || options.containsKey("report-only") ? 0 : 4;
    } catch (UsageException error) {
      err.println(error.getMessage());
      return 2;
    } catch (ReceiptWriteException error) {
      err.println("Receipt persistence failed; remote acceptance=" + error.remoteAccepted());
      return 5;
    } catch (DeliveryException error) {
      err.println(error.reasonCode() + ": " + error.getMessage());
      return 2;
    } catch (RuntimeException | IOException error) {
      err.println(
          "PROFILE_INVALID: Check profile schema, artifact/plan hashes, capability/loss policy and"
              + " output permissions.");
      return 2;
    }
  }

  private static String resolve(SecretReference reference, Map<String, String> environment) {
    if (reference.kind() != SecretReference.Kind.ENV
        && reference.kind() != SecretReference.Kind.ENV_BASIC) {
      throw new UsageException(
          "Standalone delivery requires an ENV secret reference; Maven server references are"
              + " available through the Maven goal");
    }
    String value = environment.get(reference.name());
    if (value == null || value.isEmpty()) {
      throw new UsageException("Configured authentication environment variable is absent or empty");
    }
    return reference.basic()
        ? SecretReference.encodeBasic(environment.get(reference.usernameName()), value)
        : value;
  }

  private static Map<String, String> parse(String[] args) {
    Map<String, String> result = new HashMap<>();
    for (int i = 1; i < args.length; i++) {
      String key = args[i];
      if (!key.startsWith("--")) {
        throw new UsageException("Expected a named delivery option");
      }
      key = key.substring(2);
      if (result.containsKey(key)) {
        throw new UsageException("Delivery options may be supplied only once");
      }
      if ("report-only".equals(key)) {
        result.put(key, "true");
      } else if (Set.of("action", "profile", "plan-sha256").contains(key) && i + 1 < args.length) {
        result.put(key, args[++i]);
      } else {
        throw new UsageException("Unknown delivery option or missing value");
      }
    }
    return result;
  }

  private static String required(Map<String, String> options, String name) {
    String value = options.get(name);
    if (value == null || value.isBlank()) {
      throw new UsageException("Missing required --" + name);
    }
    return value;
  }

  private static final class UsageException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    UsageException(String message) {
      super(message);
    }
  }
}
