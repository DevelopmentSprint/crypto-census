package com.developmentsprint.cryptocensus.appservers;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.annotations.CensusAnnotationApi;
import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.annotations.CryptoPurpose;
import com.developmentsprint.cryptocensus.annotations.DataProtection;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.SourceCatalog;
import com.developmentsprint.cryptocensus.core.Targets;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/** Annotated deployments in pinned real servers; no Docker skip satisfies the CI gate. */
class AnnotatedAppServerIT {
  private static final String AGENT = "/opt/census/census-agent.jar";
  private static final String SOURCES = "/opt/census/source-catalog.json";
  private static final String CBOM = "/tmp/context-cbom.json";
  private static final String EVIDENCE = "/tmp/context-evidence.json";
  private static final String LIBERTY_SERVER = "/opt/ol/wlp/usr/servers/defaultServer";
  private static final List<String> APPS = List.of("context-one", "context-two");
  private static final int READABLE = 0644;
  @TempDir static Path temporary;

  @BeforeAll
  static void requireDocker() {
    AppServerIT.requireDocker();
  }

  @Test
  void annotatedLibertyDeploymentsKeepSeparateLoaderIdentity() throws Exception {
    String options = "-javaagent:" + AGENT + "=" + agentOptions("liberty-context") + "\n";
    try (GenericContainer<?> server =
        new GenericContainer<>(DockerImageName.parse(AppServerIT.OPEN_LIBERTY))
            .withLabel("cryptocensus.task", "census-dlz.8")
            .withCopyToContainer(Transferable.of(sources(false), READABLE), SOURCES)
            .withCopyFileToContainer(MountableFile.forHostPath(agentJar(), READABLE), AGENT)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("liberty/server.xml", READABLE),
                LIBERTY_SERVER + "/server.xml")
            .withCopyToContainer(
                Transferable.of(options.getBytes(StandardCharsets.UTF_8), READABLE),
                LIBERTY_SERVER + "/jvm.options")
            .withExposedPorts(9080)
            .waitingFor(
                Wait.forLogMessage(".*CWWKZ0001I.*context-.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(5)))) {
      for (String app : APPS) {
        server.withCopyFileToContainer(
            MountableFile.forHostPath(war(app), READABLE),
            LIBERTY_SERVER + "/dropins/" + app + ".war");
      }
      EvidenceDocument evidence = exercise(server, 9080, "liberty");
      assertDeployments(evidence);
    }
  }

  @Test
  void annotatedWildFlyDeploymentsUseExplicitBootstrapBridgeDelegation() throws Exception {
    String options =
        "-Djava.net.preferIPv4Stack=true -Djava.awt.headless=true"
            + " -Djboss.modules.system.pkgs=org.jboss.byteman,"
            + "com.developmentsprint.cryptocensus.agent"
            + " -javaagent:"
            + AGENT
            + "="
            + agentOptions("wildfly-context");
    try (GenericContainer<?> server =
        new GenericContainer<>(DockerImageName.parse(AppServerIT.WILDFLY))
            .withLabel("cryptocensus.task", "census-dlz.8")
            .withCopyToContainer(Transferable.of(sources(true), READABLE), SOURCES)
            .withCopyFileToContainer(MountableFile.forHostPath(agentJar(), READABLE), AGENT)
            .withEnv("JAVA_OPTS", options)
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forLogMessage(".*WFLYSRV0010: Deployed \\\"context-.*\\n", 2)
                    .withStartupTimeout(Duration.ofMinutes(5)))) {
      for (String app : APPS) {
        server.withCopyFileToContainer(
            MountableFile.forHostPath(war(app), READABLE),
            "/opt/jboss/wildfly/standalone/deployments/" + app + ".war");
      }
      EvidenceDocument evidence = exercise(server, 8080, "wildfly");
      assertDeployments(evidence);
    }
  }

  private static String agentOptions(String app) {
    return "output="
        + CBOM
        + ",evidenceOutput="
        + EVIDENCE
        + ",enrichment=true,sourcesFile="
        + SOURCES
        + ",app="
        + app;
  }

  private static EvidenceDocument exercise(GenericContainer<?> server, int port, String name)
      throws Exception {
    server.start();
    for (String app : APPS) {
      HttpResponse<String> response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(
                          URI.create(
                              "http://"
                                  + server.getHost()
                                  + ":"
                                  + server.getMappedPort(port)
                                  + "/"
                                  + app
                                  + "/crypto"))
                      .timeout(Duration.ofMinutes(1))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      assertThat(response.body()).startsWith("ok ");
    }
    server.getDockerClient().stopContainerCmd(server.getContainerId()).withTimeout(120).exec();
    String logs = server.getLogs();
    Files.writeString(temporary.resolve(name + ".log"), logs);
    Path cbomPath = temporary.resolve(name + ".json");
    Path evidencePath = temporary.resolve(name + ".evidence.json");
    server.copyFileFromContainer(
        CBOM, input -> Files.copy(input, cbomPath, StandardCopyOption.REPLACE_EXISTING));
    server.copyFileFromContainer(
        EVIDENCE, input -> Files.copy(input, evidencePath, StandardCopyOption.REPLACE_EXISTING));
    EvidenceDocument evidence = EvidenceCodec.read(evidencePath);
    Cbom.Document cbom = EvidenceCodec.validatePair(evidence, Cbom.readBytes(cbomPath));
    assertThat(cbom.properties())
        .as(logs)
        .containsEntry("cryptocensus:recordingFailures", "0")
        .containsEntry("cryptocensus:transformErrors", "0")
        .containsEntry("cryptocensus:dropped", "0")
        .containsEntry("cryptocensus:drainTimedOut", "0");
    assertThat(Targets.parse(cbom.properties().get(Targets.PROPERTY)).values())
        .noneMatch(Targets.Status::isGap);
    assertThat(logs).doesNotContain("[crypto-census] agent failed to start");
    return evidence;
  }

