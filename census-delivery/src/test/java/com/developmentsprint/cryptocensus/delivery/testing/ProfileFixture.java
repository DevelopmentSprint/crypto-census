package com.developmentsprint.cryptocensus.delivery.testing;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Primitive;
import com.developmentsprint.cryptocensus.delivery.profile.JsonSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public executable profile fixture shared with Maven and CLI integration tests. */
public final class ProfileFixture {
  private ProfileFixture() {}

  /**
   * Writes a real agent-schema CBOM and returns mutable profile configuration for negative cases.
   */
  public static Map<String, Object> configuration(Path directory, String endpoint, Path caBundle)
      throws Exception {
    final Path artifact = directory.resolve("cbom.json");
    Inventory inventory = new Inventory();
    inventory.record(
        CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, "SUN"),
        new Occurrence("example.Workload", "digest", 1, null));
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    for (String health : Cbom.HEALTH_PROPERTIES) {
      properties.put(health, "0");
    }
    Cbom.write(inventory, new Cbom.Metadata("module", properties), artifact);
    Map<String, Object> mapping =
        Map.of(
            "application", "app", "module", "example:module", "version", "1.0", "runId", "run-1");
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("schema", "census.delivery-profile/1");
    config.put("adapter", "generic-https");
    config.put("endpoint", endpoint);
    config.put("destinationId", "inventory");
    config.put(
        "preparation",
        Map.of("mode", "IDENTITY", "outputDirectory", directory.resolve("prepared").toString()));
    config.put(
        "inputs",
        List.of(
            Map.of(
                "path",
                artifact.toString(),
                "sha256",
                hash(Files.readAllBytes(artifact)),
                "kind",
                "CENSUS_RAW_CBOM",
                "correlation",
                mapping)));
    config.put("authentication", Map.of("type", "ENV", "name", "CENSUS_TEST_TOKEN"));
    config.put("options", Map.of("headers", Map.of("X-Correlation-Id", "build-1")));
    config.put(
        "policy",
        Map.of(
            "capabilityRevision",
            "generic-https/1",
            "acceptedLossCodes",
            List.of(),
            "completion",
            "UPLOAD_ACCEPTED"));
    config.put(
        "outputs",
        Map.of(
            "plan",
            directory.resolve("plan.json").toString(),
            "receipt",
            directory.resolve("receipt.json").toString()));
    if (caBundle != null) {
      config.put("transport", Map.of("caBundle", caBundle.toString()));
    }
    return config;
  }

  /** Writes a profile using the same bounded JSON codec as the product. */
  public static Path write(Path directory, Map<String, Object> config) throws Exception {
    Path profile = directory.resolve("delivery.json");
    Files.write(profile, JsonSupport.write(config));
    return profile;
  }

  /** Computes expected exact evidence identity. */
  public static String hash(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
