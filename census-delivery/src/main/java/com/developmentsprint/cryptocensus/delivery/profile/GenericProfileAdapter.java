package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.delivery.preparation.InputKind;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact-byte receiver contract without claims of inventory ingestion or remote retention. */
public final class GenericProfileAdapter implements DestinationAdapter {
  /** Typed nonsecret extension metadata with a finite header allowlist. */
  public record Options(
      Map<String, String> headers, boolean receiverSupportsIdempotency, int maxAttempts)
      implements AdapterOptions {
    /** Copies validated extension headers. */
    public Options {
      headers = Map.copyOf(headers);
      if (maxAttempts < 1
          || maxAttempts > 3
          || (!receiverSupportsIdempotency && maxAttempts != 1)) {
        throw new IllegalArgumentException(
            "Retries require receiver idempotency and at most three attempts");
      }
    }

    /** Default one-attempt receiver behavior. */
    public Options(Map<String, String> headers) {
      this(headers, false, 1);
    }

    @Override
    public Map<String, Object> sanitized() {
      return Map.of(
          "receiverSupportsIdempotency",
          receiverSupportsIdempotency,
          "maxAttempts",
          maxAttempts,
          "idempotencyDeclaration",
          receiverSupportsIdempotency ? "OPERATOR_ASSERTED" : "NOT_DECLARED");
    }

    @Override
    public AuthenticationScheme authenticationScheme() {
      return AuthenticationScheme.BEARER;
    }
  }

  @Override
  public String id() {
    return "generic-https";
  }

  @Override
  public AdapterOptions parseOptions(Map<String, Object> raw) {
    JsonSupport.fields(raw, "headers", "receiverSupportsIdempotency", "maxAttempts");
    Map<String, Object> values =
        raw.containsKey("headers") ? JsonSupport.object(raw.get("headers")) : Map.of();
    JsonSupport.fields(values, "X-Correlation-Id", "X-Tenant-Id");
    Map<String, String> headers = new java.util.LinkedHashMap<>();
    for (String name : values.keySet()) {
      String value = JsonSupport.string(values, name);
      if (!value.matches("[\\x21-\\x7e]{1,256}")) {
        throw new IllegalArgumentException("Extension header value is invalid");
      }
      headers.put(name, value);
    }
    Object declaration = raw.getOrDefault("receiverSupportsIdempotency", false);
    Object attempts = raw.getOrDefault("maxAttempts", 1L);
    if (!(declaration instanceof Boolean enabled)
        || !(attempts instanceof Long count)
        || count < 1
        || count > 3) {
      throw new IllegalArgumentException("Invalid receiver retry configuration");
    }
    return new Options(headers, enabled, count.intValue());
  }

  @Override
  public CapabilityDeclaration capabilities() {
    return new CapabilityDeclaration(
        id(),
        "receiver-defined",
        "generic-https/1",
        Set.of(InputKind.values()),
        Set.of("1.6"),
        Set.of(),
        CapabilityDeclaration.InventoryMutation.ARTIFACT_ONLY,
        false,
        false);
  }

  @Override
  public void validate(AdapterContext context) {
    if (!(context.options() instanceof Options)) {
      throw new IllegalArgumentException("Generic adapter requires typed options");
    }
    if (context.policy().completion() != DeliveryPolicy.Completion.UPLOAD_ACCEPTED) {
      throw new IllegalArgumentException("Generic receiver cannot verify ingestion");
    }
  }

  @Override
  public AdapterSession open(AdapterContext context, byte[] publicationBytes) {
    validate(context);
    Options options = (Options) context.options();
    return new Session(options, publicationBytes);
  }

  private static final class Session implements AdapterSession {
    private final Options options;
    private final byte[] bytes;

    Session(Options options, byte[] bytes) {
      this.options = options;
      this.bytes = bytes.clone();
    }

    @Override
    public ProtocolStep first() {
      return new ProtocolStep(
          new AdapterRequest(
              "POST", "", "application/vnd.cyclonedx+json", bytes, true, 0, options.headers()),
          result(AdapterResult.Upload.NOT_ATTEMPTED, "PREPARED", 0));
    }

    @Override
    public ProtocolStep accept(ProtocolResponse response) {
      int status = response.statusCode();
      AdapterResult.Upload upload =
          status >= 200 && status < 300
              ? AdapterResult.Upload.ACCEPTED
              : status >= 300 && status < 500
                  ? AdapterResult.Upload.REJECTED
                  : AdapterResult.Upload.UNKNOWN;
      String reason =
          response.failureCode() != null
              ? response.failureCode()
              : upload == AdapterResult.Upload.ACCEPTED
                  ? "HTTP_ACCEPTED"
                  : upload == AdapterResult.Upload.REJECTED ? "HTTP_REJECTED" : "HTTP_UNKNOWN";
      return new ProtocolStep(null, result(upload, reason, status));
    }

    private AdapterResult result(AdapterResult.Upload upload, String reason, int status) {
      return new AdapterResult(
          upload,
          AdapterResult.Ingestion.NOT_APPLICABLE,
          reason,
          status,
          null,
          "receiver-defined",
          List.of());
    }
  }
}