  private static void assertDeployments(EvidenceDocument evidence) {
    var archive =
        evidence.flows().stream()
            .filter(flow -> flow.definition().subject().flowId().equals("archive"))
            .toList();
    assertThat(archive).hasSize(2);
    assertThat(archive.stream().map(EvidenceDocument.DiscoveredFlow::identityId).distinct())
        .hasSize(2);
    var declaring =
        evidence.identities().stream()
            .filter(
                identity ->
                    archive.stream().anyMatch(flow -> flow.identityId().equals(identity.id())))
            .toList();
    assertThat(declaring)
        .allSatisfy(
            identity -> {
              assertThat(identity.applicationId())
                  .isIn("fixture:context-one", "fixture:context-two");
              assertThat(identity.status()).isEqualTo(EvidenceDocument.IdentityStatus.RESOLVED);
              assertThat(identity.deploymentId())
                  .isEqualTo(identity.applicationId() + ":deployment");
              assertThat(identity.sourceArtifact())
                  .isEqualTo(identity.applicationId() + ":artifact");
            });
    assertThat(declaring.stream().map(EvidenceDocument.IdentityMapping::applicationId).distinct())
        .hasSize(2);
    assertThat(declaring.stream().map(EvidenceDocument.IdentityMapping::loaderId).distinct())
        .hasSize(2);
    var observations =
        evidence.observations().stream()
            .filter(
                observation ->
                    observation
                        .occurrence()
                        .location()
                        .equals(AnnotatedCryptoServlet.class.getName()))
            .toList();
    assertThat(
            observations.stream().map(observation -> observation.context().identityId()).distinct())
        .containsExactlyInAnyOrderElementsOf(
            declaring.stream().map(EvidenceDocument.IdentityMapping::id).toList());
    for (var identity : declaring) {
      var deployment =
          observations.stream()
              .filter(observation -> observation.context().identityId().equals(identity.id()))
              .toList();
      assertThat(deployment)
          .anySatisfy(
              observation ->
                  assertThat(observation.context().path())
                      .extracting(scope -> scope.subject().flowId())
                      .containsExactly("archive"));
      assertThat(deployment)
          .anySatisfy(
              observation ->
                  assertThat(observation.context().path())
                      .extracting(scope -> scope.subject().flowId())
                      .containsExactly("archive", "transport"));
      assertThat(deployment)
          .allSatisfy(
              observation -> {
                assertThat(observation.projectFrame().sourceArtifact())
                    .isEqualTo(identity.sourceArtifact());
                assertThat(observation.projectFrame().origin()).isEqualTo(ContextModel.Origin.TEST);
                assertThat(observation.context().path())
                    .allSatisfy(
                        scope -> {
                          assertThat(scope.identityId()).isEqualTo(identity.id());
                          assertThat(scope.subject().applicationId())
                              .isEqualTo(identity.applicationId());
                        });
              });
      var afterReturn =
          deployment.stream()
              .filter(observation -> observation.occurrence().symbol().equals("doGet"))
              .toList();
      assertThat(afterReturn)
          .hasSize(1)
          .allSatisfy(
              observation -> {
                assertThat(observation.count()).isEqualTo(1);
                assertThat(observation.context().association())
                    .isEqualTo(EvidenceDocument.Association.NO_SCOPE);
              });
    }
    assertThat(evidence.health().get(EvidenceDocument.Counter.SCOPE_INTEGRITY)).isZero();
    assertThat(evidence.health().get(EvidenceDocument.Counter.SCOPE_RESTORE_FAILURES)).isZero();
    assertThat(evidence.health().get(EvidenceDocument.Counter.TRANSFORM_ERRORS)).isZero();
  }

  private static byte[] sources(boolean wildfly) {
    return new SourceCatalog(
            1,
            APPS.stream()
                .map(
                    app ->
                        new SourceCatalog.Entry(
                            wildfly
                                ? "vfs:/content/" + app + ".war/WEB-INF/classes"
                                : "file:" + LIBERTY_SERVER + "/dropins/" + app + ".war",
                            "fixture:" + app + ":artifact",
                            "fixture:" + app,
                            ContextModel.Origin.TEST,
                            "fixture:" + app + ":deployment",
                            wildfly ? "deployment." + app + ".war" : ""))
                .toList())
        .encode();
  }

  private static Path war(String app) throws Exception {
    Path war = temporary.resolve(app + ".war");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(war))) {
      for (Class<?> type :
          List.of(
              AnnotatedCryptoServlet.class,
              CryptoFlow.class,
              DataProtection.class,
              CryptoPurpose.class,
              CensusAnnotationApi.class)) {
        String entry = type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream("/" + entry)) {
          assertThat(input).as(entry).isNotNull();
          zip.putNextEntry(new ZipEntry("WEB-INF/classes/" + entry));
          input.transferTo(zip);
          zip.closeEntry();
        }
      }
    }
    return war;
  }

  private static Path agentJar() {
    return Path.of(System.getProperty("census.agent.jar"));
  }
}
