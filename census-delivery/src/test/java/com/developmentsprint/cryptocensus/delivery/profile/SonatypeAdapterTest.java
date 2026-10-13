package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.delivery.preparation.Correlation;
import com.developmentsprint.cryptocensus.delivery.preparation.InputKind;
import com.developmentsprint.cryptocensus.delivery.preparation.PreparedArtifact;
import com.developmentsprint.cryptocensus.delivery.preparation.PreparedBundle;
import com.developmentsprint.cryptocensus.delivery.sonatype.SonatypeOptions;
import com.developmentsprint.cryptocensus.delivery.sonatype.SonatypeSbomManagerAdapter;
import com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SonatypeAdapterTest {
  static final String APPLICATION = "c9920b0b240f405a8fc11c897864e2e5";
  static final String OPERATION = "a5bd3587cf7d45f3aaa2c09401a47b27";
  static final String STATUS = "api/v2/sbom/applications/" + APPLICATION + "/status/" + OPERATION;
  static final String DOWNLOAD = "api/v2/sbom/" + APPLICATION + "/version/1.0?form=original";
  @TempDir Path directory;
  final SonatypeSbomManagerAdapter adapter = new SonatypeSbomManagerAdapter();
  byte[] bytes;
  PreparedBundle bundle;

  @BeforeEach
  void setup() throws Exception {
    ProfileFixture.configuration(directory, "https://example.invalid/", null);
    Path path = directory.resolve("cbom.json");
    bytes = Files.readAllBytes(path);
    PreparedArtifact source =
        new PreparedArtifact("cbom", InputKind.CENSUS_RAW_CBOM, path, ProfileFixture.hash(bytes));
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

  static Map<String, Object> options() {
    return Map.of(
        "applicationId", APPLICATION, "publicId", "app", "importPolicy", "NEW_VERSION_ONLY");
  }

  static Map<String, Object> application() {
    return Map.of("applications", List.of(Map.of("id", APPLICATION, "publicId", "app")));
  }

  static Map<String, Object> completed() {
    return Map.of(
        "applicationId", APPLICATION, "version", "1.0", "isError", false, "downloadUrl", DOWNLOAD);
  }

  AdapterContext context(DeliveryPolicy policy, VerifiedRetention retention) {
    return new AdapterContext(
        bundle,
        URI.create("https://example.invalid/"),
        adapter.parseOptions(options()),
        policy,
        retention);
  }

  DeliveryPolicy policy() {
    return new DeliveryPolicy(
        SonatypeSbomManagerAdapter.CAPABILITY_REVISION,
        SonatypeSbomManagerAdapter.LOSS_CODES,
        DeliveryPolicy.Completion.INGESTION_COMPLETED);
  }

  VerifiedRetention proof() {
    return new VerifiedRetention(bundle.publication().sha256(), "0".repeat(64), Instant.now());
  }

  static ProtocolResponse response(Object object) {
    return new ProtocolResponse(200, JsonSupport.write(object), null);
  }

  AdapterSession started() {
    AdapterSession session = adapter.open(context(policy(), proof()), bytes);
    assertThat(session.first().request().relativePath())
        .isEqualTo("api/v2/applications?publicId=app");
    return session;
  }

  AdapterSession beforeUpload() {
    AdapterSession session = started();
    session.accept(response(application()));
    var step = session.accept(response(Map.of("totalResultsCount", 0, "results", List.of())));
    assertThat(step.request().relativePath())
        .isEqualTo("api/v2/sbom/import?ignoreValidationError=false");
    assertThat(step.request().mutation()).isTrue();
    String multipart = new String(step.request().body(), StandardCharsets.UTF_8);
    assertThat(multipart)
        .contains(
            "name=\"applicationId\"\r\n\r\n" + APPLICATION,
            "name=\"applicationVersion\"\r\n\r\n1.0",
            new String(bytes, StandardCharsets.UTF_8));
    return session;
  }

  AdapterSession beforeStatus() {
    AdapterSession session = beforeUpload();
    var step = session.accept(response(Map.of("statusUrl", STATUS)));
    assertThat(step.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.PENDING);
    assertThat(step.request().relativePath()).isEqualTo(STATUS);
    return session;
  }

  @Test
  void exactOriginalIsRequiredAfterMatchingTerminalCompletion() {
    AdapterSession session = beforeStatus();
    var download = session.accept(response(completed()));
    assertThat(download.request().relativePath()).isEqualTo(DOWNLOAD);
    assertThat(download.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.PENDING);
    var result = session.accept(new ProtocolResponse(200, bytes, null));
    assertThat(result.request()).isNull();
    assertThat(result.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.COMPLETED);
    assertThatThrownBy(() -> session.accept(response(completed())))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void capabilityNeverClaimsMeasuredVendorFidelity() {
    assertThat(adapter.capabilities().semanticFields().values())
        .containsOnly(CapabilityDeclaration.FieldSupport.UNVERIFIED);
    assertThat(adapter.capabilities().byteRetention())
        .isEqualTo(CapabilityDeclaration.ByteRetention.UNVERIFIED);
    assertThat(adapter.capabilities().nativeIdempotency()).isFalse();
    assertThat(adapter.requiresPublicationReadback()).isTrue();
    assertThat(adapter.requiresOriginalRetention()).isTrue();
    assertThat(adapter.parseOptions(options()).sanitized()).isEqualTo(options());
    assertThat(adapter.parseOptions(options()).authenticationScheme())
        .isEqualTo(AdapterOptions.AuthenticationScheme.BASIC);
  }

  @Test
  void localGuardsFailWithoutAcceptedLossAndExactOriginal() {
    assertThatThrownBy(
            () ->
                adapter.validate(
                    context(
                        new DeliveryPolicy(
                            "bad", Set.of(), DeliveryPolicy.Completion.UPLOAD_ACCEPTED),
                        null)))
        .hasMessage("SONATYPE_UNVERIFIED_FIDELITY_ACCEPTANCE_REQUIRED");
    assertThatThrownBy(() -> adapter.open(context(policy(), null), bytes))
        .hasMessage("SONATYPE_VERIFIED_ORIGINAL_REQUIRED");
    assertThatThrownBy(() -> adapter.open(context(policy(), proof()), new byte[] {1}))
        .hasMessage("SONATYPE_VERIFIED_ORIGINAL_REQUIRED");
    assertThatThrownBy(() -> adapter.parseOptions(Map.of("extra", true)))
        .isInstanceOf(IllegalArgumentException.class);
    for (String unsafe : List.of("../app", "app/child", "app?x=y", "app%2f", "a\r\nb", ".", "..")) {
      assertThatThrownBy(() -> new SonatypeOptions(APPLICATION, unsafe, "NEW_VERSION_ONLY"))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new SonatypeOptions("wrong", "app", "NEW_VERSION_ONLY"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SonatypeOptions(APPLICATION, "app", "REPLACE"))
        .isInstanceOf(IllegalArgumentException.class);
    var wrongKind =
        new PreparedArtifact(
            "combined",
            InputKind.DERIVED_COMBINED_BOM,
            bundle.publication().path(),
            bundle.publication().sha256());
    bundle =
        new PreparedBundle(
            bundle.sources(),
            wrongKind,
            bundle.manifest(),
            bundle.manifestSha256(),
            bundle.correlation(),
            List.of(),
            0);
    assertThatThrownBy(() -> adapter.validate(context(policy(), proof())))
        .hasMessage("SONATYPE_INPUT_KIND_UNSUPPORTED");
  }

  @Test
  void invalidMappingAndSessionSequenceAreBlocked() {
    AdapterSession session = adapter.open(context(policy(), proof()), bytes);
    assertThatThrownBy(() -> session.accept(response(application())))
        .isInstanceOf(IllegalStateException.class);
    session.first();
    assertThatThrownBy(session::first).isInstanceOf(IllegalStateException.class);
    assertThat(session.accept(response(Map.of("applications", List.of()))).request()).isNull();
    var wrong =
        started()
            .accept(
                response(
                    Map.of(
                        "applications", List.of(Map.of("id", APPLICATION, "publicId", "wrong")))));
    assertThat(wrong.observation().upload()).isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
    bundle =
        new PreparedBundle(
            bundle.sources(),
            bundle.publication(),
            bundle.manifest(),
            bundle.manifestSha256(),
            new Correlation("app", "module", "../version", "run"),
            List.of(),
            0);
    assertThatThrownBy(() -> adapter.validate(context(policy(), proof())))
        .hasMessage("SONATYPE_MAPPING_INVALID");
  }

  @Test
  void boundedVersionPaginationRejectsExistingDuplicateAndChangingSnapshots() {
    var page = IntStream.range(0, 25).mapToObj(i -> Map.of("applicationVersion", "v" + i)).toList();
    AdapterSession session = started();
    session.accept(response(application()));
    var next = session.accept(response(Map.of("totalResultsCount", 26, "results", page)));
    assertThat(next.request().relativePath()).contains("page=2");
    var existing =
        session.accept(
            response(
                Map.of(
                    "totalResultsCount",
                    26,
                    "results",
                    List.of(Map.of("applicationVersion", "1.0")))));
    assertThat(existing.observation().reasonCode()).isEqualTo("SONATYPE_VERSION_EXISTS");
    for (Object malformed :
        List.of(
            Map.of("totalResultsCount", 1001, "results", List.of()),
            Map.of("totalResultsCount", 1, "results", List.of()),
            Map.of("totalResultsCount", 0, "results", List.of(Map.of("applicationVersion", "v1"))),
            Map.of(
                "totalResultsCount",
                2,
                "results",
                List.of(Map.of("applicationVersion", "v1"), Map.of("applicationVersion", "v1"))))) {
      session = started();
      session.accept(response(application()));
      assertThat(session.accept(response(malformed)).request()).isNull();
    }
    session = started();
    session.accept(response(application()));
    session.accept(response(Map.of("totalResultsCount", 26, "results", page)));
    assertThat(
            session
                .accept(response(Map.of("totalResultsCount", 27, "results", List.of())))
                .request())
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://evil.invalid/status",
        "//evil.invalid/status",
        "/api/v2/sbom/status",
        "api/v2/sbom/applications/other/status/secret",
        "api/v2/sbom/applications/c9920b0b240f405a8fc11c897864e2e5/status/../secret"
      })
  void returnedStatusUrlsCannotEscapeApplication(String path) {
    var step = beforeUpload().accept(response(Map.of("statusUrl", path)));
    assertThat(step.request()).isNull();
    assertThat(step.observation().upload()).isEqualTo(AdapterResult.Upload.UNKNOWN);
    assertThat(step.observation().toString()).doesNotContain(path);
  }

  @Test
  void completionFailureUnknownShapeAndForeignDownloadNeverBecomeSuccess() {
    var failed = beforeStatus().accept(response(Map.of("isError", true, "errorMessage", "secret")));
    assertThat(failed.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.FAILED);
    assertThat(failed.observation().toString()).doesNotContain("secret");
    for (String field :
        List.of("downloadUrl", "applicationId", "version", "isError", "errorMessage")) {
      var body = new java.util.LinkedHashMap<>(completed());
      body.put(field, "foreign-secret");
      var step = beforeStatus().accept(response(body));
      assertThat(step.request()).isNull();
      assertThat(step.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
    }
    assertThat(beforeStatus().accept(response(Map.of("pending", true))).request()).isNull();
    AdapterSession session = beforeStatus();
    session.accept(response(completed()));
    assertThat(session.accept(response(Map.of("different", true))).observation().ingestion())
        .isEqualTo(AdapterResult.Ingestion.UNVERIFIED);
  }

  @ParameterizedTest
  @ValueSource(ints = {202, 204})
  void incompleteHttpPollingIsBoundedAndNeverInterpretedAsCompletion(int code) {
    AdapterSession session = beforeStatus();
    for (int i = 0; i < 15; i++) {
      var step = session.accept(new ProtocolResponse(code, new byte[0], null));
      assertThat(step.request().relativePath()).isEqualTo(STATUS);
      assertThat(step.request().mutation()).isFalse();
    }
    var finalStep = session.accept(new ProtocolResponse(code, new byte[0], null));
    assertThat(finalStep.request()).isNull();
    assertThat(finalStep.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.PENDING);
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 401, 403, 409, 429, 500, 503, 0})
  void unsuccessfulResponsesPreserveUploadStageAndNeverRetry(int code) {
    var response =
        new ProtocolResponse(
            code, "secret".getBytes(StandardCharsets.UTF_8), code == 0 ? "TIMEOUT" : null);
    assertThat(started().accept(response).observation().upload())
        .isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
    assertThat(beforeUpload().accept(response).observation().upload())
        .isEqualTo(
            code >= 300 && code < 500
                ? AdapterResult.Upload.REJECTED
                : AdapterResult.Upload.UNKNOWN);
    assertThat(beforeStatus().accept(response).observation().upload())
        .isEqualTo(AdapterResult.Upload.ACCEPTED);
  }
}
