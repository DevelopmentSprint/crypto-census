package com.developmentsprint.cryptocensus.delivery.dependencytrack;

import com.developmentsprint.cryptocensus.delivery.preparation.InputKind;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterContext;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterOptions;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterRequest;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterResult;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterResult.Ingestion;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterResult.Upload;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterSession;
import com.developmentsprint.cryptocensus.delivery.profile.CapabilityDeclaration;
import com.developmentsprint.cryptocensus.delivery.profile.CapabilityDeclaration.ByteRetention;
import com.developmentsprint.cryptocensus.delivery.profile.CapabilityDeclaration.FieldSupport;
import com.developmentsprint.cryptocensus.delivery.profile.CapabilityDeclaration.InventoryMutation;
import com.developmentsprint.cryptocensus.delivery.profile.CapabilityDeclaration.Linkage;
import com.developmentsprint.cryptocensus.delivery.profile.DestinationAdapter;
import com.developmentsprint.cryptocensus.delivery.profile.JsonSupport;
import com.developmentsprint.cryptocensus.delivery.profile.ProtocolResponse;
import com.developmentsprint.cryptocensus.delivery.profile.ProtocolStep;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Explicitly limited Dependency-Track 5.2 runtime view. The engine owns all HTTPS, authentication,
 * deadlines and receipts; this adapter only prepares and interprets bounded native protocol steps.
 */
public final class DependencyTrackAdapter implements DestinationAdapter {
  /** Evidence revision whose entire measured loss set must be explicitly accepted. */
  public static final String CAPABILITY_REVISION = DependencyTrackRules.EVIDENCE_REVISION;

  /** All measured losses; no catch-all loss waiver is supported. */
  public static final Set<String> LOSS_CODES = DependencyTrackRules.LOSSES;

  private static final List<String> ORDERED_LOSSES = LOSS_CODES.stream().sorted().toList();
  private static final Set<InputKind> KINDS =
      Set.of(InputKind.CENSUS_RAW_CBOM, InputKind.CENSUS_MERGED_CBOM);

  @Override
  public String id() {
    return "dependency-track";
  }

  @Override
  public boolean requiresOriginalRetention() {
    return true;
  }

  @Override
  public AdapterOptions parseOptions(Map<String, Object> raw) {
    JsonSupport.fields(raw, "projectUuid", "ownerId", "replacementPolicy");
    UUID project = DependencyTrackRules.uuid(JsonSupport.string(raw, "projectUuid"));
    if (!"OWNED_RUNTIME_SNAPSHOT".equals(JsonSupport.string(raw, "replacementPolicy"))) {
      throw new IllegalArgumentException("DT_REPLACEMENT_POLICY_UNSUPPORTED");
    }
    return new DependencyTrackOptions(
        project,
        JsonSupport.string(raw, "ownerId"),
        DependencyTrackOptions.ReplacementPolicy.OWNED_RUNTIME_SNAPSHOT);
  }

  @Override
  public CapabilityDeclaration capabilities() {
    return new CapabilityDeclaration(
            id(),
            DependencyTrackRules.SERVER_VERSION,
            CAPABILITY_REVISION,
            KINDS,
            Set.of("1.6"),
            LOSS_CODES,
            InventoryMutation.REPLACE_SNAPSHOT,
            true,
            false)
        .withEvidence(
            Map.of(
                "cryptoTypes", FieldSupport.PRESERVED,
                "cryptoProperties", FieldSupport.DROPPED,
                "operationIdentity", FieldSupport.TRANSFORMED,
                "occurrences", FieldSupport.TRANSFORMED,
                "censusMetadata", FieldSupport.DROPPED,
                "signatures", FieldSupport.UNVERIFIED,
                "softwareGraph", FieldSupport.UNVERIFIED),
            ByteRetention.TEMPORARY,
            Linkage.UNVERIFIED);
  }

  @Override
  public void validate(AdapterContext context) {
    if (!(context.options() instanceof DependencyTrackOptions)) {
      throw new IllegalArgumentException("DT_OPTIONS_INVALID");
    }
    if (!KINDS.contains(context.bundle().publication().kind())) {
      throw new IllegalArgumentException("DT_INPUT_KIND_UNSUPPORTED");
    }
    if (!CAPABILITY_REVISION.equals(context.policy().capabilityRevision())
        || !LOSS_CODES.equals(context.policy().acceptedLossCodes())) {
      throw new IllegalArgumentException("DT_MEASURED_LOSS_ACCEPTANCE_REQUIRED");
    }
  }

