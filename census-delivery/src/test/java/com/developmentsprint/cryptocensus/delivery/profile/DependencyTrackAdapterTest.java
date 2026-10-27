package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Primitive;
import com.developmentsprint.cryptocensus.delivery.dependencytrack.DependencyTrackAdapter;
import com.developmentsprint.cryptocensus.delivery.dependencytrack.DependencyTrackOptions;
import com.developmentsprint.cryptocensus.delivery.preparation.Correlation;
import com.developmentsprint.cryptocensus.delivery.preparation.InputKind;
import com.developmentsprint.cryptocensus.delivery.preparation.PreparedArtifact;
import com.developmentsprint.cryptocensus.delivery.preparation.PreparedBundle;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Native protocol tests; real transport/retention proof is covered by the HTTPS integration test.
 */
class DependencyTrackAdapterTest {
  private static final UUID PROJECT = UUID.fromString("bb2163f9-0c10-44d1-bd03-234283065428");
  private static final String TOKEN = "00000000-0000-4000-8000-000000000001";
  private static final String OWNER = "census-demo:runtime-writer";
  private static final Map<String, Object> SERVER =
      Map.of("application", "Dependency-Track", "version", "5.2.0");
  private static final Map<String, Object> PROJECT_RESPONSE =
      Map.of(
          "uuid",
          PROJECT.toString(),
          "version",
          "1.0",
          "classifier",
          "APPLICATION",
          "active",
          true);
  private static final List<Map<String, Object>> OWNER_RESPONSE =
      List.of(
          Map.of(
              "groupName", "cryptocensus",
              "propertyName", "runtime.owner",
              "propertyType", "STRING",
              "propertyValue", OWNER));
  @TempDir Path directory;
  private final DependencyTrackAdapter adapter = new DependencyTrackAdapter();
  private byte[] publication;
  private String digest;
  private PreparedBundle bundle;

