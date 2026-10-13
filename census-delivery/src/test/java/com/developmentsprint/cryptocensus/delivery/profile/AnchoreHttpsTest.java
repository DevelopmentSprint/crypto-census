package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.delivery.anchore.AnchoreAdapter;
import com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
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

/** A TLS protocol fixture is not a live Anchore installation. */
class AnchoreHttpsTest {
  @TempDir static Path certificates;
  static TlsFixture tls;
  @TempDir Path directory;
  HttpsServer server;
  ExecutorService threads;
  byte[] publication;
  Path profile;
  Map<String, Object> config;
  final AtomicInteger mutations = new AtomicInteger();
  final AtomicInteger statusReads = new AtomicInteger();
  final List<String> paths = new CopyOnWriteArrayList<>();
  final List<String> auth = new CopyOnWriteArrayList<>();
  final AtomicReference<String> evidenceAuth = new AtomicReference<>();
  final AtomicReference<byte[]> upload = new AtomicReference<>();
  String scenario = "success";
  final ProfilePlanner planner =
      new ProfilePlanner(new ProfileReader(List.of(new AnchoreAdapter())));

  @BeforeAll
  static void certificates() throws Exception {
    tls = new TlsFixture(certificates);
  }

  @BeforeEach
  void setup() throws Exception {
    server = tls.server();
    threads = Executors.newCachedThreadPool();
    server.setExecutor(threads);
    server.createContext("/", this::handle);
    server.start();
    String root = "https://localhost:" + server.getAddress().getPort();
    config = ProfileFixture.configuration(directory, root + "/prefix/", tls.caBundle());
    publication = Files.readAllBytes(directory.resolve("cbom.json"));
    config.put("adapter", "anchore-enterprise");
    config.put("options", AnchoreAdapterTest.options());
    config.put(
        "authentication",
        Map.of(
            "type", "ENV_BASIC", "usernameName", "ANCHORE_USERNAME", "name", "ANCHORE_PASSWORD"));
    config.put(
        "policy",
        Map.of(
            "capabilityRevision",
            AnchoreAdapter.CAPABILITY_REVISION,
            "acceptedLossCodes",
            new ArrayList<>(AnchoreAdapter.LOSS_CODES),
            "completion",
            "INGESTION_COMPLETED"));
    config.put(
        "retention",
        Map.of(
            "endpoint",
            root + "/evidence",
            "authentication",
            Map.of("type", "ENV", "name", "EVIDENCE_TOKEN")));
    profile = ProfileFixture.write(directory, config);
  }

  @AfterEach
  void stop() {
    server.stop(0);
    threads.shutdownNow();
  }

  private String secret(SecretReference reference) {
    return switch (reference.name()) {
      case "EVIDENCE_TOKEN" -> "evidence-secret";

      case "ANCHORE_PASSWORD" -> SecretReference.encodeBasic("_api_key", "upload-secret");
      default -> throw new AssertionError("Unexpected credential reference");
    };
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().toString();
    paths.add(path);
    if (path.equals("/evidence")) {
      evidenceAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
      byte[] bytes = scenario.equals("retention") ? new byte[] {1} : publication;
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
      exchange.close();
      return;
    }
    auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
    String app = "/prefix/v2/apps/" + AnchoreAdapterTest.APP;
    String version = app + "/versions/" + AnchoreAdapterTest.VERSION;
    Object body;
    int status = 200;
    if (path.equals("/prefix/v2/version")) {
      body = AnchoreAdapterTest.SERVER;
    } else if (path.equals(app)) {
      body = AnchoreAdapterTest.app();
    } else if (path.equals(version)) {
      body = AnchoreAdapterTest.version();
    } else if (path.equals(version + "/assets?name=" + AnchoreAdapterTest.NAME + "&limit=1")) {
      body =
          scenario.equals("collision")
              ? Map.of(
                  "items",
                  List.of(AnchoreAdapterTest.asset()),
                  "pagination",
                  Map.of("item_count", 1))
              : AnchoreAdapterTest.EMPTY;
    } else if (path.equals(app + "/jobs/add-sbom-asset")
        && exchange.getRequestMethod().equals("POST")) {
      mutations.incrementAndGet();
      upload.set(exchange.getRequestBody().readAllBytes());
      if (scenario.equals("ambiguous")) {
        exchange.close();
        return;
      }
      status = scenario.equals("auth") ? 401 : 201;
      body = AnchoreAdapterTest.job("pending");
    } else if (path.equals(app + "/jobs/add-sbom-asset/" + AnchoreAdapterTest.JOB)) {
      statusReads.incrementAndGet();
      body = AnchoreAdapterTest.job(scenario.equals("failed") ? "failed" : "complete");
    } else if (path.equals(version + "/assets/" + AnchoreAdapterTest.ASSET)) {
      body = AnchoreAdapterTest.asset();
    } else {
      status = 404;
      body = Map.of("message", "unexpected path");
    }
    byte[] encoded = JsonSupport.write(body);
    exchange.sendResponseHeaders(status, encoded.length);
    exchange.getResponseBody().write(encoded);
    exchange.close();
  }

