package com.developmentsprint.cryptocensus.maven;

import com.developmentsprint.cryptocensus.core.ContextModel;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Resolves declaring modules' effective identity configuration without changing the build session.
 */
final class ApplicationIdentities {
  private ApplicationIdentities() {}

  static Map<String, String> resolve(List<MavenProject> reactor, MavenSession session) {
    Map<String, String> result = new LinkedHashMap<>();
    for (MavenProject module : reactor) {
      String artifact = module.getGroupId() + ":" + module.getArtifactId();
      MavenSession isolated = session.clone();
      isolated.setCurrentProject(module);
      Plugin plugin = module.getPlugin("com.developmentsprint:census-maven-plugin");
      if (plugin == null) {
        plugin = new Plugin();
        plugin.setGroupId("com.developmentsprint");
        plugin.setArtifactId("census-maven-plugin");
      }
      Set<String> identities = new HashSet<>();
      Object configuration = plugin.getConfiguration();
      for (var execution : plugin.getExecutions()) {
        if (execution.getGoals().contains("prepare-agent")) {
          Object effective = execution.getConfiguration();
          String value = parameter(effective);
          identities.add(
              evaluate(
                  isolated,
                  plugin,
                  execution.getId(),
                  value == null ? parameter(configuration) : value,
                  artifact));
        }
      }
      if (identities.isEmpty()) {
        identities.add(
            evaluate(isolated, plugin, "default-cli", parameter(configuration), artifact));
      }
      if (identities.size() != 1) {
        throw new IllegalArgumentException(
            "Conflicting prepare-agent application identities for declaring artifact " + artifact);
      }
      result.put(artifact, identities.iterator().next());
    }
    return Map.copyOf(result);
  }

  private static String parameter(Object configuration) {
    if (configuration instanceof Xpp3Dom xml) {
      Xpp3Dom parameter = xml.getChild("applicationId");
      return parameter == null ? null : parameter.getValue();
    }
    return null;
  }

  private static String evaluate(
      MavenSession session, Plugin plugin, String executionId, String configured, String fallback) {
    try {
      Object value =
          new PluginParameterExpressionEvaluator(
                  session, new MojoExecution(plugin, "prepare-agent", executionId))
              .evaluate(configured == null ? "${census.applicationId}" : configured, String.class);
      if (value == null && configured != null && !configured.isBlank()) {
        throw new IllegalArgumentException("Unresolved application identity for " + fallback);
      }
      if (value == null || value.toString().isBlank()) {
        return fallback;
      }
      String application = value.toString();
      if (application.contains("${")) {
        throw new IllegalArgumentException("Unresolved application identity for " + fallback);
      }
      return ContextModel.text(application, "applicationId", 256, false);
    } catch (ExpressionEvaluationException failure) {
      throw new IllegalArgumentException(
          "Cannot resolve application identity for " + fallback, failure);
    }
  }
}
