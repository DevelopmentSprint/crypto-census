package com.developmentsprint.cryptocensus.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import net.bytebuddy.agent.builder.AgentBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Executes the proposed internal hooks without modifying the production agent installer. */
class TlsCorrelationFeasibilityIT {
  @TempDir Path temporary;

  @Test
  void correlatesCompletedFailedResumedAndDelegatedHandshakesByOwnedObjects() throws Exception {
    Path store = ChildJvm.keystore(temporary);
    Path probe = probeJar();
    Path result = temporary.resolve("probe.tsv");
    Path log = temporary.resolve("probe.log");
    List<String> command = new ArrayList<>();
    command.add(System.getProperty("census.tls.java", ChildJvm.javaTool("java")));
    command.add("--add-opens=java.base/sun.security.ssl=ALL-UNNAMED");
    command.add("-Djdk.tls.namedGroups=" + System.getProperty("census.tls.group", "x25519"));
    command.add("-javaagent:" + probe + "=" + bridgeJar());
    command.add("-cp");
    command.add(
        String.join(
            File.pathSeparator,
            ChildJvm.classpathEntry(TlsProbeWorkload.class),
            ChildJvm.classpathEntry(AgentBuilder.class)));
    command.add(TlsProbeWorkload.class.getName());
    command.addAll(List.of(store.toString(), ChildJvm.KEYSTORE_PASSWORD, result.toString()));
    Process child =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    boolean finished = child.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      child.destroyForcibly().waitFor();
    }
    assertThat(finished).as(Files.readString(log)).isTrue();
    assertThat(child.exitValue()).as(Files.readString(log)).isZero();
    List<String[]> observations =
        Files.readAllLines(result).stream().map(line -> line.split("\t")).toList();
    List<String[]> completed =
        observations.stream().filter(row -> row[0].equals("completed")).toList();
    assertThat(completed).as(Files.readString(log)).hasSize(8);
    assertThat(completed.stream().map(row -> row[1]).distinct()).hasSize(8);
    String group = System.getProperty("census.tls.groupId", "29");
    assertThat(completed)
        .allSatisfy(
            row -> {
              assertThat(row[4]).isEqualTo("TLSv1.3");
              assertThat(row[6]).isEqualTo(group);
            });
    assertThat(completed.stream().filter(row -> row[2].equals("engine"))).hasSize(4);
    assertThat(completed.stream().filter(row -> row[7].equals("true")))
        .hasSize(2)
        .allSatisfy(
            row -> {
              assertThat(row[8]).isEqualTo("unknown");
              assertThat(row[9]).isEqualTo("unknown");
            });
    assertThat(completed.stream().filter(row -> row[3].equals("client") && row[7].equals("true")))
        .singleElement()
        .satisfies(row -> assertThat(row[10]).isEqualTo("true"));
    assertThat(completed.stream().filter(row -> row[3].equals("client") && row[7].equals("false")))
        .allSatisfy(row -> assertThat(row[9]).isEqualTo("ecdsa_secp256r1_sha256"));
    assertThat(completed.stream().filter(row -> row[2].equals("engine") && row[3].equals("client")))
        .allSatisfy(row -> assertThat(row[12]).isEqualTo("tls-delegated-task"));
    assertThat(observations.stream().filter(row -> row[0].equals("failed"))).hasSize(2);
    assertThat(observations.stream().filter(row -> row[0].equals("ticket-derived-sessions")))
        .singleElement()
        .satisfies(row -> assertThat(Integer.parseInt(row[1])).isGreaterThan(0));
  }

  private Path probeJar() throws Exception {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().putValue("Premain-Class", TlsProbeAgent.class.getName());
    Path jar = temporary.resolve("tls-probe.jar");
    Path classes = Path.of(ChildJvm.classpathEntry(TlsProbeAgent.class));
    Path packageDirectory = classes.resolve("com/developmentsprint/cryptocensus/it");
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest);
        var paths = Files.list(packageDirectory)) {
      for (Path file :
          paths
              .filter(path -> path.getFileName().toString().startsWith("TlsProbeAgent"))
              .toList()) {
        addClass(output, classes, file);
      }
    }
    return jar;
  }

  private Path bridgeJar() throws Exception {
    Path jar = temporary.resolve("tls-bridge.jar");
    Path classes = Path.of(ChildJvm.classpathEntry(TlsProbeBridge.class));
    try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
      addClass(
          output,
          classes,
          classes.resolve("com/developmentsprint/cryptocensus/it/TlsProbeBridge.class"));
    }
    return jar;
  }

  private static void addClass(JarOutputStream output, Path classes, Path file) throws IOException {
    output.putNextEntry(
        new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
    Files.copy(file, output);
    output.closeEntry();
  }
}
