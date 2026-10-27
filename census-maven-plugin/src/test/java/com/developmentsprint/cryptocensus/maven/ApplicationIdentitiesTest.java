package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationIdentitiesTest {
  @Test
  @SuppressWarnings("deprecation") // Maven 3 exposes only deprecated session constructors.
  void declaringModuleConfigurationSurvivesConsumerSessionAndCliOverrides(@TempDir Path path) {
    var library = SourceCatalogsTest.project("library", path.resolve("library"));
    final var consumer = SourceCatalogsTest.project("consumer", path.resolve("consumer"));
    library.getProperties().setProperty("product.id", "library-product");
    Plugin plugin = new Plugin();
    plugin.setGroupId("com.developmentsprint");
    plugin.setArtifactId("census-maven-plugin");
    plugin.setConfiguration(configuration("${product.id}"));
    library.getBuild().addPlugin(plugin);
    var request = new DefaultMavenExecutionRequest();
    var session =
        new MavenSession(
            null,
            (org.eclipse.aether.RepositorySystemSession) null,
            request,
            new DefaultMavenExecutionResult());
    session.setCurrentProject(consumer);
    var reactor = List.of(library, consumer);
    assertThat(ApplicationIdentities.resolve(reactor, session))
        .containsEntry("acme:library", "library-product")
        .containsEntry("acme:consumer", "acme:consumer");
    assertThat(session.getCurrentProject()).isSameAs(consumer);
    request.getUserProperties().setProperty("product.id", "cli-product");
    request.getUserProperties().setProperty("census.applicationId", "session-product");
    assertThat(ApplicationIdentities.resolve(reactor, session))
        .containsEntry("acme:library", "cli-product")
        .containsEntry("acme:consumer", "session-product");
    PluginExecution execution = new PluginExecution();
    execution.setId("observe");
    execution.addGoal("prepare-agent");
    execution.setConfiguration(configuration("execution-product"));
    plugin.addExecution(execution);
    assertThat(ApplicationIdentities.resolve(reactor, session))
        .containsEntry("acme:library", "execution-product");
    PluginExecution conflicting = new PluginExecution();
    conflicting.setId("second");
    conflicting.addGoal("prepare-agent");
    conflicting.setConfiguration(configuration("different-product"));
    plugin.addExecution(conflicting);
    assertThatThrownBy(() -> ApplicationIdentities.resolve(reactor, session))
        .hasMessageContaining("Conflicting prepare-agent application identities");
    plugin.getExecutions().clear();
    plugin.setConfiguration(configuration("${missing.product}"));
    assertThatThrownBy(() -> ApplicationIdentities.resolve(reactor, session))
        .hasMessageContaining("Unresolved application identity");
  }

  @Test
  @SuppressWarnings("deprecation") // Maven 3 exposes only deprecated session constructors.
  void managedOnlyConfigurationResolvesWithoutReplacingActivePlugin(@TempDir Path path) {
    var library = SourceCatalogsTest.project("library", path.resolve("library"));
    library.getProperties().setProperty("product.id", "managed-product");
    var managed = new Plugin();
    managed.setGroupId("com.developmentsprint");
    managed.setArtifactId("census-maven-plugin");
    managed.setConfiguration(configuration("${product.id}"));
    var management = new org.apache.maven.model.PluginManagement();
    management.addPlugin(managed);
    library.getBuild().setPluginManagement(management);
    var session =
        new MavenSession(
            null,
            (org.eclipse.aether.RepositorySystemSession) null,
            new DefaultMavenExecutionRequest(),
            new DefaultMavenExecutionResult());
    var reactor = List.of(library);
    assertThat(library.getPlugin("com.developmentsprint:census-maven-plugin")).isNull();
    assertThat(ApplicationIdentities.resolve(reactor, session))
        .containsEntry("acme:library", "managed-product");
    assertThat(library.getPlugin("com.developmentsprint:census-maven-plugin")).isNull();
    var active = managed.clone();
    active.setConfiguration(configuration("active-product"));
    library.getBuild().addPlugin(active);
    library.getBuild().flushPluginMap();
    assertThat(ApplicationIdentities.resolve(reactor, session))
        .containsEntry("acme:library", "active-product");
    assertThat(((Xpp3Dom) managed.getConfiguration()).getChild("applicationId").getValue())
        .isEqualTo("${product.id}");
  }

  private static Xpp3Dom configuration(String value) {
    Xpp3Dom configuration = new Xpp3Dom("configuration");
    Xpp3Dom application = new Xpp3Dom("applicationId");
    application.setValue(value);
    configuration.addChild(application);
    return configuration;
  }
}
