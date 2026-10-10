package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.delivery.ReceiptWriteException;
import com.developmentsprint.cryptocensus.delivery.testing.ProfileFixture;
import com.developmentsprint.cryptocensus.delivery.testing.TlsFixture;
import com.sun.net.httpserver.HttpsServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

class ProfileFlowTest {
  @TempDir static Path certificates;
  static TlsFixture tls;
  @TempDir Path directory;
  HttpsServer server;
  ExecutorService threads;
  String endpoint;
  Map<String, Object> config;
  Path profile;
  final AtomicInteger requests = new AtomicInteger();
  final AtomicReference<byte[]> submitted = new AtomicReference<>();
  final AtomicReference<String> authorization = new AtomicReference<>();
  final AtomicInteger status = new AtomicInteger(202);
  final AtomicReference<byte[]> response =
      new AtomicReference<>("accepted".getBytes(StandardCharsets.UTF_8));

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
        "/upload",
        exchange -> {
          requests.incrementAndGet();
          submitted.set(exchange.getRequestBody().readAllBytes());
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] bytes = response.get();
          exchange.sendResponseHeaders(status.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    endpoint = "https://localhost:" + server.getAddress().getPort() + "/upload";
    config = ProfileFixture.configuration(directory, endpoint, tls.caBundle());
    profile = ProfileFixture.write(directory, config);
  }

  @AfterEach
  void cleanup() {
    server.stop(0);
    threads.shutdownNow();
    Thread.interrupted();
  }

