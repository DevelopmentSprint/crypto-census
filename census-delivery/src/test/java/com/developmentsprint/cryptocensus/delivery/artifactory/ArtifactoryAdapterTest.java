package com.developmentsprint.cryptocensus.delivery.artifactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.delivery.profile.AdapterContext;
import com.developmentsprint.cryptocensus.delivery.profile.AdapterResult;
import com.developmentsprint.cryptocensus.delivery.profile.ProfileExecutor;
import com.developmentsprint.cryptocensus.delivery.profile.ProfilePlanner;
import com.developmentsprint.cryptocensus.delivery.profile.ProfileReader;
import com.developmentsprint.cryptocensus.delivery.profile.ProtocolResponse;
import com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpsServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

class ArtifactoryAdapterTest {
  @TempDir static Path certificates;
  static TlsFixture tls;
  @TempDir Path directory;
  HttpsServer server;
  ExecutorService threads;
  Map<String, Object> config;
  Path profile;
  final List<String> requests = new CopyOnWriteArrayList<>();
  final List<String> authorization = new CopyOnWriteArrayList<>();
  final AtomicReference<byte[]> submitted = new AtomicReference<>();
  final AtomicReference<byte[]> returned = new AtomicReference<>();
  final AtomicInteger checkStatus = new AtomicInteger(404);
  final AtomicInteger putStatus = new AtomicInteger(201);
  final AtomicInteger getStatus = new AtomicInteger(200);
  final ProfilePlanner planner =
      new ProfilePlanner(new ProfileReader(List.of(new ArtifactoryAdapter())));

  @BeforeAll
  static void certificates() throws Exception {
    tls = new TlsFixture(certificates);
  }

  @BeforeEach
  void setup() throws Exception {
    server = tls.server();
    threads = Executors.newCachedThreadPool();
    server.setExecutor(threads);
    server.createContext(
        "/artifactory/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          String method = exchange.getRequestMethod();
          requests.add(method + " " + path);
          authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
          int status;
          byte[] body = new byte[0];
          if (path.startsWith("/artifactory/api/storage/")) {
            status = checkStatus.get();
          } else if ("PUT".equals(method)) {
            submitted.set(exchange.getRequestBody().readAllBytes());
            status = putStatus.get();
            if (status == 0) {
              exchange.close();
              return;
            }
          } else {
            status = getStatus.get();
            body = returned.get() == null ? submitted.get() : returned.get();
          }
          if (status == 302) {
            exchange.getResponseHeaders().add("Location", "/escaped");
          }
          exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
          if (body.length != 0) {
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
    config =
        ProfileFixture.configuration(
            directory,
            "https://localhost:" + server.getAddress().getPort() + "/artifactory",
            tls.caBundle());
    config.put("adapter", "artifactory");
    config.put("options", options("crypto/evidence"));
    config.put(
        "policy",
        Map.of(
            "capabilityRevision",
            ArtifactoryAdapter.CAPABILITY_REVISION,
            "acceptedLossCodes",
            List.of(),
            "completion",
            "UPLOAD_ACCEPTED"));
    profile = ProfileFixture.write(directory, config);
  }

  @AfterEach
  void cleanup() {
    server.stop(0);
    threads.shutdownNow();
  }

  @Test
  void exactStorageAuthenticatesEveryStepAndLeavesSourceUnchanged() throws Exception {
    byte[] original = Files.readAllBytes(directory.resolve("cbom.json"));
    var plan = planner.plan(profile);
    assertThat(requests).isEmpty();
    var receipt = new ProfileExecutor(planner).execute(plan, reference -> "private-token");
    assertThat(receipt.successful()).isTrue();
    assertThat(receipt.observation().reasonCode()).isEqualTo("ARTIFACTORY_STORED_EXACT_BYTES");
    assertThat(receipt.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.NOT_APPLICABLE);
    assertThat(submitted.get()).isEqualTo(original);
    assertThat(Files.readAllBytes(directory.resolve("cbom.json"))).isEqualTo(original);
    String runDigest =
        ProfileFixture.hash(
            com.developmentsprint.cryptocensus.delivery.profile.JsonSupport.write(
                List.of("census.artifactory-path/1", "app", "example:module", "1.0", "run-1")));
    String path =
        "/artifactory/cbom-local/crypto/evidence/"
            + runDigest
            + "/"
            + ProfileFixture.hash(original)
            + ".json";
    assertThat(requests)
        .containsExactly(
            "GET /artifactory/api/storage/" + path.substring(13), "PUT " + path, "GET " + path);
    assertThat(authorization)
        .containsExactly("Bearer private-token", "Bearer private-token", "Bearer private-token");
    assertThat(Files.readString(directory.resolve("receipt.json")))
        .contains("ARTIFACTORY_STORED_EXACT_BYTES", "NOT_APPLICABLE")
        .doesNotContain("private-token", "localhost", "INGESTION_COMPLETED");
  }

  @Test
  void preflightOnlyChecksExistingPathWithoutUploading() throws Exception {
    var receipt =
        new ProfileExecutor(planner).preflight(planner.plan(profile), reference -> "token");
    assertThat(receipt.observation().reasonCode()).isEqualTo("REMOTE_PREFLIGHT_COMPLETE");
    assertThat(requests).hasSize(1);
    assertThat(submitted.get()).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 201, 302, 401, 403, 429, 500})
  void collisionOrUnconfirmedAbsenceNeverUploads(int status) throws Exception {
    checkStatus.set(status);
    var receipt = new ProfileExecutor(planner).execute(planner.plan(profile), reference -> "token");
    assertThat(receipt.successful()).isFalse();
    assertThat(receipt.observation().upload()).isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
    assertThat(requests).hasSize(1);
    assertThat(submitted.get()).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 202, 302, 401, 403, 409, 429, 500, 0})
  void rejectionOrAmbiguousUploadNeverRetries(int status) throws Exception {
    putStatus.set(status);
    var receipt = new ProfileExecutor(planner).execute(planner.plan(profile), reference -> "token");
    assertThat(receipt.successful()).isFalse();
    assertThat(receipt.observation().upload())
        .isEqualTo(
            status >= 300 && status < 500
                ? AdapterResult.Upload.REJECTED
                : AdapterResult.Upload.UNKNOWN);
    assertThat(requests).hasSize(2);
  }

