package com.developmentsprint.cryptocensus.maven;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.SourceCatalog;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.project.MavenProject;

/** Build-owned locations establish artifact identity; filenames and package names never do. */
final class SourceCatalogs {
  private SourceCatalogs() {}

  static SourceCatalog generate(
      List<MavenProject> reactor, List<SourceCatalog.Entry> dependencies, SourceCatalog overrides) {
    return generate(reactor, dependencies, overrides, Map.of());
  }

  static SourceCatalog generate(
      List<MavenProject> reactor,
      List<SourceCatalog.Entry> dependencies,
      SourceCatalog overrides,
      Map<String, String> applications) {
    Map<List<String>, SourceCatalog.Entry> entries = new LinkedHashMap<>();
    for (MavenProject module : reactor) {
      String artifact = module.getGroupId() + ":" + module.getArtifactId();
      String application = module.getProperties().getProperty("census.applicationId", artifact);
      var build = module.getBuild();
      if (build != null && !"pom".equals(module.getPackaging())) {
        add(
            entries,
            entry(
                build.getOutputDirectory(), artifact, application, ContextModel.Origin.MAIN, true));
        add(
            entries,
            entry(
                build.getTestOutputDirectory(),
                artifact,
                application,
                ContextModel.Origin.TEST,
                true));
        if (!repackaged(module)
            && module.getArtifact() != null
            && module.getArtifact().getFile() != null) {
          add(
              entries,
              entry(
                  module.getArtifact().getFile().getPath(),
                  artifact,
                  application,
                  ContextModel.Origin.MAIN,
                  false));
        }
        if (!repackaged(module)
            && "jar".equals(module.getPackaging())
            && build.getDirectory() != null
            && build.getFinalName() != null) {
          add(
              entries,
              entry(
                  Path.of(build.getDirectory(), build.getFinalName() + ".jar").toString(),
                  artifact,
                  application,
                  ContextModel.Origin.MAIN,
                  false));
        }
        for (var attached : module.getAttachedArtifacts()) {
          File file = attached.getFile();
          if (file != null && !repackaged(module)) {
            add(
                entries,
                entry(
                    file.getPath(),
                    artifact,
                    application,
                    "tests".equals(attached.getClassifier())
                        ? ContextModel.Origin.TEST
                        : ContextModel.Origin.MAIN,
                    false));
          }
        }
      }
    }
    for (SourceCatalog.Entry dependency : dependencies) {
      // A reactor artifact retains its declaring module's MAIN/TEST ownership in consumer tests.
      entries.putIfAbsent(key(dependency), dependency);
    }
    entries.replaceAll(
        (key, entry) ->
            applications.containsKey(entry.artifactId())
                    && (entry.origin() == ContextModel.Origin.MAIN
                        || entry.origin() == ContextModel.Origin.TEST)
                ? new SourceCatalog.Entry(
                    entry.locationUri(),
                    entry.artifactId(),
                    applications.get(entry.artifactId()),
                    entry.origin(),
                    entry.deploymentId(),
                    entry.loaderName(),
                    entry.qualification())
                : entry);
    for (SourceCatalog.Entry override : overrides.entries()) {
      entries.put(
          key(override),
          new SourceCatalog.Entry(
              override.locationUri(),
              override.artifactId(),
              override.applicationId(),
              override.origin(),
              override.deploymentId(),
              override.loaderName(),
              SourceCatalog.Qualification.EXPLICIT_DEPLOYMENT));
    }
    return new SourceCatalog(SourceCatalog.VERSION, List.copyOf(entries.values()));
  }

  static SourceCatalog.Entry dependency(
      File file,
      String artifact,
      String version,
      String extension,
      String classifier,
      List<MavenProject> reactor) {
    for (MavenProject module : reactor) {
      if (artifact.equals(module.getGroupId() + ":" + module.getArtifactId())
          && version.equals(module.getVersion())
          && compatible(module, extension, classifier)) {
        if (repackaged(module) && !file.isDirectory()) {
          return null;
        }
        return entry(
            file.getPath(),
            artifact,
            module.getProperties().getProperty("census.applicationId", artifact),
            "tests".equals(classifier) ? ContextModel.Origin.TEST : ContextModel.Origin.MAIN,
            file.isDirectory());
      }
    }
    return entry(
        file.getPath(), artifact, artifact, ContextModel.Origin.LIBRARY, file.isDirectory());
  }

  private static boolean compatible(MavenProject module, String extension, String classifier) {
    if (classifier.isEmpty()) {
      String mainExtension =
          module.getArtifact() == null
              ? module.getPackaging()
              : module.getArtifact().getArtifactHandler().getExtension();
      return extension.equals(mainExtension);
    }
    return module.getAttachedArtifacts().stream()
        .anyMatch(
            artifact ->
                classifier.equals(artifact.getClassifier())
                    && extension.equals(artifact.getArtifactHandler().getExtension()));
  }

  private static boolean repackaged(MavenProject module) {
    return module.getBuildPlugins().stream()
        .anyMatch(
            plugin ->
                "maven-shade-plugin".equals(plugin.getArtifactId())
                    || "maven-assembly-plugin".equals(plugin.getArtifactId()));
  }

  static SourceCatalog.Entry entry(
      String path,
      String artifact,
      String application,
      ContextModel.Origin origin,
      boolean directory) {
    if (path == null) {
      return null;
    }
    String uri = Path.of(path).toAbsolutePath().normalize().toUri().toString();
    if (directory && !uri.endsWith("/")) {
      uri += "/";
    }
    return new SourceCatalog.Entry(
        uri,
        artifact,
        application,
        origin,
        "maven:" + artifact,
        "",
        SourceCatalog.Qualification.GENERATED_BUILD);
  }

  private static void add(
      Map<List<String>, SourceCatalog.Entry> entries, SourceCatalog.Entry entry) {
    if (entry != null) {
      SourceCatalog.Entry prior = entries.putIfAbsent(key(entry), entry);
      if (prior != null && !prior.equals(entry)) {
        throw new IllegalArgumentException("Reactor modules declare conflicting source locations");
      }
    }
  }

  private static List<String> key(SourceCatalog.Entry entry) {
    return List.of(entry.locationUri(), entry.loaderName());
  }
}
