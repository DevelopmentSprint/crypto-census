package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.delivery.dependencytrack.DependencyTrackAdapter;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Opt-in deployed-server smoke test. The production executor uses HTTPS and an ephemeral trusted
 * CA; the test-only reverse proxy reaches a separately selected local Docker API. Existing samples
 * are never mutated. All newly provisioned projects and the temporary team are removed after each
 * test.
 */
@EnabledIfEnvironmentVariable(
    named = "CENSUS_DT_LIVE_URL",
    matches = "http://127\\.0\\.0\\.1:[0-9]+")
class DependencyTrackLiveTest {
  @TempDir Path directory;
  private final HttpClient nativeClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final List<String> projects = new ArrayList<>();
  private final AtomicInteger uploads = new AtomicInteger();
  private final AtomicInteger readbacks = new AtomicInteger();
  private URI nativeBase;
  private String administrator;
  private String team;
  private String apiKey;
  private String owner;
  private String project;
  private byte[] original;
  private String sha256;
  private HttpsServer proxy;
  private ExecutorService executor;
  private TlsFixture tls;
  private String endpoint;

  @BeforeEach
  void provisionOwnedTestResources() throws Exception {
    nativeBase = URI.create(System.getenv("CENSUS_DT_LIVE_URL"));
    Map<String, Object> login =
        JsonSupport.object(
            JsonSupport.parse(Files.readAllBytes(Path.of(System.getenv("CENSUS_DT_LOGIN_FILE")))));
    assertThat(login.get("baseUrl")).isEqualTo(nativeBase.toString());
    String form =
        "username="
            + encode(JsonSupport.string(login, "username"))
            + "&password="
            + encode(JsonSupport.string(login, "password"));
    HttpResponse<byte[]> loggedIn =
        nativeRequest(
            "POST",
            "api/v1/user/login",
            form.getBytes(StandardCharsets.UTF_8),
            "application/x-www-form-urlencoded",
            null,
            null);
    assertThat(loggedIn.statusCode()).isEqualTo(200);
    administrator = new String(loggedIn.body(), StandardCharsets.UTF_8);
    owner = "census-test-" + UUID.randomUUID();
    team = JsonSupport.string(adminJson("PUT", "api/v1/team", Map.of("name", owner)), "uuid");
    for (String permission : List.of("BOM_UPLOAD", "VIEW_PORTFOLIO", "PORTFOLIO_MANAGEMENT_READ")) {
      adminJson("POST", "api/v1/permission/" + permission + "/team/" + team, null);
    }
    apiKey = JsonSupport.string(adminJson("PUT", "api/v1/team/" + team + "/key", null), "key");
    project =
        JsonSupport.string(
            adminJson(
                "PUT",
                "api/v1/project",
                Map.of("name", owner, "version", "0.1.0", "classifier", "APPLICATION")),
            "uuid");
    projects.add(project);
    adminJson(
        "PUT",
        "api/v1/project/" + project + "/property",
        Map.of(
            "groupName",
            "cryptocensus",
            "propertyName",
            "runtime.owner",
            "propertyType",
            "STRING",
            "propertyValue",
            owner));
    original = Files.readAllBytes(Path.of(System.getenv("CENSUS_DT_CBOM_PATH")));
    sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
    tls = new TlsFixture(directory);
    proxy = tls.server();
    executor = Executors.newCachedThreadPool();
    proxy.setExecutor(executor);
    proxy.createContext("/", this::forward);
    proxy.start();
    endpoint = "https://localhost:" + proxy.getAddress().getPort() + "/";
  }

