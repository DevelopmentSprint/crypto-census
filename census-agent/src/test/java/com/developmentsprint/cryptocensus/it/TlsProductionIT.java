package com.developmentsprint.cryptocensus.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsProductionIT {
  @TempDir Path temporary;

  @Test
  void productionCollectorCorrelatesRealTlsAndLeavesFailedConnectionsFailed() throws Exception {
    List<String[]> observations = run("TLSv1.3");
    List<String[]> completed =
        observations.stream().filter(row -> row[0].equals("COMPLETED")).toList();
    assertThat(completed).hasSize(8);
    assertThat(completed.stream().map(row -> row[1]).distinct()).hasSize(8);
    assertThat(completed)
        .allSatisfy(
            row -> {
              assertThat(row[5]).isEqualTo("TLSv1.3");
              assertThat(row[6]).isEqualTo(System.getProperty("census.tls.groupId", "29"));
              assertThat(row[11]).isEqualTo("tls-fixture");
            });
    assertThat(completed.stream().filter(row -> row[7].equals("YES")))
        .hasSize(2)
        .allSatisfy(
            row -> {
              assertThat(row[8]).isEmpty();
              assertThat(row[9]).isEmpty();
              if (row[4].equals("CLIENT")) {
                assertThat(row[10]).isEqualTo("true");
              }
            });
    assertThat(completed.stream().filter(row -> row[7].equals("NO")))
        .hasSize(6)
        .allSatisfy(
            row -> {
              boolean client = row[4].equals("CLIENT");
              assertThat(row[client ? 9 : 8]).isEqualTo("ecdsa_secp256r1_sha256");
              assertThat(row[client ? 8 : 9]).isEmpty();
            });
    assertThat(observations.stream().filter(row -> row[0].equals("FAILED"))).hasSize(2);
    assertThat(observations.stream().filter(row -> row[0].equals("UNKNOWN"))).isEmpty();
    assertThat(
            observations.stream()
                .filter(
                    row ->
                        row[0].equals("capability")
                            && row[1].equals("tls-sunjsse-1.3-correlation")))
        .singleElement()
        .satisfies(row -> assertThat(row[2]).isEqualTo("SUPPORTED"));
  }

  @Test
  void tls12CompletionRemainsUsefulWithExplicitlyMissingSelection() throws Exception {
    List<String[]> observations = run("TLSv1.2");
    List<String[]> completed =
        observations.stream().filter(row -> row[0].equals("COMPLETED")).toList();
    assertThat(completed)
        .hasSize(8)
        .allSatisfy(
            row -> {
              assertThat(row[5]).isEqualTo("TLSv1.2");
              assertThat(row[6]).isEqualTo("-1");
              assertThat(row[8]).isEmpty();
              assertThat(row[9]).isEmpty();
            });
    assertThat(completed.stream().filter(row -> row[7].equals("YES"))).hasSize(2);
    assertThat(observations.stream().filter(row -> row[0].equals("FAILED"))).hasSize(2);
    assertThat(observations.stream().filter(row -> row[0].equals("UNKNOWN"))).isEmpty();
  }

  @Test
  void collectorFailureDoesNotChangeSuccessfulOrRejectedApplicationHandshakes() throws Exception {
    List<String[]> observations = run("TLSv1.3", true);
    assertThat(observations).allSatisfy(row -> assertThat(row[0]).isEqualTo("capability"));
    assertThat(observations.stream().filter(row -> row[1].equals("tls-sunjsse-1.3-correlation")))
        .singleElement()
        .satisfies(row -> assertThat(row[2]).isEqualTo("PARTIAL"));
  }

  @Test
  void abandonedEngineCanBeCollectedAndFinalizedAsUnknown() throws Exception {
    List<String[]> observations = run("TLSv1.3", false, true);
    assertThat(observations.stream().filter(row -> row[0].equals("UNKNOWN")))
        .singleElement()
        .satisfies(row -> assertThat(row[12]).contains("owner-collected-before-completion"));
    assertThat(observations.stream().filter(row -> row[0].equals("COMPLETED"))).hasSize(8);
  }

  private List<String[]> run(String protocol) throws Exception {
    return run(protocol, false);
  }

  private List<String[]> run(String protocol, boolean fault) throws Exception {
    return run(protocol, fault, false);
  }

  private List<String[]> run(String protocol, boolean fault, boolean gc) throws Exception {
    Path store = ChildJvm.keystore(temporary);
    Path launcher = launcher();
    Path log = temporary.resolve("production.log");
    Path result = temporary.resolve("production.tsv");
    List<String> command = new ArrayList<>();
    command.add(System.getProperty("census.tls.java", ChildJvm.javaTool("java")));
    command.addAll(ChildJvmCoverage.jvmArgs());
    command.add("-Dcensus.tls.fixtureFault=" + fault);
    command.add("-Dcensus.tls.fixtureGc=" + gc);
    command.add("-XX:-DisableExplicitGC");
    command.add("-Dcensus.tls.protocol=" + protocol);
    command.add(
        "-Djdk.tls.namedGroups="
            + ("TLSv1.3".equals(protocol)
                ? System.getProperty("census.tls.group", "x25519")
                : "x25519,secp256r1"));
    command.add("-javaagent:" + launcher + "=" + ChildJvm.agentJar());
    command.add("-cp");
    command.add(ChildJvm.classpathEntry(TlsProductionWorkload.class));
    command.add(TlsProductionWorkload.class.getName());
    command.addAll(List.of(store.toString(), ChildJvm.KEYSTORE_PASSWORD, result.toString()));
    Process child =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    boolean finished = child.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      child.destroyForcibly().waitFor();
    }
    assertThat(finished).as(Files.readString(log)).isTrue();
    assertThat(child.exitValue()).as(Files.readString(log)).isZero();
    if (fault) {
      assertThat(Files.readString(log)).contains("TLS-HEALTH:RECORDING_FAILURES");
    } else {
      assertThat(Files.readString(log)).doesNotContain("TLS-HEALTH:");
    }
    return Files.readAllLines(result).stream().map(line -> line.split("\t", -1)).toList();
  }

  private Path launcher() throws Exception {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("Premain-Class", TlsProductionAgent.class.getName());
    manifest.getMainAttributes().putValue("Can-Retransform-Classes", "true");
    Path jar = temporary.resolve("launcher.jar");
    Path classes = Path.of(ChildJvm.classpathEntry(TlsProductionAgent.class));
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
      add(
          output,
          classes,
          classes.resolve(TlsProductionAgent.class.getName().replace('.', '/') + ".class"));
      add(
          output,
          classes,
          classes.resolve(
              TlsProductionAgent.class.getName().replace('.', '/') + "$Bootstrap.class"));
    }
    return jar;
  }

  private static void add(JarOutputStream output, Path classes, Path file)
      throws java.io.IOException {
    output.putNextEntry(
        new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
    Files.copy(file, output);
    output.closeEntry();
  }
}
