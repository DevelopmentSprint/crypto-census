package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.delivery.DeliveryCredentials;
import com.developmentsprint.cryptocensus.delivery.DeliveryOptions;
import com.developmentsprint.cryptocensus.delivery.DeliveryRequest;
import com.developmentsprint.cryptocensus.delivery.preparation.ArtifactInput;
import com.developmentsprint.cryptocensus.delivery.preparation.Correlation;
import com.developmentsprint.cryptocensus.delivery.preparation.InputKind;
import com.developmentsprint.cryptocensus.delivery.preparation.PreparationRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict profile reader shared by Maven and CLI. */
public final class ProfileReader {
  private final Map<String, DestinationAdapter> adapters;
  private final java.util.function.Consumer<DeliveryProfile> validator;

  /** Uses explicitly registered adapters only. */
  public ProfileReader(List<DestinationAdapter> adapters) {
    this(adapters, profile -> {});
  }

  /** Applies an entrypoint's run-binding checks to every freshly decoded profile. */
  public ProfileReader(
      List<DestinationAdapter> adapters, java.util.function.Consumer<DeliveryProfile> validator) {
    this.validator = java.util.Objects.requireNonNull(validator);
    Map<String, DestinationAdapter> registry = new java.util.LinkedHashMap<>();
    for (DestinationAdapter adapter : adapters) {
      if (registry.put(adapter.id(), adapter) != null) {
        throw new IllegalArgumentException("Duplicate adapter registration");
      }
    }
    this.adapters = Map.copyOf(registry);
  }

  /** Decodes bounded JSON and rejects unknown fields before local preparation or delivery. */
  public DeliveryProfile read(Path source) throws IOException {
    byte[] bytes = ProfileFiles.read(source, 65536);
    Map<String, Object> root = JsonSupport.object(JsonSupport.parse(bytes));
    JsonSupport.fields(
        root,
        "schema",
        "adapter",
        "endpoint",
        "destinationId",
        "preparation",
        "inputs",
        "options",
        "authentication",
        "transport",
        "policy",
        "retention",
        "outputs");
    if (!"census.delivery-profile/1".equals(JsonSupport.string(root, "schema"))) {
      throw new IllegalArgumentException("Unsupported profile schema");
    }
    DestinationAdapter adapter = adapters.get(JsonSupport.string(root, "adapter"));
    if (adapter == null) {
      throw new IllegalArgumentException("Unsupported delivery adapter");
    }
    final URI endpoint = endpoint(JsonSupport.string(root, "endpoint"));
    String destination = JsonSupport.string(root, "destinationId");
    if (!destination.matches("[A-Za-z0-9._-]{1,128}")) {
      throw new IllegalArgumentException("Invalid destination ID");
    }
    Path directory = source.toAbsolutePath().getParent();
    Map<String, Object> prep = JsonSupport.object(root.get("preparation"));
    JsonSupport.fields(prep, "mode", "outputDirectory");
    if (!(root.get("inputs") instanceof List<?> inputs) || inputs.isEmpty() || inputs.size() > 2) {
      throw new IllegalArgumentException("Profile requires one or two inputs");
    }
    List<ArtifactInput> artifacts = new ArrayList<>();
    for (Object item : inputs) {
      Map<String, Object> input = JsonSupport.object(item);
      JsonSupport.fields(input, "path", "sha256", "kind", "correlation");
      Map<String, Object> mapping = JsonSupport.object(input.get("correlation"));
      JsonSupport.fields(mapping, "application", "module", "version", "runId");
      artifacts.add(
          new ArtifactInput(
              path(directory, input, "path"),
              JsonSupport.string(input, "sha256"),
              InputKind.valueOf(JsonSupport.string(input, "kind")),
              new Correlation(
                  JsonSupport.string(mapping, "application"),
                  JsonSupport.string(mapping, "module"),
                  JsonSupport.string(mapping, "version"),
                  JsonSupport.string(mapping, "runId"))));
    }
    final PreparationRequest preparation =
        new PreparationRequest(
            PreparationRequest.Mode.valueOf(JsonSupport.string(prep, "mode")),
            artifacts,
            path(directory, prep, "outputDirectory"));
    Map<String, Object> policy = JsonSupport.object(root.get("policy"));
    JsonSupport.fields(
        policy,
        "capabilityRevision",
        "acceptedLossCodes",
        "completion",
        "requiredFields",
        "requireRemoteRetention");
    final DeliveryPolicy gate =
        new DeliveryPolicy(
            JsonSupport.string(policy, "capabilityRevision"),
            strings(policy.get("acceptedLossCodes")),
            DeliveryPolicy.Completion.valueOf(JsonSupport.string(policy, "completion")),
            policy.containsKey("requiredFields")
                ? fieldNames(policy.get("requiredFields"))
                : Set.of(),
            booleanValue(policy, "requireRemoteRetention"));
    Map<String, Object> outputs = JsonSupport.object(root.get("outputs"));
    JsonSupport.fields(outputs, "plan", "receipt");
    Map<String, Object> transport =
        root.containsKey("transport") ? JsonSupport.object(root.get("transport")) : Map.of();
    JsonSupport.fields(
        transport, "caBundle", "proxyHost", "proxyPort", "proxyUsername", "proxyPassword");
    boolean proxyAuth =
        transport.containsKey("proxyUsername") || transport.containsKey("proxyPassword");
    SecretReference proxyUsername = null;
    SecretReference proxyPassword = null;
    if (proxyAuth) {
      if (!transport.containsKey("proxyHost")
          || !transport.containsKey("proxyUsername")
          || !transport.containsKey("proxyPassword")) {
        throw new IllegalArgumentException(
            "Proxy authentication requires host and both references");
      }
      proxyUsername = SecretReference.parse(transport.get("proxyUsername"));
      proxyPassword = SecretReference.parse(transport.get("proxyPassword"));
      if (proxyUsername.kind() != SecretReference.Kind.ENV
          || proxyPassword.kind() != SecretReference.Kind.ENV) {
        throw new IllegalArgumentException(
            "Profile proxy credentials require environment references");
      }
    }
    String proxy =
        transport.containsKey("proxyHost") ? JsonSupport.string(transport, "proxyHost") : null;
    if (proxy != null && !proxy.matches("[A-Za-z0-9.:-]{1,253}")) {
      throw new IllegalArgumentException("Invalid explicit proxy host");
    }
    if (proxy == null && transport.containsKey("proxyPort")) {
      throw new IllegalArgumentException("Proxy port requires proxy host");
    }
    int port = 8080;
    if (transport.containsKey("proxyPort")) {
      Object value = transport.get("proxyPort");
      if (!(value instanceof Long number) || number < 1 || number > 65535) {
        throw new IllegalArgumentException("Invalid proxy port");
      }
      port = number.intValue();
    }
    DeliveryProfile.Retention retention = null;
    if (root.containsKey("retention")) {
      Map<String, Object> value = JsonSupport.object(root.get("retention"));
      JsonSupport.fields(value, "endpoint", "authentication");
      retention =
          new DeliveryProfile.Retention(
              endpoint(JsonSupport.string(value, "endpoint")),
              SecretReference.parse(value.get("authentication")));
    }
    DeliveryProfile decoded =
        new DeliveryProfile(
            source.toAbsolutePath(),
            ProfileFiles.hash(bytes),
            destination,
            adapter,
            endpoint,
            adapter.parseOptions(
                root.containsKey("options") ? JsonSupport.object(root.get("options")) : Map.of()),
            preparation,
            SecretReference.parse(root.get("authentication")),
            new DeliveryProfile.Transport(
                transport.containsKey("caBundle") ? path(directory, transport, "caBundle") : null,
                proxy,
                port,
                proxyUsername,
                proxyPassword),
            gate,
            retention,
            path(directory, outputs, "plan"),
            path(directory, outputs, "receipt"));
    validator.accept(decoded);
    return decoded;
  }

