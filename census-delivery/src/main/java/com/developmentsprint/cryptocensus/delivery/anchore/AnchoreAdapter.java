package com.developmentsprint.cryptocensus.delivery.anchore;

import com.developmentsprint.cryptocensus.delivery.preparation.InputKind;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterContext;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterOptions;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterRequest;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterResult;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterResult.Ingestion;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterResult.Upload;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterSession;
import com.developmentsprint.cryptocensus.delivery.profile.CapabilityDeclaration;
import com.developmentsprint.cryptocensus.delivery.profile.CapabilityDeclaration.FieldSupport;
import com.developmentsprint.cryptocensus.delivery.profile.DestinationAdapter;
import com.developmentsprint.cryptocensus.delivery.profile.JsonSupport;
import com.developmentsprint.cryptocensus.delivery.profile.ProtocolResponse;
import com.developmentsprint.cryptocensus.delivery.profile.ProtocolStep;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Experimental native API 2.19.0 adapter; no live semantic fidelity claims. */
public final class AnchoreAdapter implements DestinationAdapter {
  /** Pinned official OpenAPI contract, separately from commercial tenant validation. */
  public static final String CAPABILITY_REVISION = "anchore-enterprise-api-2.19.0/2026-10-12";

  /** Explicit acceptance of each unverified behavior is required. */
  public static final Set<String> LOSS_CODES =
      Set.of(
          "ANCHORE_CRYPTO_SEMANTICS_UNVERIFIED", "ANCHORE_ORIGINAL_RETENTION_UNVERIFIED",
          "ANCHORE_INVENTORY_MUTATION_UNVERIFIED", "ANCHORE_LIVE_CONTRACT_UNVERIFIED");

  private static final List<String> ORDERED_LOSSES = LOSS_CODES.stream().sorted().toList();
  private static final Set<InputKind> KINDS =
      Set.of(InputKind.CENSUS_RAW_CBOM, InputKind.CENSUS_MERGED_CBOM);

  @Override
  public String id() {
    return "anchore-enterprise";
  }

  @Override
  public boolean requiresAuthentication() {
    return true;
  }

  @Override
  public boolean requiresOriginalRetention() {
    return true;
  }

  @Override
  public AdapterOptions parseOptions(Map<String, Object> raw) {
    JsonSupport.fields(
        raw, "applicationUuid", "versionUuid", "assetName", "serverVersion", "mutationPolicy");
    if (!"DEDICATED_VERSION_SINGLE_WRITER".equals(JsonSupport.string(raw, "mutationPolicy"))) {
      throw new IllegalArgumentException("ANCHORE_MUTATION_POLICY_REQUIRED");
    }
    return new AnchoreOptions(
        uuid(JsonSupport.string(raw, "applicationUuid")),
        uuid(JsonSupport.string(raw, "versionUuid")),
        JsonSupport.string(raw, "assetName"),
        JsonSupport.string(raw, "serverVersion"),
        AnchoreOptions.MutationPolicy.DEDICATED_VERSION_SINGLE_WRITER);
  }

  @Override
  public CapabilityDeclaration capabilities() {
    return new CapabilityDeclaration(
            id(),
            "UNVERIFIED (API 2.19.0)",
            CAPABILITY_REVISION,
            KINDS,
            Set.of("1.6"),
            LOSS_CODES,
            CapabilityDeclaration.InventoryMutation.UNVERIFIED,
            true,
            false)
        .withEvidence(
            Map.of(
                "cryptoTypes",
                FieldSupport.UNVERIFIED,
                "cryptoProperties",
                FieldSupport.UNVERIFIED,
                "operationIdentity",
                FieldSupport.UNVERIFIED,
                "occurrences",
                FieldSupport.UNVERIFIED,
                "censusMetadata",
                FieldSupport.UNVERIFIED,
                "softwareGraph",
                FieldSupport.UNVERIFIED,
                "signatures",
                FieldSupport.UNVERIFIED),
            CapabilityDeclaration.ByteRetention.UNVERIFIED,
            CapabilityDeclaration.Linkage.UNVERIFIED);
  }

  @Override
  public void validate(AdapterContext context) {
    if (!(context.options() instanceof AnchoreOptions)) {
      throw new IllegalArgumentException("ANCHORE_OPTIONS_INVALID");
    }
    if (!KINDS.contains(context.bundle().publication().kind())) {
      throw new IllegalArgumentException("ANCHORE_INPUT_KIND_UNSUPPORTED");
    }
    if (!CAPABILITY_REVISION.equals(context.policy().capabilityRevision())
        || !LOSS_CODES.equals(context.policy().acceptedLossCodes())) {
      throw new IllegalArgumentException("ANCHORE_UNVERIFIED_CAPABILITIES_ACCEPTANCE_REQUIRED");
    }
    if (context.policy().completion()
        != com.developmentsprint.cryptocensus.delivery.profile.DeliveryPolicy.Completion
            .INGESTION_COMPLETED) {
      throw new IllegalArgumentException("ANCHORE_INGESTION_COMPLETION_REQUIRED");
    }
  }

