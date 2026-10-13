package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.delivery.sonatype.SonatypeSbomManagerAdapter;
import com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpsServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Vendor contract fixtures over real HTTPS; these do not certify a commercial tenant. */
class SonatypeHttpsTest {
  @TempDir static Path certificates;
  static TlsFixture tls;
  @TempDir Path directory;
  HttpsServer server;
  ExecutorService threads;
  Path profile;
  Map<String, Object> config;
  byte[] original;
  final List<String> paths = new CopyOnWriteArrayList<>();
  final List<String> auth = new CopyOnWriteArrayList<>();
  final AtomicInteger uploads = new AtomicInteger();
  final AtomicInteger polls = new AtomicInteger();
  final AtomicInteger uploadCode = new AtomicInteger(200);
  final AtomicReference<byte[]> uploadResponse = new AtomicReference<>();
  final AtomicReference<byte[]> statusResponse = new AtomicReference<>();
  final AtomicReference<byte[]> returnedOriginal = new AtomicReference<>();
  final AtomicReference<byte[]> retainedOriginal = new AtomicReference<>();
  final AtomicReference<byte[]> submitted = new AtomicReference<>();
  final AtomicInteger incompletePolls = new AtomicInteger();
  ProfilePlanner planner;

  @BeforeAll
  static void certificate() throws Exception {
    tls = new TlsFixture(certificates);
  }

  @BeforeEach
  void setup() throws Exception {
    server = tls.server();
    threads = Executors.newCachedThreadPool();
    server.setExecutor(threads);
    server.start();
    String origin = "https://localhost:" + server.getAddress().getPort();
    config = ProfileFixture.configuration(directory, origin + "/platform/", tls.caBundle());
    original = Files.readAllBytes(directory.resolve("cbom.json"));
    returnedOriginal.set(original);
    retainedOriginal.set(original);
    config.put("adapter", "sonatype-sbom-manager");
    config.put("options", SonatypeAdapterTest.options());
    config.put(
        "authentication",
        Map.of(
            "type",
            "ENV_BASIC",
            "name",
            "SONATYPE_TOKEN_PASSWORD",
            "usernameName",
            "SONATYPE_USER_TOKEN"));
    config.put(
        "policy",
        Map.of(
            "capabilityRevision",
            SonatypeSbomManagerAdapter.CAPABILITY_REVISION,
            "acceptedLossCodes",
            new ArrayList<>(SonatypeSbomManagerAdapter.LOSS_CODES),
            "completion",
            "INGESTION_COMPLETED"));
    config.put(
        "retention",
        Map.of(
            "endpoint",
            origin + "/evidence",
            "authentication",
            Map.of("type", "ENV", "name", "EVIDENCE_TOKEN")));
    profile = ProfileFixture.write(directory, config);
    planner = new ProfilePlanner(new ProfileReader(List.of(new SonatypeSbomManagerAdapter())));
    uploadResponse.set(JsonSupport.write(Map.of("statusUrl", SonatypeAdapterTest.STATUS)));
    statusResponse.set(JsonSupport.write(SonatypeAdapterTest.completed()));
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().toString();
          paths.add(path);
          auth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          byte[] body;
          int status = 200;
          if (path.equals("/evidence")) {
            body = retainedOriginal.get();
          } else if (path.equals("/platform/api/v2/applications?publicId=app")) {
            body = JsonSupport.write(SonatypeAdapterTest.application());
          } else if (path.equals(
              "/platform/api/v2/sbom/applications/"
                  + SonatypeAdapterTest.APPLICATION
                  + "?page=1&pageSize=25&sortByDate=asc")) {
            body = JsonSupport.write(Map.of("totalResultsCount", 0, "results", List.of()));
          } else if (path.equals("/platform/api/v2/sbom/import?ignoreValidationError=false")) {
            uploads.incrementAndGet();
            submitted.set(exchange.getRequestBody().readAllBytes());
            body = uploadResponse.get();
            status = uploadCode.get();
            if (status == 0) {
              exchange.close();
              return;
            }
          } else if (path.equals("/platform/" + SonatypeAdapterTest.STATUS)) {
            status = polls.getAndIncrement() < incompletePolls.get() ? 202 : 200;
            body = statusResponse.get();
          } else if (path.equals("/platform/" + SonatypeAdapterTest.DOWNLOAD)) {
            body = returnedOriginal.get();
          } else {
            body = new byte[0];
            status = 404;
          }
          exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
  }

  @AfterEach
  void cleanup() {
    server.stop(0);
    threads.shutdownNow();
  }

  String resolve(SecretReference reference) {
    return "EVIDENCE_TOKEN".equals(reference.name())
        ? "retention-secret"
        : SecretReference.encodeBasic("test-user", "test-password");
  }

  ProfileReceipt publish() throws Exception {
    return new ProfileExecutor(planner).execute(planner.plan(profile), this::resolve);
  }

