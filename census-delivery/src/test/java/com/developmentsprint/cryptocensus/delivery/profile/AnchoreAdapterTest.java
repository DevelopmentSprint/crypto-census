package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.delivery.anchore.AnchoreAdapter;
import com.developmentsprint.cryptocensus.delivery.anchore.AnchoreOptions;
import com.developmentsprint.cryptocensus.delivery.preparation.Correlation;
import com.developmentsprint.cryptocensus.delivery.preparation.InputKind;
import com.developmentsprint.cryptocensus.delivery.preparation.PreparedArtifact;
import com.developmentsprint.cryptocensus.delivery.preparation.PreparedBundle;
import com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnchoreAdapterTest {
  static final String APP = "00000000-0000-4000-8000-000000000001";
  static final String VERSION = "00000000-0000-4000-8000-000000000002";
  static final String JOB = "00000000-0000-4000-8000-000000000003";
  static final String ASSET = "00000000-0000-4000-8000-000000000004";
  static final String NAME = "runtime-run-1";
  static final Map<String, Object> SERVER =
      Map.of("api", Map.of("version", "2.19.0"), "service", Map.of("version", "6.2.0"));
  static final Map<String, Object> EMPTY =
      Map.of("items", List.of(), "pagination", Map.of("item_count", 0));
  @TempDir Path directory;
  final AnchoreAdapter adapter = new AnchoreAdapter();
  final byte[] publication = "{\"unchanged\":true}".getBytes(StandardCharsets.UTF_8);
  AdapterContext context;

  @BeforeEach
  void setup() throws Exception {
    String hash = ProfileFixture.hash(publication);
    PreparedArtifact artifact =
        new PreparedArtifact(
            "cbom", InputKind.CENSUS_RAW_CBOM, directory.resolve("cbom.json"), hash);
    PreparedBundle bundle =
        new PreparedBundle(
            List.of(artifact),
            artifact,
            directory.resolve("manifest.json"),
            "0".repeat(64),
            new Correlation("app", "module", "1.0", "run-1"),
            List.of(),
            0);
    context =
        new AdapterContext(
            bundle,
            URI.create("https://example.invalid/"),
            adapter.parseOptions(options()),
            new DeliveryPolicy(
                AnchoreAdapter.CAPABILITY_REVISION,
                AnchoreAdapter.LOSS_CODES,
                DeliveryPolicy.Completion.INGESTION_COMPLETED),
            new VerifiedRetention(hash, "0".repeat(64), Instant.now()));
  }

  static Map<String, Object> options() {
    return Map.of(
        "applicationUuid",
        APP,
        "versionUuid",
        VERSION,
        "assetName",
        NAME,
        "serverVersion",
        "6.2.0",
        "mutationPolicy",
        "DEDICATED_VERSION_SINGLE_WRITER");
  }

  static Map<String, Object> app() {
    return Map.of("system_metadata", Map.of("id", APP));
  }

  static Map<String, Object> version() {
    return Map.of(
        "system_metadata",
        Map.of("id", VERSION),
        "name",
        "1.0",
        "status",
        Map.of("current_value", "in_progress"));
  }

  static Map<String, Object> asset() {
    return Map.of("system_metadata", Map.of("id", ASSET), "name", NAME, "type", "application");
  }

  static Map<String, Object> job(String state) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("system_metadata", Map.of("id", JOB));
    result.put("status", state);
    result.put("job_type", "add-sbom-asset");
    result.put(
        "job_spec",
        Map.of(
            "app_versions",
            List.of(Map.of("app_version_id", VERSION)),
            "asset",
            Map.of("name", NAME, "type", "application")));
    if (state.equals("complete")) {
      result.put("completed_at", "2026-10-12T12:00:00Z");
      result.put("completion_detail", Map.of("asset_id", ASSET));
    }
    return result;
  }

  static ProtocolResponse response(int status, Object body) {
    return new ProtocolResponse(status, JsonSupport.write(body), null);
  }

  AdapterSession start() {
    AdapterSession session = adapter.open(context, publication);
    assertThat(session.first().request().relativePath()).isEqualTo("v2/version");
    return session;
  }

  AdapterSession beforeUpload() {
    AdapterSession session = start();
    assertThat(session.accept(response(200, SERVER)).request().relativePath())
        .isEqualTo("v2/apps/" + APP);
    assertThat(session.accept(response(200, app())).request().relativePath())
        .isEqualTo("v2/apps/" + APP + "/versions/" + VERSION);
    assertThat(session.accept(response(200, version())).request().relativePath())
        .endsWith("/assets?name=" + NAME + "&limit=1");
    return session;
  }

  AdapterSession uploading() {
    AdapterSession session = beforeUpload();
    assertThat(session.accept(response(200, EMPTY)).request().mutation()).isTrue();
    return session;
  }

  AdapterSession polling() {
    AdapterSession session = uploading();
    assertThat(session.accept(response(201, job("pending"))).observation().upload())
        .isEqualTo(AdapterResult.Upload.ACCEPTED);
    return session;
  }

  @Test
  void capabilitiesNeverClaimFidelityOrAtomicAppend() {
    assertThat(adapter.id()).isEqualTo("anchore-enterprise");
    assertThat(adapter.capabilities().mutation())
        .isEqualTo(CapabilityDeclaration.InventoryMutation.UNVERIFIED);
    assertThat(adapter.capabilities().semanticFields().values())
        .containsOnly(CapabilityDeclaration.FieldSupport.UNVERIFIED);
    assertThat(adapter.capabilities().byteRetention())
        .isEqualTo(CapabilityDeclaration.ByteRetention.UNVERIFIED);
    assertThat(adapter.capabilities().nativeIdempotency()).isFalse();
    assertThat(adapter.requiresOriginalRetention()).isTrue();
    assertThat(context.options().sanitized()).isEqualTo(options());
  }

  @Test
  void completeFlowPreservesMultipartBytesAndVerifiesCreatedAsset() {
    AdapterSession session = beforeUpload();
    AdapterRequest upload = session.accept(response(200, EMPTY)).request();
    assertThat(upload.method()).isEqualTo("POST");
    assertThat(upload.relativePath()).isEqualTo("v2/apps/" + APP + "/jobs/add-sbom-asset");
    String body = new String(upload.body(), StandardCharsets.UTF_8);
    assertThat(body)
        .contains(
            "name=\"asset_name\"\r\n\r\n" + NAME,
            "name=\"app_version_id\"\r\n\r\n" + VERSION,
            "name=\"asset_type\"\r\n\r\napplication",
            "name=\"sbom\"; filename=\"cbom.json\"",
            new String(publication, StandardCharsets.UTF_8));
    assertThat(session.accept(response(201, job("pending"))).request().relativePath())
        .endsWith("/jobs/add-sbom-asset/" + JOB);
    assertThat(session.accept(response(200, job("processing"))).request().delayMillis())
        .isEqualTo(500);
    assertThat(session.accept(response(200, job("complete"))).request().relativePath())
        .endsWith("/assets/" + ASSET);
    ProtocolStep done = session.accept(response(200, asset()));
    assertThat(done.request()).isNull();
    assertThat(done.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.COMPLETED);
    assertThat(done.observation().remoteOperationId()).isEqualTo(JOB);
    assertThat(done.observation().lossCodes())
        .containsExactlyElementsOf(AnchoreAdapter.LOSS_CODES.stream().sorted().toList());
    assertThatThrownBy(session::first).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> session.accept(response(200, asset())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void invalidOptionsAndReplacementFailBeforeNetwork() {
    for (String field : options().keySet()) {
      Map<String, Object> invalid = new LinkedHashMap<>(options());
      invalid.put(field, "../../secret\r\n");
      assertThatThrownBy(() -> adapter.parseOptions(invalid))
          .isInstanceOf(IllegalArgumentException.class);
      invalid.remove(field);
      assertThatThrownBy(() -> adapter.parseOptions(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    Map<String, Object> unknown = new LinkedHashMap<>(options());
    unknown.put("overwrite", true);
    assertThatThrownBy(() -> adapter.parseOptions(unknown))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AnchoreOptions(
                    java.util.UUID.fromString(APP),
                    java.util.UUID.fromString(VERSION),
                    NAME,
                    "6.2.0",
                    null))
        .hasMessage("ANCHORE_MUTATION_POLICY_REQUIRED");
  }

  @Test
  void retentionAndNamedAcceptanceRequired() {
    AdapterContext noProof =
        new AdapterContext(
            context.bundle(), context.endpoint(), context.options(), context.policy(), null);
    adapter.validate(noProof);
    assertThatThrownBy(() -> adapter.open(noProof, publication))
        .hasMessage("ANCHORE_VERIFIED_ORIGINAL_RETENTION_REQUIRED");
    assertThatThrownBy(() -> adapter.open(context, new byte[] {1}))
        .hasMessage("ANCHORE_VERIFIED_ORIGINAL_RETENTION_REQUIRED");
    for (String revision : List.of("stale", AnchoreAdapter.CAPABILITY_REVISION)) {
      AdapterContext invalid =
          new AdapterContext(
              context.bundle(),
              context.endpoint(),
              context.options(),
              new DeliveryPolicy(revision, Set.of(), DeliveryPolicy.Completion.UPLOAD_ACCEPTED),
              context.retention());
      assertThatThrownBy(() -> adapter.validate(invalid))
          .hasMessage("ANCHORE_UNVERIFIED_CAPABILITIES_ACCEPTANCE_REQUIRED");
    }
  }

  @Test
  void unexpectedMappingAndVersionBlockBeforeUpload() {
    assertBlocked(
        start()
            .accept(
                response(
                    200,
                    Map.of(
                        "api",
                        Map.of("version", "2.20.0"),
                        "service",
                        Map.of("version", "6.2.0")))));
    AdapterSession session = start();
    session.accept(response(200, SERVER));
    assertBlocked(session.accept(response(200, Map.of("system_metadata", Map.of("id", VERSION)))));
    session = start();
    session.accept(response(200, SERVER));
    session.accept(response(200, app()));
    assertBlocked(
        session.accept(
            response(
                200,
                Map.of(
                    "system_metadata",
                    Map.of("id", VERSION),
                    "name",
                    "different",
                    "status",
                    Map.of("current_value", "in_progress")))));
  }

  @Test
  void collisionsAndIncompletePaginationNeverUpload() {
    for (Object body :
        List.of(
            Map.of("items", List.of(asset()), "pagination", Map.of("item_count", 1)),
            Map.of(
                "items",
                List.of(),
                "pagination",
                Map.of("item_count", 0, "next_cursor", "another")),
            Map.of("items", List.of(), "pagination", Map.of("item_count", "0")),
            Map.of("pagination", Map.of("item_count", 0)))) {
      assertBlocked(beforeUpload().accept(response(200, body)));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"failed", "cancelled"})
  void terminalJobFailureIsNotSuccess(String state) {
    ProtocolStep done = polling().accept(response(200, job(state)));
    assertThat(done.request()).isNull();
    assertThat(done.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.FAILED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown", "COMPLETED", "", "secret\nvalue"})
  void unknownJobStateCannotSatisfyCompletion(String state) {
    ProtocolStep done = polling().accept(response(200, job(state)));
    assertThat(done.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
    assertThat(done.observation().toString()).doesNotContain("secret");
  }

  @Test
  void incompleteOrWrongJobMappingIsNeverCompletion() {
    for (String field :
        List.of("system_metadata", "job_type", "job_spec", "completed_at", "completion_detail")) {
      Map<String, Object> invalid = new LinkedHashMap<>(job("complete"));
      invalid.remove(field);
      assertThat(polling().accept(response(200, invalid)).observation().ingestion())
          .isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
    }
    Map<String, Object> invalid = new LinkedHashMap<>(job("complete"));
    invalid.put("completed_at", "not-a-time");
    assertThat(polling().accept(response(200, invalid)).observation().ingestion())
        .isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
    invalid = new LinkedHashMap<>(job("complete"));
    invalid.put("error", Map.of("reason", "secret", "code", "AS510"));
    assertThat(polling().accept(response(200, invalid)).observation().ingestion())
        .isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
  }

  @Test
  void ambiguousUploadNeverRetriesAndAuthenticationFailuresAreRejected() {
    for (ProtocolResponse response :
        List.of(
            new ProtocolResponse(0, new byte[0], "TIMEOUT"),
            response(500, Map.of()),
            response(201, Map.of()),
            new ProtocolResponse(201, "{bad}".getBytes(StandardCharsets.UTF_8), null))) {
      ProtocolStep done = uploading().accept(response);
      assertThat(done.request()).isNull();
      assertThat(done.observation().upload()).isEqualTo(AdapterResult.Upload.UNKNOWN);
    }
    ProtocolStep unauthorized = uploading().accept(response(401, Map.of()));
    assertThat(unauthorized.observation().upload()).isEqualTo(AdapterResult.Upload.REJECTED);
    assertBlocked(start().accept(response(401, Map.of())));
    ProtocolStep timeout = polling().accept(new ProtocolResponse(0, new byte[0], "TIMEOUT"));
    assertThat(timeout.observation().upload()).isEqualTo(AdapterResult.Upload.ACCEPTED);
    assertThat(timeout.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
  }

  @Test
  void assetMustBelongToCompletedJobAndMatchNameAndType() {
    AdapterSession session = polling();
    session.accept(response(200, job("complete")));
    ProtocolStep wrong =
        session.accept(
            response(
                200,
                Map.of(
                    "system_metadata",
                    Map.of("id", VERSION),
                    "name",
                    NAME,
                    "type",
                    "application")));
    assertThat(wrong.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
  }

  @Test
  void mismatchedRetentionAndUnsupportedInputCannotOpen() {
    AdapterContext wrongProof =
        new AdapterContext(
            context.bundle(),
            context.endpoint(),
            context.options(),
            context.policy(),
            new VerifiedRetention("f".repeat(64), "0".repeat(64), Instant.now()));
    assertThatThrownBy(() -> adapter.open(wrongProof, publication))
        .hasMessage("ANCHORE_VERIFIED_ORIGINAL_RETENTION_REQUIRED");
    PreparedArtifact unsupported =
        new PreparedArtifact(
            "external",
            InputKind.EXTERNAL_SOFTWARE_SBOM,
            context.bundle().publication().path(),
            context.bundle().publication().sha256());
    PreparedBundle other =
        new PreparedBundle(
            List.of(unsupported),
            unsupported,
            context.bundle().manifest(),
            context.bundle().manifestSha256(),
            context.bundle().correlation(),
            List.of(),
            0);
    assertThatThrownBy(
            () ->
                adapter.validate(
                    new AdapterContext(
                        other,
                        context.endpoint(),
                        context.options(),
                        context.policy(),
                        context.retention())))
        .hasMessage("ANCHORE_INPUT_KIND_UNSUPPORTED");
    assertThatThrownBy(
            () ->
                adapter.validate(
                    new AdapterContext(
                        context.bundle(),
                        context.endpoint(),
                        null,
                        context.policy(),
                        context.retention())))
        .hasMessage("ANCHORE_OPTIONS_INVALID");
    assertThatThrownBy(() -> adapter.open(context, new byte[16 * 1024 * 1024 + 1]))
        .hasMessage("ANCHORE_VERIFIED_ORIGINAL_RETENTION_REQUIRED");
  }

  @Test
  void foreignJobOrVersionOrAssetCannotBeAccepted() {
    Map<String, Object> foreignId = new LinkedHashMap<>(job("complete"));
    foreignId.put("system_metadata", Map.of("id", APP));
    assertThat(polling().accept(response(200, foreignId)).observation().ingestion())
        .isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
    for (Object spec :
        List.of(
            Map.of(
                "app_versions",
                List.of(Map.of("app_version_id", APP)),
                "asset",
                Map.of("name", NAME, "type", "application")),
            Map.of(
                "app_versions",
                List.of(Map.of("app_version_id", VERSION), Map.of("app_version_id", VERSION)),
                "asset",
                Map.of("name", NAME, "type", "application")),
            Map.of(
                "app_versions",
                List.of(Map.of("app_version_id", VERSION)),
                "asset",
                Map.of("name", "other", "type", "application")),
            Map.of(
                "app_versions",
                List.of(Map.of("app_version_id", VERSION)),
                "asset",
                Map.of("name", NAME, "type", "library")))) {
      Map<String, Object> invalid = new LinkedHashMap<>(job("pending"));
      invalid.put("job_spec", spec);
      assertThat(uploading().accept(response(201, invalid)).observation().upload())
          .isEqualTo(AdapterResult.Upload.UNKNOWN);
    }
    Map<String, Object> contradiction = new LinkedHashMap<>(job("processing"));
    contradiction.put("completed_at", "2026-10-12T12:00:00Z");
    assertThat(polling().accept(response(200, contradiction)).observation().ingestion())
        .isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
  }

  @Test
  void uploadOnlyPolicyCannotTurnAnAsyncFailureIntoSuccess() {
    AdapterContext invalid =
        new AdapterContext(
            context.bundle(),
            context.endpoint(),
            context.options(),
            new DeliveryPolicy(
                AnchoreAdapter.CAPABILITY_REVISION,
                AnchoreAdapter.LOSS_CODES,
                DeliveryPolicy.Completion.UPLOAD_ACCEPTED),
            context.retention());
    assertThatThrownBy(() -> adapter.validate(invalid))
        .hasMessage("ANCHORE_INGESTION_COMPLETION_REQUIRED");
    assertThat(adapter.requiresAuthentication()).isTrue();
    assertThat(context.options().authenticationScheme())
        .isEqualTo(AdapterOptions.AuthenticationScheme.BASIC);
  }

  private static void assertBlocked(ProtocolStep step) {
    assertThat(step.request()).isNull();
    assertThat(step.observation().upload()).isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
  }
}
