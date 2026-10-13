package com.developmentsprint.cryptocensus.delivery.sonatype;

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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Experimental documented SBOM Manager import contract, without live fidelity certification. */
public final class SonatypeSbomManagerAdapter implements DestinationAdapter {
  /** Explicit operator acceptance of a documentation-backed, contract-tested revision. */
  public static final String CAPABILITY_REVISION =
      "sonatype-sbom-manager/docs-2026-10-12-experimental-1";

  /** No native semantic preservation or atomic non-overwrite guarantee has been measured. */
  public static final Set<String> LOSS_CODES =
      Set.of("SONATYPE_SEMANTICS_UNVERIFIED", "SONATYPE_MUTATION_UNVERIFIED");

  private static final List<String> LOSSES = LOSS_CODES.stream().sorted().toList();
  private static final Set<InputKind> KINDS =
      Set.of(
          InputKind.CENSUS_RAW_CBOM,
          InputKind.CENSUS_MERGED_CBOM,
          InputKind.EXTERNAL_SOFTWARE_SBOM);

  @Override
  public String id() {
    return "sonatype-sbom-manager";
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
  public boolean requiresPublicationReadback() {
    return true;
  }

  @Override
  public AdapterOptions parseOptions(Map<String, Object> raw) {
    JsonSupport.fields(raw, "applicationId", "publicId", "importPolicy");
    return new SonatypeOptions(
        JsonSupport.string(raw, "applicationId"),
        JsonSupport.string(raw, "publicId"),
        JsonSupport.string(raw, "importPolicy"));
  }

  @Override
  public CapabilityDeclaration capabilities() {
    return new CapabilityDeclaration(
            id(),
            "UNVERIFIED",
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
                "signatures",
                FieldSupport.UNVERIFIED,
                "softwareGraph",
                FieldSupport.UNVERIFIED),
            CapabilityDeclaration.ByteRetention.UNVERIFIED,
            CapabilityDeclaration.Linkage.UNVERIFIED);
  }

  @Override
  public void validate(AdapterContext context) {
    if (!(context.options() instanceof SonatypeOptions)
        || !SonatypeOptions.safeSegment(context.bundle().correlation().version())) {
      throw new IllegalArgumentException("SONATYPE_MAPPING_INVALID");
    }
    if (!KINDS.contains(context.bundle().publication().kind())) {
      throw new IllegalArgumentException("SONATYPE_INPUT_KIND_UNSUPPORTED");
    }
    if (!CAPABILITY_REVISION.equals(context.policy().capabilityRevision())
        || !context.policy().acceptedLossCodes().equals(LOSS_CODES)) {
      throw new IllegalArgumentException("SONATYPE_UNVERIFIED_FIDELITY_ACCEPTANCE_REQUIRED");
    }
  }