  @Test
  void boundedAsyncCompletionRequiresBothIndependentAndDestinationByteProof() throws Exception {
    incompletePolls.set(1);
    var result = publish();
    assertThat(result.successful()).isTrue();
    assertThat(result.retention().artifactSha256()).isEqualTo(ProfileFixture.hash(original));
    assertThat(result.destinationReadback().artifactSha256())
        .isEqualTo(ProfileFixture.hash(original));
    assertThat(result.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.COMPLETED);
    assertThat(uploads.get()).isEqualTo(1);
    assertThat(polls.get()).isEqualTo(2);
    assertThat(auth.get(0)).isEqualTo("Bearer retention-secret");
    assertThat(auth.subList(1, auth.size()))
        .containsOnly("Basic " + resolve(new SecretReference(SecretReference.Kind.NONE, null)));
    assertThat(new String(submitted.get(), StandardCharsets.UTF_8))
        .contains(new String(original, StandardCharsets.UTF_8));
    assertThat(Files.readString(directory.resolve("receipt.json")))
        .contains("SONATYPE_SEMANTICS_UNVERIFIED", "RETRIEVED_EXACT_BYTES")
        .doesNotContain(
            "test-password",
            "test-user",
            "retention-secret",
            resolve(new SecretReference(SecretReference.Kind.NONE, null)));
  }

  @Test
  void remotePreflightReadsOnlyAndHasNoUploadOrReadbackClaim() throws Exception {
    var result = new ProfileExecutor(planner).preflight(planner.plan(profile), this::resolve);
    assertThat(result.observation().reasonCode()).isEqualTo("REMOTE_PREFLIGHT_COMPLETE");
    assertThat(result.observation().upload()).isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
    assertThat(uploads.get()).isZero();
    assertThat(paths).hasSize(3);
  }

  @Test
  void wrongIndependentOriginalPreventsAllVendorTraffic() throws Exception {
    retainedOriginal.set("wrong".getBytes(StandardCharsets.UTF_8));
    assertThat(publish().successful()).isFalse();
    assertThat(paths).containsExactly("/evidence");
    assertThat(uploads.get()).isZero();
  }

  @Test
  void changedDestinationOriginalCannotPassCompletion() throws Exception {
    returnedOriginal.set("changed".getBytes(StandardCharsets.UTF_8));
    assertThat(publish().successful()).isFalse();
    assertThat(uploads.get()).isEqualTo(1);
  }

  @Test
  void asynchronousFailureStaysSeparateFromUploadAcknowledgment() throws Exception {
    statusResponse.set(JsonSupport.write(Map.of("isError", true, "errorMessage", "test-password")));
    var receipt = publish();
    assertThat(receipt.successful()).isFalse();
    assertThat(receipt.observation().upload()).isEqualTo(AdapterResult.Upload.ACCEPTED);
    assertThat(receipt.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.FAILED);
    assertThat(Files.readString(directory.resolve("receipt.json"))).doesNotContain("test-password");
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 401, 403, 409, 429, 500, 503})
  void failedUploadIsNeverRepeatedAndResponsesAreNotEchoed(int code) throws Exception {
    uploadCode.set(code);
    uploadResponse.set("test-password".getBytes(StandardCharsets.UTF_8));
    var receipt = publish();
    assertThat(receipt.successful()).isFalse();
    assertThat(uploads.get()).isEqualTo(1);
    assertThat(receipt.observation().upload())
        .isEqualTo(code < 500 ? AdapterResult.Upload.REJECTED : AdapterResult.Upload.UNKNOWN);
    assertThat(polls.get()).isZero();
    assertThat(Files.readString(directory.resolve("receipt.json"))).doesNotContain("test-password");
  }

  @Test
  void hostileReturnUrlIsRejectedBeforeAnyFollowup() throws Exception {
    uploadResponse.set(
        JsonSupport.write(Map.of("statusUrl", "https://evil.invalid/credential-trap")));
    var receipt = publish();
    assertThat(receipt.successful()).isFalse();
    assertThat(paths).hasSize(4);
    assertThat(receipt.observation().upload()).isEqualTo(AdapterResult.Upload.UNKNOWN);
  }

  @Test
  void publicationReadbackExceedsControlResponseLimitWithoutTruncation() throws Exception {
    original =
        (" ".repeat(70000) + new String(original, StandardCharsets.UTF_8))
            .getBytes(StandardCharsets.UTF_8);
    Files.write(directory.resolve("cbom.json"), original);
    returnedOriginal.set(original);
    retainedOriginal.set(original);
    List<?> inputs = (List<?>) config.get("inputs");
    Map<String, Object> input = new java.util.LinkedHashMap<>(JsonSupport.object(inputs.get(0)));
    input.put("sha256", ProfileFixture.hash(original));
    config.put("inputs", List.of(input));
    ProfileFixture.write(directory, config);
    assertThat(publish().successful()).isTrue();
  }

  @Test
  void lostUploadResponseIsAmbiguousAndCannotCauseReplay() throws Exception {
    uploadCode.set(0);
    var receipt = publish();
    assertThat(receipt.successful()).isFalse();
    assertThat(receipt.observation().upload()).isEqualTo(AdapterResult.Upload.UNKNOWN);
    assertThat(uploads.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {201, 202})
  void non200UploadAcknowledgmentStillRequiresDocumentedScopedTicket(int code) throws Exception {
    uploadCode.set(code);
    assertThat(publish().successful()).isTrue();
    assertThat(uploads.get()).isEqualTo(1);
  }
}