  @Override
  public AdapterSession open(AdapterContext context, byte[] publicationBytes) {
    validate(context);
    if (context.retention() == null
        || !context.bundle().publication().sha256().equals(context.retention().artifactSha256())
        || publicationBytes.length > 16 * 1024 * 1024
        || !context.bundle().publication().sha256().equals(sha256(publicationBytes))) {
      throw new IllegalArgumentException("DT_VERIFIED_ORIGINAL_RETENTION_REQUIRED");
    }
    return new Session(context, publicationBytes);
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static final class Session implements AdapterSession {
    private static final int MAX_COMPONENT_PAGES = 40;
    private final DependencyTrackOptions options;
    private final String version;
    private final byte[] publication;
    private final Set<UUID> componentIds = new HashSet<>();
    private Phase phase = Phase.VERSION;
    private int page = 1;
    private boolean started;
    private String token;
    private String serverVersion;
    private AdapterResult observation =
        result(Upload.NOT_ATTEMPTED, Ingestion.UNVERIFIED, "DT_PREFLIGHT_PENDING", 0);

    Session(AdapterContext context, byte[] publication) {
      options = (DependencyTrackOptions) context.options();
      version = context.bundle().correlation().version();
      this.publication = publication.clone();
    }

    @Override
    public ProtocolStep first() {
      if (started) {
        throw new IllegalStateException("DT_SESSION_SEQUENCE_INVALID");
      }
      started = true;
      return get("api/version", 0);
    }

    @Override
    public ProtocolStep accept(ProtocolResponse response) {
      if (!started || phase == Phase.FINISHED) {
        throw new IllegalStateException("DT_SESSION_SEQUENCE_INVALID");
      }
      if (response.failureCode() != null || response.statusCode() != 200) {
        return failedResponse(response);
      }
      final Object body;
      try {
        body = JsonSupport.parse(response.body());
      } catch (IllegalArgumentException error) {
        return invalid("DT_RESPONSE_INVALID", response.statusCode());
      }
      try {
        return interpret(body, response.statusCode());
      } catch (IllegalArgumentException error) {
        // Only predefined codes from our own native-schema rules can leave the protocol parser.
        String code = error.getMessage();
        return invalid(
            code != null && code.matches("DT_[A-Z_]{1,90}") ? code : "DT_RESPONSE_INVALID",
            response.statusCode());
      }
    }

    private ProtocolStep interpret(Object body, int status) {
      return switch (phase) {
        case VERSION -> {
          DependencyTrackRules.verifyServer(body);
          serverVersion = DependencyTrackRules.SERVER_VERSION;
          phase = Phase.PROJECT;
          yield get("api/v1/project/" + options.projectUuid(), 0);
        }
        case PROJECT -> {
          DependencyTrackRules.verifyProject(body, options.projectUuid(), version);
          phase = Phase.OWNERSHIP;
          yield get("api/v1/project/" + options.projectUuid() + "/property", 0);
        }
        case OWNERSHIP -> {
          DependencyTrackRules.verifyOwner(body, options.ownerId());
          phase = Phase.COMPONENTS;
          yield componentPage();
        }
        case COMPONENTS -> components(body);
        case SERVICES -> {
          DependencyTrackRules.verifyNoServices(body);
          phase = Phase.UPLOAD;
          yield upload();
        }
        case UPLOAD -> {
          token = DependencyTrackRules.uploadToken(body, options.projectUuid()).toString();
          observation = result(Upload.ACCEPTED, Ingestion.PENDING, "DT_UPLOAD_ACCEPTED", status);
          phase = Phase.STATUS;
          yield poll(0);
        }
        case STATUS -> status(body, status);
        case FINISHED -> throw new IllegalStateException("DT_SESSION_SEQUENCE_INVALID");
      };
    }

    private ProtocolStep components(Object body) {
      List<UUID> ids = DependencyTrackRules.componentPage(body);
      for (UUID componentId : ids) {
        if (!componentIds.add(componentId)) {
          throw new IllegalArgumentException("DT_INVENTORY_PAGINATION_UNVERIFIED");
        }
      }
      if (ids.size() == DependencyTrackRules.PAGE_SIZE) {
        if (++page > MAX_COMPONENT_PAGES) {
          throw new IllegalArgumentException("DT_INVENTORY_LIMIT_EXCEEDED");
        }
        return componentPage();
      }
      phase = Phase.SERVICES;
      return get("api/v1/service/project/" + options.projectUuid() + "?pageNumber=1&pageSize=1", 0);
    }

    private ProtocolStep componentPage() {
      return get(
          "api/v1/component/project/"
              + options.projectUuid()
              + "?pageNumber="
              + page
              + "&pageSize="
              + DependencyTrackRules.PAGE_SIZE
              + "&sortName=uuid&sortOrder=asc",
          0);
    }

    private ProtocolStep upload() {
      String boundary = "census-" + UUID.randomUUID();
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      body.writeBytes(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\"project\"\r\n\r\n"
                  + options.projectUuid()
                  + "\r\n--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\"bom\"; filename=\"cbom.json\""
                  + "\r\nContent-Type: application/vnd.cyclonedx+json\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      body.writeBytes(publication);
      body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
      observation = result(Upload.NOT_ATTEMPTED, Ingestion.UNVERIFIED, "DT_UPLOAD_READY", 0);
      return new ProtocolStep(
          new AdapterRequest(
              "POST",
              "api/v1/bom",
              "multipart/form-data; boundary=" + boundary,
              body.toByteArray(),
              true,
              0),
          observation);
    }

    private ProtocolStep status(Object body, int httpStatus) {
      return switch (DependencyTrackRules.completion(body)) {
        case PENDING -> {
          observation =
              result(Upload.ACCEPTED, Ingestion.PENDING, "DT_INGESTION_PENDING", httpStatus);
          yield poll(500);
        }
        case COMPLETED ->
            finish(
                Upload.ACCEPTED, Ingestion.COMPLETED, "DT_IMPORT_COMPLETED_WITH_LOSS", httpStatus);
        case FAILED -> finish(Upload.ACCEPTED, Ingestion.FAILED, "DT_IMPORT_FAILED", httpStatus);
        case UNVERIFIED ->
            finish(Upload.ACCEPTED, Ingestion.UNVERIFIED, "DT_TOKEN_STATUS_UNVERIFIED", httpStatus);
      };
    }

    private ProtocolStep failedResponse(ProtocolResponse response) {
      int status = response.statusCode();
      if (phase == Phase.UPLOAD) {
        Upload upload =
            response.failureCode() == null && status >= 300 && status < 500
                ? Upload.REJECTED
                : Upload.UNKNOWN;
        return finish(upload, Ingestion.UNVERIFIED, "DT_UPLOAD_UNCONFIRMED", status);
      }
      if (phase == Phase.STATUS) {
        return finish(Upload.ACCEPTED, Ingestion.UNVERIFIED, "DT_STATUS_UNCONFIRMED", status);
      }
      return finish(Upload.NOT_ATTEMPTED, Ingestion.UNVERIFIED, "DT_PREFLIGHT_UNCONFIRMED", status);
    }

    private ProtocolStep invalid(String reason, int status) {
      Upload upload =
          phase == Phase.UPLOAD
              ? Upload.UNKNOWN
              : phase == Phase.STATUS ? Upload.ACCEPTED : Upload.NOT_ATTEMPTED;
      return finish(upload, Ingestion.UNVERIFIED, reason, status);
    }

    private ProtocolStep poll(long delayMillis) {
      return get("api/v1/event/token/" + token, delayMillis);
    }

    private ProtocolStep get(String path, long delayMillis) {
      return new ProtocolStep(
          new AdapterRequest("GET", path, null, new byte[0], false, delayMillis), observation);
    }

    private ProtocolStep finish(Upload upload, Ingestion ingestion, String reason, int status) {
      observation = result(upload, ingestion, reason, status);
      phase = Phase.FINISHED;
      return new ProtocolStep(null, observation);
    }

    private AdapterResult result(Upload upload, Ingestion ingestion, String reason, int status) {
      return new AdapterResult(
          upload, ingestion, reason, status, token, serverVersion, ORDERED_LOSSES);
    }
  }

  private enum Phase {
    VERSION,
    PROJECT,
    OWNERSHIP,
    COMPONENTS,
    SERVICES,
    UPLOAD,
    STATUS,
    FINISHED
  }
}