  @ParameterizedTest
  @ValueSource(ints = {302, 403, 404, 500})
  void readbackFailureRetainsAcknowledgmentButDoesNotSucceed(int status) throws Exception {
    getStatus.set(status);
    var receipt = new ProfileExecutor(planner).execute(planner.plan(profile), reference -> "token");
    assertThat(receipt.successful()).isFalse();
    assertThat(receipt.observation().upload()).isEqualTo(AdapterResult.Upload.ACCEPTED);
    assertThat(requests).hasSize(3);
  }

  @Test
  void wrongBytesFailEvenAfterAcknowledgment() throws Exception {
    returned.set("wrong-private-token".getBytes(StandardCharsets.UTF_8));
    var receipt =
        new ProfileExecutor(planner).execute(planner.plan(profile), reference -> "private-token");
    assertThat(receipt.successful()).isFalse();
    assertThat(receipt.observation().upload()).isEqualTo(AdapterResult.Upload.ACCEPTED);
    assertThat(Files.readString(directory.resolve("receipt.json"))).doesNotContain("private-token");
  }

  @Test
  void readbackLargerThanMetadataLimitStillVerifiesExactBytes() throws Exception {
    Path artifact = directory.resolve("cbom.json");
    Files.writeString(artifact, Files.readString(artifact) + " ".repeat(70000));
    var inputs = (List<?>) config.get("inputs");
    var input =
        new java.util.LinkedHashMap<>(
            com.developmentsprint.cryptocensus.delivery.profile.JsonSupport.object(inputs.get(0)));
    input.put("sha256", ProfileFixture.hash(Files.readAllBytes(artifact)));
    config.put("inputs", List.of(input));
    ProfileFixture.write(directory, config);
    var receipt = new ProfileExecutor(planner).execute(planner.plan(profile), reference -> "token");
    assertThat(receipt.successful()).isTrue();
    assertThat(submitted.get()).hasSizeGreaterThan(65536);
    assertThat(requests).hasSize(3);
  }