  @Test
  void persistedPlanLoadsInFreshJvmsWithIndependentMapHashSeeds() throws Exception {
    var plan =
        ProfileCommand.run(
            "plan",
            profile,
            null,
            reference -> {
              throw new AssertionError();
            });
    String javaName = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    String javaExecutable = Path.of(System.getProperty("java.home"), "bin", javaName).toString();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    for (int run = 0; run < 3; run++) {
      Process process =
          new ProcessBuilder(
                  javaExecutable,
                  "-cp",
                  classpath,
                  "com.developmentsprint.cryptocensus.delivery.testing.PlanVerifier",
                  profile.toString(),
                  plan.planSha256())
              .redirectErrorStream(true)
              .start();
      try {
        assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).withFailMessage(output).isZero();
        assertThat(output).contains(plan.planSha256());
      } finally {
        process.destroyForcibly();
      }
    }
    assertThat(requests.get()).isZero();
  }

  @Test
  void offlinePlanLoadsAndPublishesExactBytesWithStageReceipt() throws Exception {
    ProfileCommand.Result plan =
        ProfileCommand.run(
            "plan",
            profile,
            null,
            reference -> {
              throw new AssertionError("Planning must not resolve credentials");
            });
    assertThat(requests.get()).isZero();
    assertThat(Files.readString(directory.resolve("plan.json")))
        .doesNotContain(endpoint, "CENSUS_TEST_TOKEN");
    ProfileCommand.Result delivered =
        ProfileCommand.run(
            "publish", profile, plan.planSha256(), reference -> "private-test-token");
    assertThat(delivered.successful()).isTrue();
    assertThat(requests.get()).isEqualTo(1);
    assertThat(submitted.get()).isEqualTo(Files.readAllBytes(directory.resolve("cbom.json")));
    assertThat(authorization.get()).isEqualTo("Bearer private-test-token");
    assertThat(Files.readString(directory.resolve("receipt.json")))
        .contains("census.delivery-receipt/2", "ACCEPTED", "NOT_APPLICABLE", "UNVERIFIED")
        .doesNotContain("private-test-token", endpoint, "accepted");
  }

  @Test
  void remotePreflightDoesNotUpload() throws Exception {
    var plan = ProfileCommand.run("plan", profile, null, reference -> null);
    var preflight =
        ProfileCommand.run("preflight", profile, plan.planSha256(), reference -> "secret");
    assertThat(preflight.successful()).isTrue();
    assertThat(preflight.receipt().observation().upload())
        .isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
    assertThat(requests.get()).isZero();
  }

  @Test
  void readbackUsesSeparateCredentialsAndProducesActualHashProof() throws Exception {
    byte[] original = Files.readAllBytes(directory.resolve("cbom.json"));
    AtomicReference<String> evidenceAuth = new AtomicReference<>();
    server.createContext(
        "/evidence",
        exchange -> {
          evidenceAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(200, original.length);
          exchange.getResponseBody().write(original);
          exchange.close();
        });
    config.put(
        "retention",
        Map.of(
            "endpoint",
            endpoint.replace("/upload", "/evidence"),
            "authentication",
            Map.of("type", "ENV", "name", "EVIDENCE_TOKEN")));
    ProfileFixture.write(directory, config);
    var plan = ProfileCommand.run("plan", profile, null, reference -> null);
    var delivered =
        ProfileCommand.run(
            "publish",
            profile,
            plan.planSha256(),
            reference ->
                reference.name().equals("EVIDENCE_TOKEN") ? "read-only-secret" : "upload-secret");
    assertThat(delivered.successful()).isTrue();
    assertThat(delivered.receipt().retention().artifactSha256())
        .isEqualTo(ProfileFixture.hash(original));
    assertThat(evidenceAuth.get()).isEqualTo("Bearer read-only-secret");
    assertThat(authorization.get()).isEqualTo("Bearer upload-secret");
    assertThat(Files.readString(directory.resolve("receipt.json")))
        .contains("RETRIEVED_EXACT_BYTES")
        .doesNotContain("read-only-secret", "upload-secret");
  }

  @ParameterizedTest
  @ValueSource(ints = {301, 400, 401, 403, 429, 500, 503})
  void rejectionAndAmbiguityNeverRetry(int httpStatus) throws Exception {
    status.set(httpStatus);
    response.set("server-echo-private-test-token".getBytes(StandardCharsets.UTF_8));
    var plan = ProfileCommand.run("plan", profile, null, reference -> null);
    var result =
        ProfileCommand.run(
            "publish", profile, plan.planSha256(), reference -> "private-test-token");
    assertThat(result.successful()).isFalse();
    assertThat(requests.get()).isEqualTo(1);
    assertThat(Files.readString(directory.resolve("receipt.json")))
        .doesNotContain("private-test-token");
  }

  @Test
  void oversizedBodyCancelsAndDoesNotClaimAcceptance() throws Exception {
    response.set(new byte[65537]);
    var plan = ProfileCommand.run("plan", profile, null, reference -> null);
    var result = ProfileCommand.run("publish", profile, plan.planSha256(), reference -> "secret");
    assertThat(result.successful()).isFalse();
    assertThat(result.receipt().observation().reasonCode()).isEqualTo("RESPONSE_TOO_LARGE");
  }

  @Test
  void wrongReadbackBlocksPublication() throws Exception {
    config.put("retention", Map.of("endpoint", endpoint, "authentication", Map.of("type", "NONE")));
    ProfileFixture.write(directory, config);
    var plan = ProfileCommand.run("plan", profile, null, reference -> null);
    var result = ProfileCommand.run("publish", profile, plan.planSha256(), reference -> "secret");
    assertThat(result.successful()).isFalse();
    assertThat(result.receipt().observation().upload())
        .isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
    assertThat(submitted.get()).isEmpty();
    assertThat(requests.get()).isEqualTo(1);
  }

  @Test
  void changedProfilePlanSourceOrPreparedBytesRejectWithoutRequest() throws Exception {
    ProfilePlanner planner =
        new ProfilePlanner(new ProfileReader(List.of(new GenericProfileAdapter())));
    PlannedDelivery plan = planner.plan(profile);
    assertThatThrownBy(() -> planner.plan(profile)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> planner.load(profile, "0".repeat(64)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> planner.load(profile, null))
        .isInstanceOf(IllegalArgumentException.class);
    Files.writeString(plan.bundle().publication().path(), "changed");
    assertThatThrownBy(() -> planner.revalidate(plan)).isInstanceOf(Exception.class);
    Files.writeString(profile, "{}");
    assertThatThrownBy(() -> planner.load(profile, plan.planSha256()))
        .isInstanceOf(Exception.class);
    assertThat(requests.get()).isZero();
  }

  @Test
  void sourceMutationInvalidatesPreviouslyPreparedPlan() throws Exception {
    var plan = ProfileCommand.run("plan", profile, null, reference -> null);
    Files.writeString(directory.resolve("cbom.json"), "changed");
    assertThatThrownBy(
            () -> ProfileCommand.run("publish", profile, plan.planSha256(), reference -> "secret"))
        .isInstanceOf(Exception.class);
    assertThat(requests.get()).isZero();
  }

  @Test
  void anonymousDeliveryDoesNotResolveSecrets() throws Exception {
    config.put("authentication", Map.of("type", "NONE"));
    ProfileFixture.write(directory, config);
    var plan =
        ProfileCommand.run(
            "plan",
            profile,
            null,
            reference -> {
              throw new AssertionError();
            });
    var result =
        ProfileCommand.run(
            "publish",
            profile,
            plan.planSha256(),
            reference -> {
              throw new AssertionError();
            });
    assertThat(result.successful()).isTrue();
    assertThat(authorization.get()).isNull();
  }

  @Test
  void receiptCannotOverwriteSourceOrPlan() throws Exception {
    config.put(
        "outputs",
        Map.of("plan", directory.resolve("plan.json").toString(), "receipt", profile.toString()));
    ProfileFixture.write(directory, config);
    var plan = ProfileCommand.run("plan", profile, null, reference -> null);
    assertThatThrownBy(
            () -> ProfileCommand.run("publish", profile, plan.planSha256(), reference -> "secret"))
        .isInstanceOf(com.developmentsprint.cryptocensus.delivery.DeliveryException.class);
    assertThat(requests.get()).isZero();
  }

  @Test
  void receiptFailureAfterAcceptanceRemainsExplicit() throws Exception {
    Path receipt = directory.resolve("receipt.json");
    server.removeContext("/upload");
    server.createContext(
        "/upload",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          Files.delete(receipt);
          Files.createDirectory(receipt);
          byte[] bytes = {1};
          exchange.sendResponseHeaders(200, 1);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    var plan = ProfileCommand.run("plan", profile, null, reference -> null);
    assertThatThrownBy(
            () -> ProfileCommand.run("publish", profile, plan.planSha256(), reference -> "secret"))
        .isInstanceOf(ReceiptWriteException.class)
        .satisfies(error -> assertThat(((ReceiptWriteException) error).remoteAccepted()).isTrue());
  }

  @Test
  void defaultTrustRejectsPrivateReceiver() throws Exception {
    config.remove("transport");
    ProfileFixture.write(directory, config);
    var plan = ProfileCommand.run("plan", profile, null, reference -> null);
    var result = ProfileCommand.run("publish", profile, plan.planSha256(), reference -> "secret");
    assertThat(result.successful()).isFalse();
    assertThat(result.receipt().observation().reasonCode()).isEqualTo("TLS_FAILURE");
    assertThat(requests.get()).isZero();
  }

  @Test
  void executorStopsSecondMutationWithoutRepeatingUpload() throws Exception {
    var planner = new ProfilePlanner(new ProfileReader(List.of(new LoopAdapter(true))));
    var plan = planner.plan(profile);
    var result = new ProfileExecutor(planner).execute(plan, reference -> "secret");
    assertThat(result.successful()).isFalse();
    assertThat(result.observation().reasonCode()).isEqualTo("MUTATION_LIMIT");
    assertThat(result.observation().upload()).isEqualTo(AdapterResult.Upload.ACCEPTED);
    assertThat(requests.get()).isEqualTo(1);
  }

  @Test
  void executorBoundsReadOnlyAdapterPolling() throws Exception {
    var planner = new ProfilePlanner(new ProfileReader(List.of(new LoopAdapter(false))));
    var result = new ProfileExecutor(planner).execute(planner.plan(profile), reference -> "secret");
    assertThat(result.successful()).isFalse();
    assertThat(result.observation().reasonCode()).isEqualTo("OPERATION_LIMIT");
    assertThat(result.observation().upload()).isEqualTo(AdapterResult.Upload.NOT_ATTEMPTED);
    assertThat(requests.get()).isEqualTo(64);
  }

  @Test
  void protocolFailureStopsAnAdapterThatRequestsFurtherWork() throws Exception {
    config.remove("transport");
    ProfileFixture.write(directory, config);
    var planner = new ProfilePlanner(new ProfileReader(List.of(new LoopAdapter(false))));
    var result = new ProfileExecutor(planner).execute(planner.plan(profile), reference -> "secret");
    assertThat(result.successful()).isFalse();
    assertThat(result.observation().reasonCode()).isEqualTo("TLS_FAILURE");
    assertThat(result.exchanges()).isEqualTo(1);
    assertThat(requests.get()).isZero();
  }

  @Test
  void changedCaInvalidatesPlanAndExactExecutionTrust() throws Exception {
    Path ca = directory.resolve("roots.pem");
    Files.copy(tls.caBundle(), ca);
    config.put("transport", Map.of("caBundle", ca.toString()));
    ProfileFixture.write(directory, config);
    var planner = new ProfilePlanner(new ProfileReader(List.of(new GenericProfileAdapter())));
    var plan = planner.plan(profile);
    final byte[] original = Files.readAllBytes(ca);
    assertThatThrownBy(
            () ->
                new ProfileExecutor(planner)
                    .execute(
                        plan,
                        reference -> {
                          try {
                            Files.writeString(ca, Files.readString(ca) + "\n");
                          } catch (java.io.IOException error) {
                            throw new AssertionError(error);
                          }
                          return "secret";
                        }))
        .hasMessageContaining("CA bundle changed");
    assertThatThrownBy(() -> planner.load(profile, plan.planSha256()))
        .hasMessageContaining("changed since planning");
    assertThat(requests.get()).isZero();
    Files.write(ca, original);
    assertThat(planner.load(profile, plan.planSha256()).planSha256()).isEqualTo(plan.planSha256());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void receiptCannotOverwriteCaFileOrHardlink(boolean hardlink) throws Exception {
    Path ca = directory.resolve("roots.pem");
    Files.copy(tls.caBundle(), ca);
    final byte[] original = Files.readAllBytes(ca);
    Path receipt = ca;
    if (hardlink) {
      receipt = directory.resolve("receipt-link.json");
      Files.createLink(receipt, ca);
    }
    config.put("transport", Map.of("caBundle", ca.toString()));
    config.put(
        "outputs",
        Map.of("plan", directory.resolve("plan.json").toString(), "receipt", receipt.toString()));
    ProfileFixture.write(directory, config);
    var plan = ProfileCommand.run("plan", profile, null, reference -> null);
    assertThatThrownBy(
            () ->
                ProfileCommand.run("preflight", profile, plan.planSha256(), reference -> "secret"))
        .isInstanceOf(com.developmentsprint.cryptocensus.delivery.DeliveryException.class)
        .hasMessageContaining("Receipt must be distinct");
    assertThat(Files.readAllBytes(ca)).isEqualTo(original);
    assertThat(requests.get()).isZero();
  }

  private static final class LoopAdapter implements DestinationAdapter {
    private final boolean mutation;

    LoopAdapter(boolean mutation) {
      this.mutation = mutation;
    }

    @Override
    public String id() {
      return "generic-https";
    }

    @Override
    public AdapterOptions parseOptions(Map<String, Object> values) {
      return new GenericProfileAdapter().parseOptions(values);
    }

    @Override
    public CapabilityDeclaration capabilities() {
      return new GenericProfileAdapter().capabilities();
    }

    @Override
    public void validate(AdapterContext context) {}

    @Override
    public AdapterSession open(AdapterContext context, byte[] bytes) {
      return new LoopSession(mutation);
    }
  }

  private static final class LoopSession implements AdapterSession {
    private final boolean mutation;

    LoopSession(boolean mutation) {
      this.mutation = mutation;
    }

    @Override
    public ProtocolStep first() {
      return step(false);
    }

    @Override
    public ProtocolStep accept(ProtocolResponse response) {
      return step(response.failureCode() == null);
    }

    private ProtocolStep step(boolean accepted) {
      return new ProtocolStep(
          new AdapterRequest(mutation ? "POST" : "GET", "", null, new byte[0], mutation, 0),
          new AdapterResult(
              accepted && mutation
                  ? AdapterResult.Upload.ACCEPTED
                  : AdapterResult.Upload.NOT_ATTEMPTED,
              AdapterResult.Ingestion.PENDING,
              "PENDING",
              0,
              null,
              null,
              List.of()));
    }
  }
}
