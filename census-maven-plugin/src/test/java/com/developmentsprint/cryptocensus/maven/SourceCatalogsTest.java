package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.SourceCatalog;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceCatalogsTest {
  @TempDir Path temporary;

  @Test
  void retainsDeclaringReactorOwnershipAndExplicitOverrides() {
    MavenProject library = project("library", temporary.resolve("library"));
    MavenProject consumer = project("consumer", temporary.resolve("consumer"));
    var dependency =
        SourceCatalogs.entry(
            library.getBuild().getDirectory() + "/library.jar",
            "acme:library",
            "acme:library",
            ContextModel.Origin.LIBRARY,
            false);
    var external =
        SourceCatalogs.entry(
            temporary.resolve("vendor.jar").toString(),
            "vendor:api",
            "vendor:api",
            ContextModel.Origin.LIBRARY,
            false);
    var override =
        new SourceCatalog.Entry(
            external.locationUri(),
            "vendor:api",
            "billing",
            ContextModel.Origin.LIBRARY,
            "deployment-one");
    var result =
        SourceCatalogs.generate(
            List.of(library, consumer),
            List.of(dependency, external),
            new SourceCatalog(1, List.of(override)));
    assertThat(result.resolve(dependency.locationUri()).orElseThrow().origin())
        .isEqualTo(ContextModel.Origin.MAIN);
    assertThat(result.resolve(external.locationUri()).orElseThrow()).isEqualTo(override);
    String tests = Path.of(library.getBuild().getTestOutputDirectory()).toUri().toString() + "/";
    assertThat(result.resolve(tests).orElseThrow().origin()).isEqualTo(ContextModel.Origin.TEST);
    assertThat(result.resolve(tests).orElseThrow().applicationId()).isEqualTo("acme:library");
    assertThat(result.resolve(temporary.resolve("unknown-shaded.jar").toUri().toString()))
        .isEmpty();
  }

  @Test
  void rejectsAmbiguousBuildOwnershipInsteadOfGuessing() {
    var first = project("first", temporary);
    var second = project("second", temporary);
    assertThatThrownBy(
            () ->
                SourceCatalogs.generate(
                    List.of(first, second), List.of(), new SourceCatalog(1, List.of())))
        .hasMessageContaining("conflicting source locations");
    var parent = project("parent", temporary);
    parent.setPackaging("pom");
    assertThat(
            SourceCatalogs.generate(List.of(parent), List.of(), new SourceCatalog(1, List.of()))
                .entries())
        .isEmpty();
  }

  @Test
  void resolvedCopiesKeepReactorIdentityAndRepackagedJarsRequireExplicitMapping() {
    var library = project("library", temporary.resolve("library"));
    library.getProperties().setProperty("census.applicationId", "billing");
    var copy = temporary.resolve("repository/library-1.jar").toFile();
    var main = SourceCatalogs.dependency(copy, "acme:library", "1", "jar", "", List.of(library));
    assertThat(main.applicationId()).isEqualTo("billing");
    assertThat(main.origin()).isEqualTo(ContextModel.Origin.MAIN);
    assertThat(
            SourceCatalogs.dependency(copy, "acme:library", "1", "jar", "tests", List.of(library))
                .origin())
        .isEqualTo(ContextModel.Origin.LIBRARY);
    var handler = new org.apache.maven.artifact.handler.DefaultArtifactHandler("test-jar");
    handler.setExtension("jar");
    library
        .getAttachedArtifacts()
        .add(
            new org.apache.maven.artifact.DefaultArtifact(
                "acme", "library", "1", "test", "test-jar", "tests", handler));
    assertThat(
            SourceCatalogs.dependency(copy, "acme:library", "1", "jar", "tests", List.of(library))
                .origin())
        .isEqualTo(ContextModel.Origin.TEST);
    assertThat(
            SourceCatalogs.dependency(copy, "vendor:api", "1", "jar", "", List.of(library))
                .origin())
        .isEqualTo(ContextModel.Origin.LIBRARY);
    assertThat(
            SourceCatalogs.dependency(copy, "acme:library", "0", "jar", "", List.of(library))
                .applicationId())
        .isEqualTo("acme:library");
    assertThat(
            SourceCatalogs.dependency(copy, "acme:library", "1", "zip", "", List.of(library))
                .origin())
        .isEqualTo(ContextModel.Origin.LIBRARY);
    var shade = new org.apache.maven.model.Plugin();
    shade.setArtifactId("maven-shade-plugin");
    library.getBuild().addPlugin(shade);
    assertThat(SourceCatalogs.dependency(copy, "acme:library", "1", "jar", "", List.of(library)))
        .isNull();
    var generated =
        SourceCatalogs.generate(List.of(library), List.of(), new SourceCatalog(1, List.of()));
    assertThat(generated.entries()).allMatch(entry -> entry.locationUri().endsWith("/"));
    var explicit =
        new SourceCatalog.Entry(
            main.locationUri(),
            "acme:shaded-owner",
            "billing",
            ContextModel.Origin.LIBRARY,
            "production");
    var mapped =
        SourceCatalogs.generate(
            List.of(library), List.of(), new SourceCatalog(1, List.of(explicit)));
    assertThat(mapped.resolve(main.locationUri()).orElseThrow()).isEqualTo(explicit);
  }

  static MavenProject project(String name, Path directory) {
    var project = new MavenProject();
    project.setGroupId("acme");
    project.setArtifactId(name);
    project.setVersion("1");
    project.setPackaging("jar");
    var build = new Build();
    build.setDirectory(directory.resolve("target").toString());
    build.setOutputDirectory(directory.resolve("target/classes").toString());
    build.setTestOutputDirectory(directory.resolve("target/test-classes").toString());
    build.setFinalName(name);
    project.setBuild(build);
    return project;
  }
}