  @Test
  void plansOfflineThenUsesBasicScopedToVendorAndBearerForExactRetention() throws Exception {
    PlannedDelivery plan = planner.plan(profile);
    assertThat(paths).isEmpty();
    assertThat(Files.readString(directory.resolve("plan.json")))
        .doesNotContain("ANCHORE_PASSWORD", "ANCHORE_USERNAME", "localhost");
    ProfileReceipt receipt = new ProfileExecutor(planner).execute(plan, this::secret);
    assertThat(receipt.successful()).isTrue();
    assertThat(mutations.get()).isEqualTo(1);
    assertThat(statusReads.get()).isEqualTo(1);
    assertThat(auth)
        .isNotEmpty()
        .containsOnly(
            "Basic "
                + Base64.getEncoder()
                    .encodeToString("_api_key:upload-secret".getBytes(StandardCharsets.UTF_8)));
    assertThat(evidenceAuth.get()).isEqualTo("Bearer evidence-secret");
    assertThat(new String(upload.get(), StandardCharsets.UTF_8))
        .contains(new String(publication, StandardCharsets.UTF_8));
    assertThat(receipt.retention().artifactSha256()).isEqualTo(ProfileFixture.hash(publication));
    assertThat(Files.readString(directory.resolve("receipt.json")))
        .contains("RETRIEVED_EXACT_BYTES", "UNVERIFIED", AnchoreAdapterTest.JOB)
        .doesNotContain("upload-secret", "evidence-secret", "_api_key", "localhost");
  }

  @Test
  void preflightDoesNotSubmit() throws Exception {
    ProfileReceipt receipt =
        new ProfileExecutor(planner).preflight(planner.plan(profile), this::secret);
    assertThat(receipt.observation().reasonCode()).isEqualTo("REMOTE_PREFLIGHT_COMPLETE");
    assertThat(mutations.get()).isZero();
    assertThat(statusReads.get()).isZero();
    assertThat(paths).hasSize(5);
  }

  @ParameterizedTest
  @ValueSource(strings = {"collision", "retention", "auth", "failed", "ambiguous"})
  void partialFailuresNeverRetryOrBecomeSuccess(String failure) throws Exception {
    scenario = failure;
    ProfileReceipt receipt =
        new ProfileExecutor(planner).execute(planner.plan(profile), this::secret);
    assertThat(receipt.successful()).isFalse();
    assertThat(mutations.get())
        .isEqualTo(failure.equals("collision") || failure.equals("retention") ? 0 : 1);
    if (failure.equals("ambiguous")) {
      assertThat(receipt.observation().upload()).isEqualTo(AdapterResult.Upload.UNKNOWN);
    }
    if (failure.equals("failed")) {
      assertThat(receipt.observation().ingestion()).isEqualTo(AdapterResult.Ingestion.FAILED);
    }
  }

  @Test
  void missingRetentionAndFullFidelityRequirementsFailOffline() throws Exception {
    config.remove("retention");
    ProfileFixture.write(directory, config);
    assertThatThrownBy(() -> planner.plan(profile)).isInstanceOf(IllegalArgumentException.class);
    assertThat(paths).isEmpty();
  }
}
