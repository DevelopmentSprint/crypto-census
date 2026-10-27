package com.developmentsprint.cryptocensus.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.module.ModuleFinder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnnotationsArtifactIT {
  @TempDir private Path temporary;

  @Test
  void artifactContainsOnlyTheJava17ApiAndHasAStableModuleName() throws IOException {
    Path artifact = artifact();
    try (var jar = new JarFile(artifact.toFile())) {
      assertThat(jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name"))
          .isEqualTo("com.developmentsprint.cryptocensus.annotations");
      var classes = jar.stream().filter(entry -> entry.getName().endsWith(".class")).toList();
      assertThat(classes)
          .extracting(entry -> entry.getName())
          .containsExactlyInAnyOrder(
              "com/developmentsprint/cryptocensus/annotations/CensusAnnotationApi.class",
              "com/developmentsprint/cryptocensus/annotations/CryptoFlow.class",
              "com/developmentsprint/cryptocensus/annotations/CryptoPurpose.class",
              "com/developmentsprint/cryptocensus/annotations/DataProtection.class",
              "com/developmentsprint/cryptocensus/annotations/package-info.class");
      for (var entry : classes) {
        try (var input = new DataInputStream(jar.getInputStream(entry))) {
          assertThat(input.readInt()).isEqualTo(0xCAFEBABE);
          assertThat(input.readUnsignedShort()).isZero();
          assertThat(input.readUnsignedShort()).isEqualTo(61);
        }
      }
      assertThat(jar.getEntry("META-INF/LICENSE")).isNotNull();
      assertThat(jar.getEntry("META-INF/NOTICE")).isNotNull();
    }
    var module =
        ModuleFinder.of(artifact)
            .find("com.developmentsprint.cryptocensus.annotations")
            .orElseThrow();
    assertThat(module.descriptor().isAutomatic()).isTrue();
  }

  @Test
  void standaloneApplicationCompilesAndRunsWithoutAnAgentOrOtherRuntimeDependencies()
      throws IOException, InterruptedException {
    String source =
        """
        import com.developmentsprint.cryptocensus.annotations.*;
        public final class PassiveApplication {
          @CryptoFlow(value = "records-export", purpose = CryptoPurpose.TRANSPORT)
          @DataProtection(classification = "restricted", confidentialityYears = 20)
          public static String export() { return "unchanged"; }
          public static void main(String[] args) throws Exception {
            var method = PassiveApplication.class.getDeclaredMethod("export");
            var flow = method.getAnnotation(CryptoFlow.class);
            var protection = method.getAnnotation(DataProtection.class);
            if (!export().equals("unchanged") || !flow.value().equals("records-export")
                || protection.confidentialityYears() != 20
                || CryptoFlow.class.getAnnotation(CensusAnnotationApi.class).value() != 1) {
              throw new AssertionError("metadata or behavior changed");
            }
            System.out.println("passive-api-ok");
          }
        }
        """;
    assertThat(compile("PassiveApplication", source)).isZero();
    Path output = temporary.resolve("application-output.txt");
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp",
                temporary + java.io.File.pathSeparator + artifact(),
                "PassiveApplication")
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("standalone JVM completes").isTrue();
      assertThat(process.exitValue()).isZero();
      assertThat(Files.readString(output)).contains("passive-api-ok");
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  @Test
  void compilerRejectsInvalidTargetsAndMissingRequiredIds() throws IOException {
    assertThat(
            compile(
                "WrongTarget",
                "import com.developmentsprint.cryptocensus.annotations.*; "
                    + "@CryptoFlow(\"flow\") public class WrongTarget {}"))
        .isNotZero();
    assertThat(
            compile(
                "MissingId",
                "import com.developmentsprint.cryptocensus.annotations.*; "
                    + "public class MissingId { @CryptoFlow public void run() {} }"))
        .isNotZero();
  }

  private int compile(String className, String source) throws IOException {
    Path sourceFile = temporary.resolve(className + ".java");
    Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
    var compiler = ToolProvider.getSystemJavaCompiler();
    assertThat(compiler).as("tests run with a JDK").isNotNull();
    try (var diagnostics = new ByteArrayOutputStream()) {
      return compiler.run(
          null,
          diagnostics,
          diagnostics,
          "--release",
          "17",
          "-classpath",
          artifact().toString(),
          "-d",
          temporary.toString(),
          sourceFile.toString());
    }
  }

  private static Path artifact() {
    return Path.of(System.getProperty("annotations.artifact"));
  }
}