  @Override
  public AdapterSession open(AdapterContext context, byte[] publicationBytes) {
    validate(context);
    if (context.retention() == null
        || !context.bundle().publication().sha256().equals(context.retention().artifactSha256())
        || publicationBytes.length > 16 * 1024 * 1024
        || !context.bundle().publication().sha256().equals(sha256(publicationBytes))) {
      throw new IllegalArgumentException("ANCHORE_VERIFIED_ORIGINAL_RETENTION_REQUIRED");
    }
    return new Session(context, publicationBytes);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  private static UUID uuid(Object value) {
    if (!(value instanceof String text)
        || !text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
      throw new IllegalArgumentException("ANCHORE_RESPONSE_INVALID");
    }
    return UUID.fromString(text);
  }

  private static Map<?, ?> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("ANCHORE_RESPONSE_INVALID");
    }
    return map;
  }

  private static void require(boolean condition, String code) {
    if (!condition) {
      throw new IllegalArgumentException(code);
    }
  }

  private static UUID identity(Map<?, ?> value) {
    return uuid(object(value.get("system_metadata")).get("id"));
  }

  private static final class Session implements AdapterSession {
    private final AnchoreOptions options;
    private final String version;
    private final byte[] publication;
    private Phase phase = Phase.SERVER;
    private boolean started;
    private String job;
    private String serverVersion;
    private String asset;
    private AdapterResult observation =
        result(Upload.NOT_ATTEMPTED, Ingestion.UNVERIFIED, "ANCHORE_PREFLIGHT_PENDING", 0);

    Session(AdapterContext context, byte[] publication) {
      options = (AnchoreOptions) context.options();
      version = context.bundle().correlation().version();
      this.publication = publication.clone();
    }

    @Override
    public ProtocolStep first() {
      if (started) {
        throw new IllegalStateException("ANCHORE_SESSION_SEQUENCE_INVALID");
      }
      started = true;
      return get("v2/version", 0);
    }

    @Override
    public ProtocolStep accept(ProtocolResponse response) {
      if (!started || phase == Phase.FINISHED) {
        throw new IllegalStateException("ANCHORE_SESSION_SEQUENCE_INVALID");
      }
      int expected = phase == Phase.UPLOAD ? 201 : 200;
      if (response.failureCode() != null || response.statusCode() != expected) {
        Upload upload =
            phase == Phase.UPLOAD
                ? response.failureCode() == null
                        && response.statusCode() >= 300
                        && response.statusCode() < 500
                    ? Upload.REJECTED
                    : Upload.UNKNOWN
                : afterUpload() ? Upload.ACCEPTED : Upload.NOT_ATTEMPTED;
        return finish(
            upload, Ingestion.UNVERIFIED, "ANCHORE_RESPONSE_UNCONFIRMED", response.statusCode());
      }
      try {
        return interpret(object(JsonSupport.parse(response.body())), response.statusCode());
      } catch (IllegalArgumentException error) {
        String code = error.getMessage();
        return finish(
            phase == Phase.UPLOAD
                ? Upload.UNKNOWN
                : afterUpload() ? Upload.ACCEPTED : Upload.NOT_ATTEMPTED,
            Ingestion.UNVERIFIED,
            code != null && code.matches("ANCHORE_[A-Z_]{1,90}")
                ? code
                : "ANCHORE_RESPONSE_INVALID",
            response.statusCode());
      }
    }

    private boolean afterUpload() {
      return phase == Phase.JOB || phase == Phase.ASSET;
    }

    private String appPath() {
      return "v2/apps/" + options.applicationUuid();
    }

    private String versionPath() {
      return appPath() + "/versions/" + options.versionUuid();
    }

    private ProtocolStep interpret(Map<?, ?> body, int status) {
      return switch (phase) {
        case SERVER -> {
          require(
              "2.19.0".equals(object(body.get("api")).get("version"))
                  && options.serverVersion().equals(object(body.get("service")).get("version")),
              "ANCHORE_SERVER_UNVERIFIED");
          serverVersion = options.serverVersion();
          phase = Phase.APP;
          yield get(appPath(), 0);
        }
        case APP -> {
          require(
              options.applicationUuid().equals(identity(body)),
              "ANCHORE_APPLICATION_MAPPING_UNVERIFIED");
          phase = Phase.VERSION;
          yield get(versionPath(), 0);
        }
        case VERSION -> {
          require(
              options.versionUuid().equals(identity(body))
                  && version.equals(body.get("name"))
                  && "in_progress".equals(object(body.get("status")).get("current_value")),
              "ANCHORE_VERSION_MAPPING_UNVERIFIED");
          phase = Phase.COLLISION;
          yield get(versionPath() + "/assets?name=" + options.assetName() + "&limit=1", 0);
        }
        case COLLISION -> {
          Map<?, ?> pagination = object(body.get("pagination"));
          require(
              body.get("items") instanceof List<?> items
                  && items.isEmpty()
                  && Long.valueOf(0).equals(pagination.get("item_count"))
                  && pagination.get("next_cursor") == null,
              "ANCHORE_ASSET_NAME_COLLISION_OR_UNVERIFIED");
          phase = Phase.UPLOAD;
          yield upload();
        }
        case UPLOAD, JOB -> job(body, status);
        case ASSET -> {
          require(
              uuid(asset).equals(identity(body))
                  && options.assetName().equals(body.get("name"))
                  && "application".equals(body.get("type")),
              "ANCHORE_ASSET_MAPPING_UNVERIFIED");
          yield finish(
              Upload.ACCEPTED,
              Ingestion.COMPLETED,
              "ANCHORE_JOB_COMPLETED_FIDELITY_UNVERIFIED",
              status);
        }
        case FINISHED -> throw new IllegalStateException("ANCHORE_SESSION_SEQUENCE_INVALID");
      };
    }

    private ProtocolStep job(Map<?, ?> body, int status) {
      String id = identity(body).toString();
      require(job == null || job.equals(id), "ANCHORE_JOB_MAPPING_UNVERIFIED");
      require("add-sbom-asset".equals(body.get("job_type")), "ANCHORE_JOB_MAPPING_UNVERIFIED");
      Map<?, ?> spec = object(body.get("job_spec"));
      require(
          spec.get("app_versions") instanceof List<?> versions
              && versions.size() == 1
              && options.versionUuid().equals(uuid(object(versions.get(0)).get("app_version_id"))),
          "ANCHORE_JOB_MAPPING_UNVERIFIED");
      Map<?, ?> requestedAsset = object(spec.get("asset"));
      require(
          options.assetName().equals(requestedAsset.get("name"))
              && "application".equals(requestedAsset.get("type")),
          "ANCHORE_JOB_MAPPING_UNVERIFIED");
      job = id;
      phase = Phase.JOB;
      Object state = body.get("status");
      if ("failed".equals(state) || "cancelled".equals(state)) {
        return finish(Upload.ACCEPTED, Ingestion.FAILED, "ANCHORE_JOB_FAILED", status);
      }
      require(body.get("error") == null, "ANCHORE_JOB_STATUS_UNVERIFIED");
      if ("complete".equals(state)) {
        require(
            body.get("completed_at") instanceof String timestamp && validTimestamp(timestamp),
            "ANCHORE_JOB_STATUS_UNVERIFIED");
        asset = uuid(object(body.get("completion_detail")).get("asset_id")).toString();
        observation =
            result(
                Upload.ACCEPTED, Ingestion.PENDING, "ANCHORE_ASSET_VERIFICATION_PENDING", status);
        phase = Phase.ASSET;
        return get(versionPath() + "/assets/" + asset, 0);
      }
      require(
          ("pending".equals(state) || "processing".equals(state))
              && body.get("completed_at") == null,
          "ANCHORE_JOB_STATUS_UNVERIFIED");
      observation = result(Upload.ACCEPTED, Ingestion.PENDING, "ANCHORE_JOB_PENDING", status);
      return get(appPath() + "/jobs/add-sbom-asset/" + job, status == 201 ? 0 : 500);
    }

    private static boolean validTimestamp(String value) {
      try {
        java.time.Instant.parse(value);
        return true;
      } catch (java.time.format.DateTimeParseException error) {
        return false;
      }
    }

    private ProtocolStep upload() {
      String boundary = "census-" + UUID.randomUUID();
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      part(body, boundary, "asset_name", options.assetName());
      part(body, boundary, "asset_type", "application");
      part(body, boundary, "app_version_id", options.versionUuid().toString());
      body.writeBytes(
          ("--"
                  + boundary
                  + "\r\n"
                  + "Content-Disposition: form-data; name=\"sbom\"; filename=\"cbom.json\"\r\n"
                  + "Content-Type: application/vnd.cyclonedx+json\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      body.writeBytes(publication);
      body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
      return new ProtocolStep(
          new AdapterRequest(
              "POST",
              appPath() + "/jobs/add-sbom-asset",
              "multipart/form-data; boundary=" + boundary,
              body.toByteArray(),
              true,
              0),
          observation);
    }

    private static void part(
        ByteArrayOutputStream body, String boundary, String name, String value) {
      body.writeBytes(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\""
                  + name
                  + "\"\r\n\r\n"
                  + value
                  + "\r\n")
              .getBytes(StandardCharsets.US_ASCII));
    }

    private ProtocolStep get(String path, long delay) {
      return new ProtocolStep(
          new AdapterRequest("GET", path, null, new byte[0], false, delay), observation);
    }

    private ProtocolStep finish(Upload upload, Ingestion ingestion, String code, int status) {
      observation = result(upload, ingestion, code, status);
      phase = Phase.FINISHED;
      return new ProtocolStep(null, observation);
    }

    private AdapterResult result(Upload upload, Ingestion ingestion, String code, int status) {
      return new AdapterResult(upload, ingestion, code, status, job, serverVersion, ORDERED_LOSSES);
    }
  }

  private enum Phase {
    SERVER,
    APP,
    VERSION,
    COLLISION,
    UPLOAD,
    JOB,
    ASSET,
    FINISHED
  }
}