  @BeforeEach
  void setup() throws Exception {
    Inventory inventory = new Inventory();
    inventory.record(
        CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, "SUN"),
        new Occurrence("example.Workload", "digest", 1, null));
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    for (String health : Cbom.HEALTH_PROPERTIES) {
      metadata.put(health, "0");
    }
    Path artifact = directory.resolve("cbom.json");
    Cbom.write(inventory, new Cbom.Metadata("module", metadata), artifact);
    publication = Files.readAllBytes(artifact);
    digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publication));
    PreparedArtifact source =
        new PreparedArtifact("cbom", InputKind.CENSUS_RAW_CBOM, artifact, digest);
    bundle =
        new PreparedBundle(
            List.of(source),
            source,
            directory.resolve("manifest.json"),
            "0".repeat(64),
            new Correlation("app", "module", "1.0", "run-1"),
            List.of(),
            0);
  }

  @Test
  void declaresMeasuredReplacementAndLossWithoutIdempotency() {
    CapabilityDeclaration capability = adapter.capabilities();
    assertThat(capability.serverVersion()).isEqualTo("5.2.0");
    assertThat(capability.mutation())
        .isEqualTo(CapabilityDeclaration.InventoryMutation.REPLACE_SNAPSHOT);
    assertThat(capability.nativeIdempotency()).isFalse();
    assertThat(adapter.requiresOriginalRetention()).isTrue();
    assertThat(capability.byteRetention()).isEqualTo(CapabilityDeclaration.ByteRetention.TEMPORARY);
    assertThat(capability.semanticFields())
        .containsEntry("cryptoProperties", CapabilityDeclaration.FieldSupport.DROPPED);
    assertThat(options().sanitized()).isEqualTo(rawOptions());
    assertThat(capability.lossCodes()).hasSize(7);
    assertThat(capability.inputKinds()).doesNotContain(InputKind.DERIVED_COMBINED_BOM);
    assertThat(options().authenticationScheme())
        .isEqualTo(AdapterOptions.AuthenticationScheme.API_KEY);
  }

  @Test
  void optionsRejectUnknownFieldsAndImplicitReplacement() {
    Map<String, Object> raw = new LinkedHashMap<>(rawOptions());
    raw.put("autoCreate", true);
    assertThatThrownBy(() -> adapter.parseOptions(raw))
        .isInstanceOf(IllegalArgumentException.class);
    raw.remove("autoCreate");
    raw.remove("replacementPolicy");
    assertThatThrownBy(() -> adapter.parseOptions(raw))
        .isInstanceOf(IllegalArgumentException.class);
    raw.put("replacementPolicy", "SHARED_SOFTWARE_SNAPSHOT");
    assertThatThrownBy(() -> adapter.parseOptions(raw))
        .hasMessage("DT_REPLACEMENT_POLICY_UNSUPPORTED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"1-1-1-1-1", "https://example.invalid/project", "../project", "secret\nvalue"})
  void rejectsUnsafeProjectIdentity(String value) {
    Map<String, Object> raw = new LinkedHashMap<>(rawOptions());
    raw.put("projectUuid", value);
    assertThatThrownBy(() -> adapter.parseOptions(raw))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("DT_RESPONSE_INVALID");
  }

  @Test
  void fullFidelityDefaultAndIncompleteOrStaleLossWaiversFailLocally() {
    for (DeliveryPolicy policy :
        List.of(
            new DeliveryPolicy(
                DependencyTrackAdapter.CAPABILITY_REVISION,
                Set.of(),
                DeliveryPolicy.Completion.INGESTION_COMPLETED),
            new DeliveryPolicy(
                "untested-capabilities",
                DependencyTrackAdapter.LOSS_CODES,
                DeliveryPolicy.Completion.INGESTION_COMPLETED),
            new DeliveryPolicy(
                DependencyTrackAdapter.CAPABILITY_REVISION,
                Set.of("allowLoss"),
                DeliveryPolicy.Completion.INGESTION_COMPLETED))) {
      assertThatThrownBy(() -> adapter.validate(context(policy, null)))
          .hasMessage("DT_MEASURED_LOSS_ACCEPTANCE_REQUIRED");
    }
    // A local plan has no remote proof; execution cannot turn that into a retention claim.
    adapter.validate(context(policy(), null));
    assertThatThrownBy(() -> adapter.open(context(policy(), null), publication))
        .hasMessage("DT_VERIFIED_ORIGINAL_RETENTION_REQUIRED");
  }

  @Test
  void requiresMatchingRemoteDigestAndExactPublicationBytes() {
    VerifiedRetention wrong = new VerifiedRetention("f".repeat(64), "0".repeat(64), Instant.now());
    assertThatThrownBy(() -> adapter.open(context(policy(), wrong), publication))
        .hasMessage("DT_VERIFIED_ORIGINAL_RETENTION_REQUIRED");
    assertThatThrownBy(
            () ->
                adapter.open(
                    context(policy(), proof()), "different".getBytes(StandardCharsets.UTF_8)))
        .hasMessage("DT_VERIFIED_ORIGINAL_RETENTION_REQUIRED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"EXTERNAL_SOFTWARE_SBOM", "DERIVED_COMBINED_BOM"})
  void refusesNonCensusAndCombinedPublication(String kind) {
    PreparedArtifact invalid =
        new PreparedArtifact(
            "output", InputKind.valueOf(kind), bundle.publication().path(), digest);
    PreparedBundle other =
        new PreparedBundle(
            bundle.sources(),
            invalid,
            bundle.manifest(),
            bundle.manifestSha256(),
            bundle.correlation(),
            List.of(),
            0);
    AdapterContext context =
        new AdapterContext(
            other, URI.create("https://example.invalid/"), options(), policy(), proof());
    assertThatThrownBy(() -> adapter.validate(context)).hasMessage("DT_INPUT_KIND_UNSUPPORTED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"5.1.0", "5.2.1", "6.0.0", "5.2.0-secret"})
  void unverifiedServerNeverReachesProjectOrUpload(String version) {
    AdapterSession session = started();
    ProtocolStep step =
        session.accept(response(Map.of("application", "Dependency-Track", "version", version)));
    assertBlocked(step, "DT_SERVER_UNVERIFIED");
    assertThat(step.observation().serverVersion()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"uuid", "version", "classifier", "active", "collectionLogic"})
  void projectMappingAndCollectionMismatchCannotUpload(String field) {
    AdapterSession session = started();
    session.accept(response(SERVER));
    Map<String, Object> project = new LinkedHashMap<>(PROJECT_RESPONSE);
    project.put(
        field,
        field.equals("active")
            ? false
            : field.equals("uuid") ? UUID.randomUUID().toString() : "different");
    assertBlocked(session.accept(response(project)), "DT_PROJECT_MAPPING_UNVERIFIED");
  }

  @Test
  void emptyInventoryIsNotOwnershipAndWrongOrDuplicateMarkersFail() {
    for (Object properties :
        List.of(
            List.of(),
            List.of(
                Map.of(
                    "groupName",
                    "cryptocensus",
                    "propertyName",
                    "runtime.owner",
                    "propertyType",
                    "STRING",
                    "propertyValue",
                    "another-writer")),
            List.of(OWNER_RESPONSE.get(0), OWNER_RESPONSE.get(0)))) {
      AdapterSession session = started();
      session.accept(response(SERVER));
      session.accept(response(PROJECT_RESPONSE));
      assertBlocked(session.accept(response(properties)), "DT_PROJECT_OWNERSHIP_UNVERIFIED");
    }
  }

  @Test
  void softwareOnLaterPageAndRepeatedPagesCannotSlipThrough() {
    AdapterSession session = beforeComponents();
    ProtocolStep next = session.accept(response(cryptoPage(25)));
    assertThat(next.request().relativePath()).contains("pageNumber=2");
    assertBlocked(
        session.accept(
            response(
                List.of(Map.of("uuid", UUID.randomUUID().toString(), "classifier", "LIBRARY")))),
        "DT_SOFTWARE_INVENTORY_REPLACEMENT_FORBIDDEN");
    AdapterSession repeated = beforeComponents();
    List<Map<String, Object>> page = cryptoPage(25);
    repeated.accept(response(page));
    assertBlocked(repeated.accept(response(page)), "DT_INVENTORY_PAGINATION_UNVERIFIED");
  }

  @Test
  void oversizedOrUnboundedInventoriesFailBeforeUpload() {
    assertBlocked(
        beforeComponents().accept(response(cryptoPage(26))), "DT_INVENTORY_PAGINATION_UNVERIFIED");
    AdapterSession session = beforeComponents();
    ProtocolStep step = null;
    for (int i = 0; i < 40; i++) {
      step = session.accept(response(cryptoPage(25)));
    }
    assertBlocked(step, "DT_INVENTORY_LIMIT_EXCEEDED");
  }

  @Test
  void servicesCannotBeDeletedByCryptoSnapshot() {
    AdapterSession session = beforeComponents();
    ProtocolStep next = session.accept(response(List.of()));
    assertThat(next.request().relativePath()).contains("service/project/");
    assertBlocked(
        session.accept(response(List.of(Map.of("name", "existing-service")))),
        "DT_SERVICE_INVENTORY_REPLACEMENT_FORBIDDEN");
  }

  @Test
  void exactBytesAreFramedOnlyAfterAllReadOnlyGuardsPass() {
    AdapterSession session = beforeComponents();
    session.accept(response(cryptoPage(2)));
    ProtocolStep step = session.accept(response(List.of()));
    assertThat(step.request().method()).isEqualTo("POST");
    assertThat(step.request().relativePath()).isEqualTo("api/v1/bom");
    assertThat(step.request().mutation()).isTrue();
    byte[] framed = step.request().body();
    String multipart = new String(framed, StandardCharsets.UTF_8);
    String start = "Content-Type: application/vnd.cyclonedx+json\r\n\r\n";
    int offset = multipart.indexOf(start) + start.length();
    assertThat(
            multipart.substring(
                offset, offset + new String(publication, StandardCharsets.UTF_8).length()))
        .isEqualTo(new String(publication, StandardCharsets.UTF_8));
    assertThat(multipart).doesNotContain("autoCreate");
    assertThat(step.observation().upload()).isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
  }

  @Test
  void acceptanceIsPendingUntilExplicitCompleted() {
    AdapterSession session = beforeUploadResponse();
    ProtocolStep accepted =
        session.accept(response(Map.of("token", TOKEN, "projectUuid", PROJECT.toString())));
    assertThat(accepted.observation().upload()).isEqualTo(AdapterResult.Upload.ACCEPTED);
    assertThat(accepted.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.PENDING);
    assertThat(accepted.request().relativePath()).isEqualTo("api/v1/event/token/" + TOKEN);
    ProtocolStep pending =
        session.accept(response(Map.of("processing", true, "status", "RUNNING")));
    assertThat(pending.request().delayMillis()).isEqualTo(500);
    ProtocolStep done =
        session.accept(response(Map.of("processing", false, "status", "COMPLETED")));
    assertThat(done.request()).isNull();
    assertThat(done.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.COMPLETED);
    assertThat(done.observation().reasonCode()).isEqualTo("DT_IMPORT_COMPLETED_WITH_LOSS");
    assertThat(done.observation().lossCodes())
        .containsExactlyInAnyOrderElementsOf(DependencyTrackAdapter.LOSS_CODES);
  }

  @ParameterizedTest
  @CsvSource({
    "false,FAILED,FAILED",
    "true,COMPLETED,UNVERIFIED",
    "false,PENDING,UNVERIFIED",
    "true,FUTURE,UNVERIFIED",
    "false,FUTURE,UNVERIFIED"
  })
  void terminalFailureAndContradictoryOrUnknownStatesNeverBecomeSuccess(
      boolean processing, String status, String expected) {
    AdapterSession session = beforeStatus();
    ProtocolStep done =
        session.accept(response(Map.of("processing", processing, "status", status)));
    assertThat(done.request()).isNull();
    assertThat(done.observation().upload()).isEqualTo(AdapterResult.Upload.ACCEPTED);
    assertThat(done.observation().ingestion().name()).isEqualTo(expected);
  }

  @Test
  void unknownTokenFalseWithoutStatusIsUnverified() {
    ProtocolStep done = beforeStatus().accept(response(Map.of("processing", false)));
    assertThat(done.request()).isNull();
    assertThat(done.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
    assertThat(done.observation().reasonCode()).isEqualTo("DT_TOKEN_STATUS_UNVERIFIED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<html>secret-value</html>",
        "{\"token\":\"one\",\"token\":\"secret-value\"}",
        "[]",
        "null",
        "{\"token\":\"https://secret-value.invalid\",\"projectUuid\":\"bb2163f9-0c10-44d1-bd03-234283065428\"}"
      })
  void malformedUploadAcknowledgmentIsAmbiguousAndNeverLeaksBody(String body) {
    ProtocolStep done =
        beforeUploadResponse()
            .accept(new ProtocolResponse(200, body.getBytes(StandardCharsets.UTF_8), null));
    assertThat(done.request()).isNull();
    assertThat(done.observation().upload()).isEqualTo(AdapterResult.Upload.UNKNOWN);
    assertThat(done.observation().toString()).doesNotContain("secret-value");
  }

  @Test
  void mismatchedAcknowledgedProjectNeverPollsAnotherProjectsToken() {
    ProtocolStep done =
        beforeUploadResponse()
            .accept(response(Map.of("token", TOKEN, "projectUuid", UUID.randomUUID().toString())));
    assertThat(done.request()).isNull();
    assertThat(done.observation().reasonCode()).isEqualTo("DT_UPLOAD_ACK_MISMATCH");
    assertThat(done.observation().upload()).isEqualTo(AdapterResult.Upload.UNKNOWN);
  }

  @ParameterizedTest
  @CsvSource({
    "301,REJECTED",
    "401,REJECTED",
    "403,REJECTED",
    "429,REJECTED",
    "500,UNKNOWN",
    "503,UNKNOWN",
    "201,UNKNOWN"
  })
  void uploadFailuresNeverRetryOrFollowRedirect(int status, String expected) {
    ProtocolStep done =
        beforeUploadResponse()
            .accept(
                new ProtocolResponse(
                    status, "secret-value".getBytes(StandardCharsets.UTF_8), null));
    assertThat(done.request()).isNull();
    assertThat(done.observation().upload().name()).isEqualTo(expected);
    assertThat(done.observation().toString()).doesNotContain("secret-value");
  }

  @Test
  void transportFailureRetainsUploadAmbiguityAndAcceptedStatusSeparately() {
    ProtocolResponse failure = new ProtocolResponse(0, new byte[0], "secret-transport-error");
    ProtocolStep before = started().accept(failure);
    assertThat(before.observation().upload()).isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
    ProtocolStep upload = beforeUploadResponse().accept(failure);
    assertThat(upload.observation().upload()).isEqualTo(AdapterResult.Upload.UNKNOWN);
    ProtocolStep polling = beforeStatus().accept(failure);
    assertThat(polling.observation().upload()).isEqualTo(AdapterResult.Upload.ACCEPTED);
    assertThat(polling.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
    for (ProtocolStep step : List.of(before, upload, polling)) {
      assertThat(step.request()).isNull();
      assertThat(step.observation().toString()).doesNotContain("secret-transport-error");
    }
  }

  private AdapterSession started() {
    AdapterSession session = adapter.open(context(policy(), proof()), publication);
    ProtocolStep first = session.first();
    assertThat(first.request().relativePath()).isEqualTo("api/version");
    assertThat(first.request().mutation()).isFalse();
    return session;
  }

  private AdapterSession beforeComponents() {
    AdapterSession session = started();
    for (Object body : List.of(SERVER, PROJECT_RESPONSE, OWNER_RESPONSE)) {
      assertThat(session.accept(response(body)).request().mutation()).isFalse();
    }
    return session;
  }

  private AdapterSession beforeUploadResponse() {
    AdapterSession session = beforeComponents();
    session.accept(response(List.of()));
    assertThat(session.accept(response(List.of())).request().mutation()).isTrue();
    return session;
  }

  private AdapterSession beforeStatus() {
    AdapterSession session = beforeUploadResponse();
    session.accept(response(Map.of("token", TOKEN, "projectUuid", PROJECT.toString())));
    return session;
  }

  private AdapterContext context(DeliveryPolicy policy, VerifiedRetention retention) {
    return new AdapterContext(
        bundle, URI.create("https://example.invalid/"), options(), policy, retention);
  }

  private DependencyTrackOptions options() {
    return (DependencyTrackOptions) adapter.parseOptions(rawOptions());
  }

  private static Map<String, Object> rawOptions() {
    return Map.of(
        "projectUuid",
        PROJECT.toString(),
        "ownerId",
        OWNER,
        "replacementPolicy",
        "OWNED_RUNTIME_SNAPSHOT");
  }

  private static DeliveryPolicy policy() {
    return new DeliveryPolicy(
        DependencyTrackAdapter.CAPABILITY_REVISION,
        DependencyTrackAdapter.LOSS_CODES,
        DeliveryPolicy.Completion.INGESTION_COMPLETED);
  }

  private VerifiedRetention proof() {
    return new VerifiedRetention(digest, "0".repeat(64), Instant.now());
  }

  private static ProtocolResponse response(Object body) {
    return new ProtocolResponse(200, JsonSupport.write(body), null);
  }

  private static List<Map<String, Object>> cryptoPage(int count) {
    List<Map<String, Object>> components = new ArrayList<>();
    IntStream.range(0, count)
        .forEach(
            i ->
                components.add(
                    Map.of(
                        "uuid",
                        UUID.randomUUID().toString(),
                        "classifier",
                        "CRYPTOGRAPHIC_ASSET")));
    return components;
  }

  private static void assertBlocked(ProtocolStep step, String reason) {
    assertThat(step.request()).isNull();
    assertThat(step.observation().upload()).isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
    assertThat(step.observation().reasonCode()).isEqualTo(reason);
  }
}