  private static boolean booleanValue(Map<String, Object> values, String name) {
    if (!values.containsKey(name)) {
      return false;
    }
    if (!(values.get(name) instanceof Boolean value)) {
      throw new IllegalArgumentException("Invalid boolean policy");
    }
    return value;
  }

  private static Set<String> fieldNames(Object value) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("Expected required field names");
    }
    Set<String> result = new java.util.HashSet<>();
    for (Object item : list) {
      if (!(item instanceof String field)
          || !field.matches("[a-zA-Z]{1,64}")
          || !result.add(field)) {
        throw new IllegalArgumentException("Invalid semantic field name");
      }
    }
    return Set.copyOf(result);
  }

  private static Set<String> strings(Object value) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("Expected a loss-code array");
    }
    Set<String> result = new java.util.HashSet<>();
    for (Object item : list) {
      if (!(item instanceof String code) || !code.matches("[A-Z0-9_]{1,96}") || !result.add(code)) {
        throw new IllegalArgumentException("Invalid or duplicate loss code");
      }
    }
    return Set.copyOf(result);
  }

  private static Path path(Path base, Map<String, Object> value, String name) {
    return base.resolve(JsonSupport.string(value, name)).normalize();
  }

  static URI endpoint(String value) {
    final URI endpoint = URI.create(value);
    new DeliveryRequest(
        Path.of("input"),
        Path.of("receipt"),
        "0".repeat(64),
        "profile",
        endpoint,
        "app",
        "module",
        "version",
        "run",
        DeliveryCredentials.none(),
        DeliveryOptions.defaults());
    return endpoint;
  }
}