  @Test
  void oversizedReadbackNeverSucceedsOrRetries() throws Exception {
    returned.set(new byte[16 * 1024 * 1024 + 1]);
    var receipt = new ProfileExecutor(planner).execute(planner.plan(profile), reference -> "token");
    assertThat(receipt.successful()).isFalse();
    assertThat(receipt.observation().upload()).isEqualTo(AdapterResult.Upload.ACCEPTED);
    assertThat(requests).hasSize(3);
  }

  @Test
  void changedSourceStopsBeforeNetwork() throws Exception {
    var plan = planner.plan(profile);
    Files.writeString(directory.resolve("cbom.json"), "changed");
    assertThatThrownBy(() -> new ProfileExecutor(planner).execute(plan, reference -> "token"))
        .isInstanceOf(Exception.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void digestMismatchAndInvalidSessionSequenceFailClosed() throws Exception {
    var plan = planner.plan(profile);
    var context =
        new AdapterContext(
            plan.bundle(),
            plan.profile().endpoint(),
            plan.profile().options(),
            plan.profile().policy(),
            null);
    var adapter = new ArtifactoryAdapter();
    assertThatThrownBy(() -> adapter.open(context, new byte[] {1}))
        .hasMessage("ARTIFACTORY_PUBLICATION_DIGEST_MISMATCH");
    var session = adapter.open(context, plan.bundle().publication().readVerified());
    assertThatThrownBy(() -> session.accept(new ProtocolResponse(404, new byte[0], null)))
        .hasMessage("ARTIFACTORY_SESSION_SEQUENCE_INVALID");
    session.first();
    assertThatThrownBy(session::first).hasMessage("ARTIFACTORY_SESSION_SEQUENCE_INVALID");
    session.accept(new ProtocolResponse(200, new byte[0], null));
    assertThatThrownBy(() -> session.accept(new ProtocolResponse(200, new byte[0], null)))
        .hasMessage("ARTIFACTORY_SESSION_SEQUENCE_INVALID");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../escape",
        "/escape",
        "a/",
        "a//b",
        "a/../b",
        "a?x",
        "a%2fb",
        "",
        "a;property=value"
      })
  void unsafePrefixesRejectBeforeNetwork(String prefix) throws Exception {
    config.put("options", options(prefix));
    ProfileFixture.write(directory, config);
    assertThatThrownBy(() -> planner.plan(profile)).isInstanceOf(IllegalArgumentException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void anonymousAuthenticationCannotSatisfyRestrictedPrincipalContract() throws Exception {
    config.put("authentication", Map.of("type", "NONE"));
    ProfileFixture.write(directory, config);
    assertThatThrownBy(() -> planner.plan(profile)).isInstanceOf(IllegalArgumentException.class);
    assertThat(requests).isEmpty();
  }

  @Test
  void overwritePermissionOrIngestionClaimCannotBeRequested() throws Exception {
    assertThatThrownBy(() -> new ArtifactoryOptions("cbom-local", "evidence", "ADMIN"))
        .hasMessage("ARTIFACTORY_CONFIGURATION_INVALID");
    assertThatThrownBy(
            () -> new ArtifactoryOptions("../bad", "evidence", "READ_DEPLOY_NO_OVERWRITE"))
        .hasMessage("ARTIFACTORY_CONFIGURATION_INVALID");
    assertThatThrownBy(() -> new ArtifactoryAdapter().parseOptions(Map.of("extra", "value")))
        .isInstanceOf(IllegalArgumentException.class);
    config.put(
        "policy",
        Map.of(
            "capabilityRevision",
            ArtifactoryAdapter.CAPABILITY_REVISION,
            "acceptedLossCodes",
            List.of(),
            "completion",
            "INGESTION_COMPLETED"));
    ProfileFixture.write(directory, config);
    assertThatThrownBy(() -> planner.plan(profile))
        .hasMessage("ARTIFACTORY_STORAGE_POLICY_REQUIRED");
    assertThat(requests).isEmpty();
  }

  private static Map<String, Object> options(String prefix) {
    return Map.of(
        "repository",
        "cbom-local",
        "pathPrefix",
        prefix,
        "deploymentPermission",
        "READ_DEPLOY_NO_OVERWRITE");
  }
}
