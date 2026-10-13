package com.developmentsprint.cryptocensus.delivery.artifactory;

import com.developmentsprint.cryptocensus.delivery.preparation.InputKind;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterContext;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterOptions;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterRequest;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterResult;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterResult.Ingestion;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterResult.Upload;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterSession;
import com.developmentsprint.cryptocensus.delivery.profile.CapabilityDeclaration;
import com.developmentsprint.cryptocensus.delivery.profile.DeliveryPolicy;
import com.developmentsprint.cryptocensus.delivery.profile.DestinationAdapter;
import com.developmentsprint.cryptocensus.delivery.profile.JsonSupport;
import com.developmentsprint.cryptocensus.delivery.profile.ProtocolResponse;
import com.developmentsprint.cryptocensus.delivery.profile.ProtocolStep;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Experimental raw artifact storage; it does not submit a BOM to Xray for analysis. */
public final class ArtifactoryAdapter implements DestinationAdapter {
  /** Documented protocol plus local HTTPS contract evidence, without commercial certification. */
  public static final String CAPABILITY_REVISION = "artifactory/1-contract";

  @Override
  public String id() {
    return "artifactory";
  }

  @Override
  public boolean requiresAuthentication() {
    return true;
  }

  @Override
  public boolean requiresPublicationReadback() {
    return true;
  }

  @Override
  public AdapterOptions parseOptions(Map<String, Object> raw) {
    JsonSupport.fields(raw, "repository", "pathPrefix", "deploymentPermission");
    return new ArtifactoryOptions(
        JsonSupport.string(raw, "repository"),
        JsonSupport.string(raw, "pathPrefix"),
        JsonSupport.string(raw, "deploymentPermission"));
  }

  @Override
  public CapabilityDeclaration capabilities() {
    return new CapabilityDeclaration(
        id(),
        "UNVERIFIED",
        CAPABILITY_REVISION,
        Set.of(InputKind.values()),
        Set.of("1.6"),
        Set.of(),
        CapabilityDeclaration.InventoryMutation.ARTIFACT_ONLY,
        false,
        false);
  }

  @Override
  public void validate(AdapterContext context) {
    if (!(context.options() instanceof ArtifactoryOptions)
        || context.policy().completion() != DeliveryPolicy.Completion.UPLOAD_ACCEPTED) {
      throw new IllegalArgumentException("ARTIFACTORY_STORAGE_POLICY_REQUIRED");
    }
  }

  @Override
  public AdapterSession open(AdapterContext context, byte[] publicationBytes) {
    validate(context);
    if (publicationBytes.length > 16 * 1024 * 1024
        || !context.bundle().publication().sha256().equals(hash(publicationBytes))) {
      throw new IllegalArgumentException("ARTIFACTORY_PUBLICATION_DIGEST_MISMATCH");
    }
    return new Session(context, publicationBytes);
  }

  /** Deterministic object location binds the application/module/version/run and exact artifact. */
  public static String artifactPath(AdapterContext context) {
    ArtifactoryOptions options = (ArtifactoryOptions) context.options();
    var correlation = context.bundle().correlation();
    String run =
        hash(
            JsonSupport.write(
                List.of(
                    "census.artifactory-path/1",
                    correlation.application(),
                    correlation.module(),
                    correlation.version(),
                    correlation.runId())));
    return options.repository()
        + "/"
        + options.pathPrefix()
        + "/"
        + run
        + "/"
        + context.bundle().publication().sha256()
        + ".json";
  }

  private static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private static final class Session implements AdapterSession {
    private final byte[] publication;
    private final String path;
    private final String digest;
    private Phase phase = Phase.CHECK;
    private boolean started;
    private AdapterResult observation =
        result(Upload.NOT_ATTEMPTED, "ARTIFACTORY_PREFLIGHT_PENDING", 0, null);

    Session(AdapterContext context, byte[] bytes) {
      publication = bytes.clone();
      path = artifactPath(context);
      digest = context.bundle().publication().sha256();
    }

    @Override
    public ProtocolStep first() {
      if (started) {
        throw new IllegalStateException("ARTIFACTORY_SESSION_SEQUENCE_INVALID");
      }
      started = true;
      return step(new AdapterRequest("GET", "api/storage/" + path, null, new byte[0], false, 0));
    }

    @Override
    public ProtocolStep accept(ProtocolResponse response) {
      if (!started || phase == Phase.FINISHED) {
        throw new IllegalStateException("ARTIFACTORY_SESSION_SEQUENCE_INVALID");
      }
      return switch (phase) {
        case CHECK -> check(response);
        case PUT -> uploaded(response);
        case READBACK -> readback(response);
        case FINISHED -> throw new IllegalStateException("ARTIFACTORY_SESSION_SEQUENCE_INVALID");
      };
    }

    private ProtocolStep check(ProtocolResponse response) {
      if (response.failureCode() != null || response.statusCode() != 404) {
        return finish(
            Upload.NOT_ATTEMPTED,
            response.failureCode() == null && response.statusCode() == 200
                ? "ARTIFACTORY_PATH_EXISTS"
                : "ARTIFACTORY_PREFLIGHT_UNCONFIRMED",
            response.statusCode());
      }
      phase = Phase.PUT;
      return step(
          new AdapterRequest("PUT", path, "application/vnd.cyclonedx+json", publication, true, 0));
    }

    private ProtocolStep uploaded(ProtocolResponse response) {
      if (response.failureCode() != null || response.statusCode() != 201) {
        return finish(
            response.failureCode() == null
                    && response.statusCode() >= 300
                    && response.statusCode() < 500
                ? Upload.REJECTED
                : Upload.UNKNOWN,
            "ARTIFACTORY_UPLOAD_UNCONFIRMED",
            response.statusCode());
      }
      // A 201 alone never satisfies this storage contract: bytes must be retrieved and compared.
      observation = result(Upload.ACCEPTED, "ARTIFACTORY_READBACK_PENDING", 201, digest);
      phase = Phase.READBACK;
      return step(
          new AdapterRequest("GET", path, null, new byte[0], false, 0).withPublicationReadback());
    }

    private ProtocolStep readback(ProtocolResponse response) {
      if (response.failureCode() != null || response.statusCode() != 200) {
        return finish(Upload.ACCEPTED, "ARTIFACTORY_READBACK_UNCONFIRMED", response.statusCode());
      }
      if (!digest.equals(hash(response.body()))) {
        return finish(Upload.ACCEPTED, "ARTIFACTORY_READBACK_DIGEST_MISMATCH", 200);
      }
      return finish(Upload.ACCEPTED, "ARTIFACTORY_STORED_EXACT_BYTES", 200);
    }

    private ProtocolStep finish(Upload upload, String reason, int status) {
      phase = Phase.FINISHED;
      observation = result(upload, reason, status, digest);
      return step(null);
    }

    private ProtocolStep step(AdapterRequest request) {
      return new ProtocolStep(request, observation);
    }

    private static AdapterResult result(Upload upload, String reason, int status, String digest) {
      return new AdapterResult(
          upload, Ingestion.NOT_APPLICABLE, reason, status, digest, null, List.of());
    }
  }

  private enum Phase {
    CHECK,
    PUT,
    READBACK,
    FINISHED
  }
}