  @AfterEach
  void removeOnlyOwnedResources() throws Exception {
    if (proxy != null) {
      proxy.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
    for (String id : projects) {
      adminJson("DELETE", "api/v1/project/" + id, null);
    }
    if (team != null) {
      adminJson("DELETE", "api/v1/team", Map.of("uuid", team));
    }
  }

  @Test
  void productionExecutorUsesVerifiedHttpsOriginalThenNativeCompletedImport() throws Exception {
    ProfilePlanner planner =
        new ProfilePlanner(new ProfileReader(List.of(new DependencyTrackAdapter())));
    PlannedDelivery plan = planner.plan(profile("success"));
    assertThat(uploads).hasValue(0);
    assertThat(readbacks).hasValue(0);
    ProfileExecutor engine = new ProfileExecutor(planner);
    ProfileReceipt preflight = engine.preflight(plan, this::secret);
    assertThat(preflight.observation().reasonCode()).isEqualTo("REMOTE_PREFLIGHT_COMPLETE");
    assertThat(uploads).hasValue(0);
    ProfileReceipt receipt = engine.execute(plan, this::secret);
    assertThat(receipt.successful()).isTrue();
    assertThat(receipt.observation().upload()).isEqualTo(AdapterResult.Upload.ACCEPTED);
    assertThat(receipt.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.COMPLETED);
    assertThat(receipt.observation().serverVersion()).isEqualTo("5.2.0");
    assertThat(receipt.retention().artifactSha256()).isEqualTo(sha256);
    assertThat(receipt.observation().lossCodes())
        .containsExactlyInAnyOrderElementsOf(DependencyTrackAdapter.LOSS_CODES);
    assertThat(uploads).hasValue(1);
    assertThat(readbacks).hasValue(2);
    assertThat(plan.bundle().publication().readVerified()).isEqualTo(original);
    Object inventory =
        JsonSupport.parse(
            nativeRequest(
                    "GET",
                    "api/v1/component/project/" + project,
                    new byte[0],
                    null,
                    "Authorization",
                    "Bearer " + administrator)
                .body());
    assertThat(inventory).isInstanceOf(List.class);
    assertThat((List<?>) inventory).hasSize(5);
    assertThat(Files.readString(plan.profile().receiptPath()))
        .doesNotContain(apiKey, administrator, "test-evidence-secret");
  }

  @Test
  void existingSoftwareInventoryRemainsAfterGuardRejectsEvenWithOwnershipMarker() throws Exception {
    byte[] software =
        JsonSupport.write(
            Map.of(
                "bomFormat",
                "CycloneDX",
                "specVersion",
                "1.6",
                "version",
                1,
                "components",
                List.of(
                    Map.of(
                        "type",
                        "library",
                        "name",
                        "synthetic-existing-library",
                        "version",
                        "1.0",
                        "bom-ref",
                        "library-1"))));
    Map<String, Object> uploaded =
        adminJson(
            "PUT",
            "api/v1/bom",
            Map.of("project", project, "bom", Base64.getEncoder().encodeToString(software)));
    boolean completed = false;
    for (int i = 0; i < 100; i++) {
      Map<String, Object> status =
          adminJson("GET", "api/v1/event/token/" + uploaded.get("token"), null);
      if ("COMPLETED".equals(status.get("status"))) {
        completed = true;
        break;
      }
      Thread.sleep(100);
    }
    assertThat(completed).as("Synthetic software seed completed before refusal check").isTrue();
    ProfilePlanner planner =
        new ProfilePlanner(new ProfileReader(List.of(new DependencyTrackAdapter())));
    ProfileReceipt receipt =
        new ProfileExecutor(planner).execute(planner.plan(profile("refusal")), this::secret);
    assertThat(receipt.successful()).isFalse();
    assertThat(receipt.observation().upload()).isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
    assertThat(receipt.observation().reasonCode())
        .isEqualTo("DT_SOFTWARE_INVENTORY_REPLACEMENT_FORBIDDEN");
    assertThat(uploads).hasValue(0);
    byte[] inventory =
        nativeRequest(
                "GET",
                "api/v1/component/project/" + project,
                new byte[0],
                null,
                "Authorization",
                "Bearer " + administrator)
            .body();
    assertThat(new String(inventory, StandardCharsets.UTF_8))
        .contains("synthetic-existing-library");
  }

  private Path profile(String name) throws IOException {
    Path input = directory.resolve(name + "-input.json");
    Files.write(input, original);
    Map<String, Object> profile =
        Map.ofEntries(
            Map.entry("schema", "census.delivery-profile/1"),
            Map.entry("adapter", "dependency-track"),
            Map.entry("endpoint", endpoint),
            Map.entry("destinationId", "local-dt-test"),
            Map.entry(
                "preparation", Map.of("mode", "IDENTITY", "outputDirectory", name + "-prepared")),
            Map.entry(
                "inputs",
                List.of(
                    Map.of(
                        "path",
                        input.toString(),
                        "sha256",
                        sha256,
                        "kind",
                        "CENSUS_MERGED_CBOM",
                        "correlation",
                        Map.of(
                            "application",
                            "census-demo",
                            "module",
                            "com.developmentsprint:census-demo",
                            "version",
                            "0.1.0",
                            "runId",
                            owner)))),
            Map.entry(
                "options",
                Map.of(
                    "projectUuid",
                    project,
                    "ownerId",
                    owner,
                    "replacementPolicy",
                    "OWNED_RUNTIME_SNAPSHOT")),
            Map.entry("authentication", Map.of("type", "ENV", "name", "DT_TEST_KEY")),
            Map.entry("transport", Map.of("caBundle", tls.caBundle().toString())),
            Map.entry(
                "policy",
                Map.of(
                    "capabilityRevision",
                    DependencyTrackAdapter.CAPABILITY_REVISION,
                    "acceptedLossCodes",
                    DependencyTrackAdapter.LOSS_CODES.stream().sorted().toList(),
                    "completion",
                    "INGESTION_COMPLETED",
                    "requireRemoteRetention",
                    true)),
            Map.entry(
                "retention",
                Map.of(
                    "endpoint",
                    endpoint + "original/cbom.json",
                    "authentication",
                    Map.of("type", "ENV", "name", "EVIDENCE_TEST_TOKEN"))),
            Map.entry(
                "outputs", Map.of("plan", name + "-plan.json", "receipt", name + "-receipt.json")));
    Path path = directory.resolve(name + "-profile.json");
    Files.write(path, JsonSupport.write(profile));
    return path;
  }

  private String secret(SecretReference reference) {
    return switch (reference.name()) {
      case "DT_TEST_KEY" -> apiKey;
      case "EVIDENCE_TEST_TOKEN" -> "test-evidence-secret";
      default -> throw new IllegalArgumentException("Unexpected test secret reference");
    };
  }

  private void forward(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().toString();
      if ("/original/cbom.json".equals(path)) {
        if (!"Bearer test-evidence-secret"
            .equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
          exchange.sendResponseHeaders(401, -1);
          return;
        }
        readbacks.incrementAndGet();
        exchange.sendResponseHeaders(200, original.length);
        exchange.getResponseBody().write(original);
        return;
      }
      if (!path.startsWith("/api/")) {
        exchange.sendResponseHeaders(404, -1);
        return;
      }
      if ("POST".equals(exchange.getRequestMethod()) && "/api/v1/bom".equals(path)) {
        uploads.incrementAndGet();
      }
      try {
        HttpResponse<byte[]> response =
            nativeRequest(
                exchange.getRequestMethod(),
                path.substring(1),
                exchange.getRequestBody().readAllBytes(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                "X-Api-Key",
                exchange.getRequestHeaders().getFirst("X-Api-Key"));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.statusCode(), response.body().length);
        exchange.getResponseBody().write(response.body());
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        exchange.sendResponseHeaders(503, -1);
      }
    }
  }

  private Map<String, Object> adminJson(String method, String path, Object value) throws Exception {
    HttpResponse<byte[]> response =
        nativeRequest(
            method,
            path,
            value == null ? new byte[0] : JsonSupport.write(value),
            value == null ? null : "application/json",
            "Authorization",
            "Bearer " + administrator);
    assertThat(response.statusCode()).isBetween(200, 299);
    if (response.body().length == 0 || response.body()[0] != '{') {
      return Map.of();
    }
    return JsonSupport.object(JsonSupport.parse(response.body()));
  }

  @SuppressWarnings(
      "PMD.CloseResource") // Java17 HttpClient lifecycle is owned by this test instance.
  private HttpResponse<byte[]> nativeRequest(
      String method, String path, byte[] body, String contentType, String header, String value)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(nativeBase.resolve("/" + path)).timeout(Duration.ofSeconds(15));
    if (header != null && value != null) {
      request.header(header, value);
    }
    if (contentType != null) {
      request.header("Content-Type", contentType);
    }
    return nativeClient.send(
        request.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
