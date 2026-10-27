package com.developmentsprint.cryptocensus.appservers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Primitive;
import com.developmentsprint.cryptocensus.core.Targets;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Conformance: the agent inside real application servers, each with its own JVM vendor and its own
 * agents (Liberty's bootstrap agent, JBoss Modules). Images are pinned by digest; see
 * docs/CONFORMANCE.md for how to move them forward. Without Docker the class is skipped locally,
 * but fails when {@code census.requireDocker=true} (CI), so a gate cannot pass without running.
 */
class AppServerIT {

  /**
   * {@code icr.io/appcafe/open-liberty:26.0.0.8-full-java25-openj9-ubi-minimal} (IBM Semeru 25).
   */
  static final String OPEN_LIBERTY =
      "icr.io/appcafe/open-liberty@sha256:"
          + "ce01001eed158f40b260b0dd26798efcdb9f3a56c65c6cc94b5b4bcdf4c12814";

  /** {@code quay.io/wildfly/wildfly:41.0.1.Final-jdk21} (Eclipse Temurin 21). */
  static final String WILDFLY =
      "quay.io/wildfly/wildfly@sha256:"
          + "a3857756e5c8bd666aa1cdbee4a92b8aa6c79dc912b764361ec6c1a7a22c6a24";

  private static final String AGENT = "/opt/census/census-agent.jar";
  private static final String CBOM = "/tmp/cryptocensus-cbom.json";
  private static final String LIBERTY_SERVER = "/opt/ol/wlp/usr/servers/defaultServer";
  private static final String APP = "census-app";
  private static final Duration STARTUP = Duration.ofMinutes(5);
  private static final int READABLE = 0644;

  @TempDir static Path tmp;
  static Path war;

  @BeforeAll
  static void requireDocker() {
    boolean available = DockerClientFactory.instance().isDockerAvailable();
    if (Boolean.getBoolean("census.requireDocker")) {
      assertThat(available)
          .as("Docker is required here (census.requireDocker=true) but is not available")
          .isTrue();
    }
    assumeTrue(available, "Docker is not available; app-server conformance skipped");
  }

  @BeforeAll
  static void buildWar() throws IOException {
    war = tmp.resolve(APP + ".war");
    String entry = CryptoServlet.class.getName().replace('.', '/') + ".class";
    try (OutputStream out = Files.newOutputStream(war);
        ZipOutputStream zip = new ZipOutputStream(out);
        InputStream servlet = CryptoServlet.class.getResourceAsStream("/" + entry)) {
      assertThat(servlet).as(entry).isNotNull();
      zip.putNextEntry(new ZipEntry("WEB-INF/classes/" + entry));
      servlet.transferTo(zip);
      zip.closeEntry();
    }
  }

  @Test
  void openLibertyOnSemeru() throws Exception {
    String jvmOptions = "-javaagent:" + AGENT + "=output=" + CBOM + ",app=open-liberty\n";
    try (GenericContainer<?> liberty =
        new GenericContainer<>(DockerImageName.parse(OPEN_LIBERTY))
            .withCopyFileToContainer(MountableFile.forHostPath(agentJar(), READABLE), AGENT)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("liberty/server.xml", READABLE),
                LIBERTY_SERVER + "/server.xml")
            .withCopyToContainer(
                Transferable.of(jvmOptions.getBytes(StandardCharsets.UTF_8), READABLE),
                LIBERTY_SERVER + "/jvm.options")
            .withCopyFileToContainer(
                MountableFile.forHostPath(war, READABLE),
                LIBERTY_SERVER + "/dropins/" + APP + ".war")
            .withExposedPorts(9080)
            .waitingFor(
                Wait.forLogMessage(".*CWWKZ0001I.*" + APP + ".*\\n", 1)
                    .withStartupTimeout(STARTUP))) {
      Cbom.Document cbom = exercise(liberty, 9080, "open-liberty");
      assertThat(cbom.properties().get("cryptocensus:java.vendor")).containsIgnoringCase("IBM");
    }
  }

  @Test
  void wildFly() throws Exception {
    // standalone.conf keeps its defaults only when JAVA_OPTS is unset, so they are restated here
    String javaOpts =
        "-Djava.net.preferIPv4Stack=true -Djboss.modules.system.pkgs=org.jboss.byteman"
            + " -Djava.awt.headless=true -javaagent:"
            + AGENT
            + "=output="
            + CBOM
            + ",app=wildfly";
    try (GenericContainer<?> wildfly =
        new GenericContainer<>(DockerImageName.parse(WILDFLY))
            .withCopyFileToContainer(MountableFile.forHostPath(agentJar(), READABLE), AGENT)
            .withCopyFileToContainer(
                MountableFile.forHostPath(war, READABLE),
                "/opt/jboss/wildfly/standalone/deployments/" + APP + ".war")
            .withEnv("JAVA_OPTS", javaOpts)
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forLogMessage(".*WFLYSRV0010: Deployed \"" + APP + "\\.war\".*\\n", 1)
                    .withStartupTimeout(STARTUP))) {
      Cbom.Document cbom = exercise(wildfly, 8080, "wildfly");
      assertThat(cbom.properties().get("cryptocensus:java.vendor")).isNotBlank();
    }
  }

  /**
   * Starts the server, calls the servlet, stops the container gracefully (so the JVM runs its
   * shutdown hooks and the agent writes the CBOM), then reads the CBOM from the stopped container.
   */
  private static Cbom.Document exercise(GenericContainer<?> server, int port, String name)
      throws IOException, InterruptedException {
    server.start();
    // Deployment logs can precede HTTP readiness; probe the root without exercising crypto.
    Wait.forHttp("/")
        .forPort(port)
        .forStatusCodeMatching(status -> status == 200 || status == 404)
        .withStartupTimeout(Duration.ofMinutes(3))
        .waitUntilReady(server);
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
                                + APP
                                + "/crypto"))
                    .timeout(Duration.ofMinutes(1))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.body()).startsWith("ok ");

    server.getDockerClient().stopContainerCmd(server.getContainerId()).withTimeout(120).exec();
    String logs = server.getLogs();
    Path local = tmp.resolve(name + "-cbom.json");
    server.copyFileFromContainer(
        CBOM, in -> Files.copy(in, local, StandardCopyOption.REPLACE_EXISTING));
    Files.writeString(tmp.resolve(name + ".log"), logs);

    assertThat(logs).doesNotContain("[crypto-census] agent failed to start");
    assertThat(logs).doesNotContain("[crypto-census] instrumentation gap");
    assertThat(logs).contains("[crypto-census] CBOM written to");

    Cbom.Document cbom = Cbom.read(local);
    assertThat(cbom.hasAgentHealth()).as(logs).isTrue();
    assertThat(cbom.properties())
        .as(logs)
        .containsEntry("cryptocensus:transformErrors", "0")
        .containsEntry("cryptocensus:recordingFailures", "0")
        .containsEntry("cryptocensus:dropped", "0")
        .containsEntry("cryptocensus:drainTimedOut", "0");
    assertThat(Targets.parse(cbom.properties().get(Targets.PROPERTY)).values())
        .isNotEmpty()
        .noneMatch(Targets.Status::isGap);

    // the servlet's own cryptography is attributed to the servlet, not to the server's frames
    String servlet = CryptoServlet.class.getName();
    for (Primitive primitive : List.of(Primitive.MESSAGE_DIGEST, Primitive.CIPHER)) {
      List<Occurrence> occurrences =
          cbom.inventory().entries().stream()
              .filter(e -> e.asset().primitive() == primitive)
              .flatMap(e -> e.occurrences().keySet().stream())
              .filter(o -> servlet.equals(o.location()))
              .toList();
      assertThat(occurrences)
          .as(primitive + " from the servlet in " + cbom.inventory().entries())
          .isNotEmpty()
          .allSatisfy(o -> assertThat(o.symbol()).isEqualTo("doGet"));
    }
    assertThat(cbom.inventory().entries())
        .anySatisfy(
            e -> {
              assertThat(e.asset().algorithm()).isEqualTo("SHA3-256");
              assertThat(e.occurrences().keySet())
                  .anySatisfy(o -> assertThat(o.location()).isEqualTo(servlet));
            });
    return cbom;
  }

  private static Path agentJar() {
    String jar = System.getProperty("census.agent.jar");
    assertThat(jar).as("census.agent.jar system property").isNotBlank();
    return Path.of(jar);
  }
}