  @Override
  public AdapterSession open(AdapterContext context, byte[] publicationBytes) {
    validate(context);
    if (context.retention() == null
        || publicationBytes.length > 16 * 1024 * 1024
        || !context.bundle().publication().sha256().equals(context.retention().artifactSha256())
        || !context.bundle().publication().sha256().equals(hash(publicationBytes))) {
      throw new IllegalArgumentException("SONATYPE_VERIFIED_ORIGINAL_REQUIRED");
    }
    return new Session(context, publicationBytes);
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  private static final class Session implements AdapterSession {
    private final SonatypeOptions options;
    private final String version;
    private final byte[] publication;
    private final Set<String> versions = new HashSet<>();
    private Phase phase = Phase.APPLICATION;
    private boolean started;
    private int page = 1;
    private int polls;
    private String statusPath;
    private long total = -1;
    private String operation;
    private AdapterResult result =
        result(Upload.NOT_ATTEMPTED, Ingestion.UNVERIFIED, "SONATYPE_PREFLIGHT_PENDING", 0);

    Session(AdapterContext context, byte[] publication) {
      options = (SonatypeOptions) context.options();
      version = context.bundle().correlation().version();
      this.publication = publication.clone();
    }

    @Override
    public ProtocolStep first() {
      if (started) {
        throw new IllegalStateException("SONATYPE_SESSION_SEQUENCE_INVALID");
      }
      started = true;
      return get("api/v2/applications?publicId=" + options.publicId());
    }

    @Override
    public ProtocolStep accept(ProtocolResponse response) {
      if (!started || phase == Phase.FINISHED) {
        throw new IllegalStateException("SONATYPE_SESSION_SEQUENCE_INVALID");
      }
      if (phase == Phase.STATUS
          && response.failureCode() == null
          && (response.statusCode() == 202 || response.statusCode() == 204)) {
        if (++polls >= 16) {
          return finish(
              Upload.ACCEPTED, Ingestion.PENDING, "SONATYPE_STATUS_LIMIT", response.statusCode());
        }
        return new ProtocolStep(
            new AdapterRequest("GET", statusPath, null, new byte[0], false, 500), result);
      }
      if (phase == Phase.READBACK
          && response.failureCode() == null
          && response.statusCode() == 200) {
        return finish(
            Upload.ACCEPTED,
            MessageDigest.isEqual(publication, response.body())
                ? Ingestion.COMPLETED
                : Ingestion.UNVERIFIED,
            MessageDigest.isEqual(publication, response.body())
                ? "SONATYPE_IMPORT_COMPLETED_UNVERIFIED_FIDELITY"
                : "SONATYPE_ORIGINAL_BYTES_DIFFER",
            200);
      }
      if (response.failureCode() != null
          || (response.statusCode() != 200
              && !(phase == Phase.UPLOAD
                  && (response.statusCode() == 201 || response.statusCode() == 202)))) {
        Upload upload =
            phase == Phase.UPLOAD
                ? response.failureCode() == null
                        && response.statusCode() >= 300
                        && response.statusCode() < 500
                    ? Upload.REJECTED
                    : Upload.UNKNOWN
                : (phase == Phase.STATUS || phase == Phase.READBACK)
                    ? Upload.ACCEPTED
                    : Upload.NOT_ATTEMPTED;
        return finish(
            upload, Ingestion.UNVERIFIED, "SONATYPE_RESPONSE_UNCONFIRMED", response.statusCode());
      }
      try {
        Map<String, Object> body = JsonSupport.object(JsonSupport.parse(response.body()));
        return switch (phase) {
          case APPLICATION -> application(body);
          case VERSIONS -> versions(body);
          case UPLOAD -> uploaded(body, response.statusCode());
          case STATUS -> completed(body);
          case READBACK, FINISHED ->
              throw new IllegalStateException("SONATYPE_SESSION_SEQUENCE_INVALID");
        };
      } catch (IllegalArgumentException error) {
        return finish(
            phase == Phase.UPLOAD
                ? Upload.UNKNOWN
                : (phase == Phase.STATUS || phase == Phase.READBACK)
                    ? Upload.ACCEPTED
                    : Upload.NOT_ATTEMPTED,
            Ingestion.UNVERIFIED,
            "SONATYPE_RESPONSE_INVALID",
            response.statusCode());
      }
    }

    private ProtocolStep application(Map<String, Object> body) {
      if (!(body.get("applications") instanceof List<?> applications) || applications.size() != 1) {
        throw new IllegalArgumentException("Invalid application mapping");
      }
      Map<String, Object> app = JsonSupport.object(applications.get(0));
      if (!options.applicationId().equals(app.get("id"))
          || !options.publicId().equals(app.get("publicId"))) {
        throw new IllegalArgumentException("Invalid application mapping");
      }
      phase = Phase.VERSIONS;
      return versionsPage();
    }

    private ProtocolStep versions(Map<String, Object> body) {
      if (!(body.get("totalResultsCount") instanceof Long count)
          || count < 0
          || count > 1000
          || !(body.get("results") instanceof List<?> results)
          || results.size() > 25
          || (total >= 0 && total != count)) {
        throw new IllegalArgumentException("Unverified version listing");
      }
      total = count;
      for (Object value : results) {
        String found = JsonSupport.string(JsonSupport.object(value), "applicationVersion");
        if (!versions.add(found)) {
          throw new IllegalArgumentException("Unstable version listing");
        }
        if (version.equals(found)) {
          return finish(Upload.NOT_ATTEMPTED, Ingestion.UNVERIFIED, "SONATYPE_VERSION_EXISTS", 200);
        }
      }
      if (versions.size() > total || (versions.size() < total && results.size() != 25)) {
        throw new IllegalArgumentException("Incomplete version listing");
      }
      if (versions.size() < total) {
        page++;
        return versionsPage();
      }
      phase = Phase.UPLOAD;
      return upload();
    }

    private ProtocolStep versionsPage() {
      return get(
          "api/v2/sbom/applications/"
              + options.applicationId()
              + "?page="
              + page
              + "&pageSize=25&sortByDate=asc");
    }

    private ProtocolStep upload() {
      String boundary = "census-" + UUID.randomUUID();
      ByteArrayOutputStream body = new ByteArrayOutputStream();
      for (Map.Entry<String, String> field :
          Map.of("applicationId", options.applicationId(), "applicationVersion", version)
              .entrySet()) {
        body.writeBytes(
            ("--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\""
                    + field.getKey()
                    + "\"\r\n\r\n"
                    + field.getValue()
                    + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
      }
      body.writeBytes(
          ("--"
                  + boundary
                  + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cbom.json\""
                  + "\r\nContent-Type: application/vnd.cyclonedx+json\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      body.writeBytes(publication);
      body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
      return new ProtocolStep(
          new AdapterRequest(
              "POST",
              "api/v2/sbom/import?ignoreValidationError=false",
              "multipart/form-data; boundary=" + boundary,
              body.toByteArray(),
              true,
              0),
          result);
    }

    private ProtocolStep uploaded(Map<String, Object> body, int status) {
      String path = JsonSupport.string(body, "statusUrl");
      String prefix = "api/v2/sbom/applications/" + options.applicationId() + "/status/";
      if (!path.startsWith(prefix) || !path.substring(prefix.length()).matches("[a-f0-9]{32}")) {
        throw new IllegalArgumentException("Invalid status path");
      }
      operation = path.substring(prefix.length());
      statusPath = path;
      result = result(Upload.ACCEPTED, Ingestion.PENDING, "SONATYPE_UPLOAD_ACCEPTED", status);
      phase = Phase.STATUS;
      return get(path);
    }

    private ProtocolStep completed(Map<String, Object> body) {
      if (Boolean.TRUE.equals(body.get("isError"))) {
        return finish(Upload.ACCEPTED, Ingestion.FAILED, "SONATYPE_IMPORT_FAILED", 200);
      }
      if (!Boolean.FALSE.equals(body.get("isError"))
          || !options.applicationId().equals(body.get("applicationId"))
          || !version.equals(body.get("version"))
          || body.get("errorMessage") != null
          || !("api/v2/sbom/" + options.applicationId() + "/version/" + version + "?form=original")
              .equals(body.get("downloadUrl"))) {
        throw new IllegalArgumentException("Unverified import result");
      }
      phase = Phase.READBACK;
      return new ProtocolStep(
          new AdapterRequest(
                  "GET", JsonSupport.string(body, "downloadUrl"), null, new byte[0], false, 0)
              .withPublicationReadback(),
          result);
    }

    private ProtocolStep get(String path) {
      return new ProtocolStep(new AdapterRequest("GET", path, null, new byte[0], false, 0), result);
    }

    private ProtocolStep finish(Upload upload, Ingestion ingestion, String code, int status) {
      result = result(upload, ingestion, code, status);
      phase = Phase.FINISHED;
      return new ProtocolStep(null, result);
    }

    private AdapterResult result(Upload upload, Ingestion ingestion, String code, int status) {
      return new AdapterResult(upload, ingestion, code, status, operation, null, LOSSES);
    }
  }

  private enum Phase {
    APPLICATION,
    VERSIONS,
    UPLOAD,
    STATUS,
    READBACK,
    FINISHED
  }
}
