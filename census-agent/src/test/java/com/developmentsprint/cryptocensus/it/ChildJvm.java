package com.developmentsprint.cryptocensus.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts the JVMs the integration tests observe: the same {@code java.home} as the test, so every
 * JDK in the CI matrix runs them on itself.
 */
final class ChildJvm {

  /** Password of the PKCS#12 keystore made by {@link #keystore(Path)}. */
  static final String KEYSTORE_PASSWORD = "changeit";

  private static final long TIMEOUT_SECONDS = 180;

  private ChildJvm() {}

  /**
   * How a child JVM ended.
   *
   * @param exitCode process exit code
   * @param stderr everything it wrote to stderr (the agent reports there)
   */
  record Result(int exitCode, String stderr) {}

  /** The packaged (shaded) agent jar under test. */
  static String agentJar() {
    return requiredProperty("census.agent.jar");
  }

  /**
   * JVM options attaching the OpenTelemetry Java agent (copied by the build, see the census-agent
   * POM), with every exporter off so it neither needs a collector nor writes telemetry.
   */
  static List<String> openTelemetryAgent() {
    return List.of(
        "-javaagent:" + requiredProperty("otel.agent.jar"),
        "-Dotel.service.name=crypto-census-it",
        "-Dotel.traces.exporter=none",
        "-Dotel.metrics.exporter=none",
        "-Dotel.logs.exporter=none");
  }

  /** A system property Failsafe passes in; fails the test if it is missing. */
  static String requiredProperty(String name) {
    String value = System.getProperty(name);
    assertThat(value).as(name + " system property").isNotBlank();
    return value;
  }

  /** A tool from the JDK running the tests ({@code java}, {@code keytool}). */
  static String javaTool(String name) {
    return Paths.get(System.getProperty("java.home"), "bin", name).toString();
  }

  /** The class path entry (directory or jar) a class was loaded from. */
  static String classpathEntry(Class<?> type) throws URISyntaxException {
    return new File(type.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
  }

  /**
   * Creates a PKCS#12 keystore with a self-signed P-256 certificate for {@code localhost}.
   *
   * @param dir where to put {@code tls.p12}
   * @return the keystore; its password is {@link #KEYSTORE_PASSWORD}
   */
  static Path keystore(Path dir) throws IOException, InterruptedException {
    Path keystore = dir.resolve("tls.p12");
    Process keytool =
        new ProcessBuilder(
                javaTool("keytool"),
                "-genkeypair",
                "-alias",
                "server",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-validity",
                "1",
                "-storetype",
                "PKCS12",
                "-keystore",
                keystore.toString(),
                "-storepass",
                KEYSTORE_PASSWORD,
                "-keypass",
                KEYSTORE_PASSWORD)
            .redirectErrorStream(true)
            .start();
    String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(keytool.waitFor(60, TimeUnit.SECONDS) && keytool.exitValue() == 0)
        .as(output)
        .isTrue();
    return keystore;
  }

  /**
   * Runs {@code main} in a new JVM and waits for it.
   *
   * @param tmp directory for the captured stderr
   * @param jvmArgs JVM options, typically {@code -javaagent:...}
   * @param classpath class path entries
   * @param main the main class
   * @param args program arguments
   */
  static Result run(
      Path tmp, List<String> jvmArgs, List<String> classpath, Class<?> main, List<String> args)
      throws IOException, InterruptedException {
    Path err = Files.createTempFile(tmp, "stderr", ".txt");
    List<String> command = new ArrayList<>();
    command.add(javaTool("java"));
    command.addAll(jvmArgs);
    command.add("-cp");
    command.add(String.join(File.pathSeparator, classpath));
    command.add(main.getName());
    command.addAll(args);
    Process process =
        new ProcessBuilder(command)
            .redirectError(err.toFile())
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .start();
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor();
      throw new AssertionError(
          main.getSimpleName()
              + " did not finish within "
              + TIMEOUT_SECONDS
              + " s; stderr:\n"
              + Files.readString(err, StandardCharsets.UTF_8));
    }
    return new Result(process.exitValue(), Files.readString(err, StandardCharsets.UTF_8));
  }
}
