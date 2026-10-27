package com.developmentsprint.cryptocensus.maven;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.DocumentFiles;
import com.developmentsprint.cryptocensus.core.Escapes;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.SourceCatalog;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;

/**
 * Sets a property (by default {@code argLine}, which Surefire and Failsafe pick up) so that test
 * JVMs run with the Crypto Census agent attached.
 *
 * <p>Each build gets its own run directory under {@link #destDir}; every forked JVM writes {@code
 * cbom-<pid>-<random>.json} there and {@code census:check} merges exactly that run. Nothing is
 * deleted, so concurrent builds or modules sharing a {@code destDir} cannot erase each other's
 * evidence.
 */
@Mojo(name = "prepare-agent", defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true)
public class PrepareAgentMojo extends AbstractMojo {

  /** Unguessable names, so nobody can pre-create a file or directory we are about to write. */
  private static final SecureRandom RANDOM = new SecureRandom();

  static final String AGENT_KEY = "com.developmentsprint:census-agent";

  /**
   * Key of the per-build project context value holding the run directory. Context values are set by
   * plugins during this build only, so a POM or profile cannot pre-seed them.
   */
  static final String RUN_DIR_CONTEXT = PrepareAgentMojo.class.getName() + ".runDir";

  /** Informational project property mirroring the run directory (not trusted by check). */
  static final String RUN_DIR_PROPERTY = "cryptocensus.runDir";

  /** Matches an agent option this mojo added earlier, quoted or not, so re-running replaces it. */
  private static final Pattern OUR_AGENT =
      Pattern.compile(
          "\\s*(\"-javaagent:[^\"]*census-agent[^\"]*\"|-javaagent:\\S*census-agent\\S*)");

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Parameter(defaultValue = "${plugin.artifactMap}", readonly = true, required = true)
  private Map<String, Artifact> pluginArtifactMap;

  /** Parent directory for per-run CBOM directories. */
  @Parameter(property = "census.destDir", defaultValue = "${project.build.directory}/cryptocensus")
  private File destDir;

  /**
   * Property to append the {@code -javaagent} option to. To combine with your own Surefire {@code
   * <argLine>}, set this to e.g. {@code censusArgLine} and write {@code <argLine>@{censusArgLine}
   * -Xmx1g</argLine>}.
   */
  @Parameter(property = "census.propertyName", defaultValue = "argLine")
  private String propertyName;

  @Inject private ProjectDependenciesResolver dependenciesResolver;

  /** Collect business-context evidence independently of the core inventory. */
  @Parameter(property = "census.enrichment", defaultValue = "false")
  private boolean enrichment;

  @Parameter(property = "census.tlsCorrelation", defaultValue = "false")
  private boolean tlsCorrelation;

  /** Explicit stable product identity; never inferred from the display name. */
  @Parameter(property = "census.applicationId")
  private String applicationId;

  /** Optional explicit catalog entries override generated build ownership. */
  @Parameter(property = "census.sourcesFile")
  private File sourcesFile;

  /** Record negotiated TLS sessions. */
  @Parameter(property = "census.tls", defaultValue = "true")
  private boolean tls;

  /** Maximum distinct assets retained per test JVM (1..2000). */
  @Parameter(property = "census.maxAssets", defaultValue = "500")
  private int maxAssets;

  /** Maximum call sites retained per asset (1..100). */
  @Parameter(property = "census.maxSites", defaultValue = "50")
  private int maxSites;

  /** Log agent instrumentation errors to aid diagnosis. */
  @Parameter(property = "census.debug", defaultValue = "false")
  private boolean debug;

  @Parameter(property = "census.skip", defaultValue = "false")
  private boolean skip;

  @Override
  public void execute() throws MojoExecutionException {
    if (skip) {
      getLog().info("Skipping Crypto Census");
      return;
    }
    validateLimits(maxAssets, maxSites);
    Artifact agent = pluginArtifactMap.get(AGENT_KEY);
    if (agent == null || agent.getFile() == null) {
      throw new MojoExecutionException(
          "Could not resolve " + AGENT_KEY + " from the plugin's dependencies");
    }
    String jar = agent.getFile().getAbsolutePath();
    if (jar.indexOf('"') >= 0) {
      throw new MojoExecutionException(
          "Agent path contains a double quote, which argLine cannot express: " + jar);
    }

    File runDir = runDirectory();

    // Option values are percent-encoded, so commas, '=', quotes and spaces in paths cannot inject
    // options.
    String javaagent =
        "-javaagent:"
            + jar
            + "=dir="
            + Escapes.encode(runDir.getAbsolutePath())
            + ",app="
            + Escapes.encode(project.getArtifactId())
            + ",summary=false"
            + ",tls="
            + tls
            + ",maxAssets="
            + maxAssets
            + ",maxSites="
            + maxSites
            + ",debug="
            + debug
            + enrichmentOptions(runDir);
    String option =
        jar.chars().anyMatch(Character::isWhitespace) ? "\"" + javaagent + "\"" : javaagent;

    Properties projectProps = project.getProperties();
    projectProps.setProperty(propertyName, append(projectProps.getProperty(propertyName), option));
    project.setContextValue(RUN_DIR_CONTEXT, runDir.getAbsolutePath());
    projectProps.setProperty(RUN_DIR_PROPERTY, runDir.getAbsolutePath());

    if (session.getUserProperties().containsKey(propertyName)) {
      // A -D<property> on the command line wins over project properties when Surefire is
      // configured, and a plugin cannot change that. census:check will fail on missing data.
      getLog().warn(commandLineOverrideHint(propertyName));
    } else {
      getLog()
          .info("Crypto Census agent will attach to forked test JVMs via ${" + propertyName + "}");
    }
    // Deliberately not logging the property value: an inherited argLine can carry secrets.
    getLog().debug("CBOMs for this run: " + runDir);
  }

  private String enrichmentOptions(File runDir) throws MojoExecutionException {
    if (!enrichment) {
      return "";
    }
    try {
      var request = new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
      var result = dependenciesResolver.resolve(request);
      List<MavenProject> reactor = session.getAllProjects();
      if (reactor == null || reactor.isEmpty()) {
        reactor = List.of(project);
      }
      List<SourceCatalog.Entry> dependencies = new ArrayList<>();
      for (var dependency : result.getResolvedDependencies()) {
        var artifact = dependency.getArtifact();
        if (artifact.getFile() != null) {
          String id = artifact.getGroupId() + ":" + artifact.getArtifactId();
          SourceCatalog.Entry entry =
              SourceCatalogs.dependency(
                  artifact.getFile(),
                  id,
                  artifact.getBaseVersion(),
                  artifact.getExtension(),
                  artifact.getClassifier(),
                  reactor);
          if (entry != null) {
            dependencies.add(entry);
          }
        }
      }
      SourceCatalog overrides =
          sourcesFile == null
              ? new SourceCatalog(SourceCatalog.VERSION, List.of())
              : SourceCatalog.parse(DocumentFiles.read(sourcesFile.toPath()));
      SourceCatalog catalog = SourceCatalogs.generate(reactor, dependencies, overrides);
      Path destination = runDir.toPath().resolve("sources.json");
      DocumentFiles.write(destination, catalog.encode());
      String options =
          ",enrichment=true,tlsCorrelation="
              + tlsCorrelation
              + ",sourcesFile="
              + Escapes.encode(destination.toAbsolutePath().toString());
      if (applicationId != null && !applicationId.isBlank()) {
        ContextModel.text(applicationId, "applicationId", 256, false);
        options += ",applicationId=" + Escapes.encode(applicationId);
      }
      return options;
    } catch (IOException | IllegalArgumentException | DependencyResolutionException e) {
      throw new MojoExecutionException(
          "Could not prepare explicit source ownership for enrichment", e);
    }
  }

  /**
   * One run directory per project and build: a second prepare-agent execution (for example before
   * Failsafe) reuses it, so check sees the unit-test JVMs too. New directories are created
   * exclusively with a 64-bit random name, so concurrent builds cannot share one by accident.
   */
  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification =
          "destDir is the plugin's own parameter (POM or -Dcensus.destDir), set by the build owner,"
              + " and the context value is the directory this mojo created earlier in the build")
  private File runDirectory() throws MojoExecutionException {
    Object existing = project.getContextValue(RUN_DIR_CONTEXT);
    if (existing != null) {
      return new File(existing.toString());
    }
    try {
      java.nio.file.Files.createDirectories(destDir.toPath());
      for (int attempt = 0; attempt < 5; attempt++) {
        File candidate =
            new File(
                destDir,
                LocalDateTime.now(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + "-"
                    + Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE));
        try {
          java.nio.file.Files.createDirectory(candidate.toPath());
          return candidate;
        } catch (java.nio.file.FileAlreadyExistsException collision) {
          getLog().debug("Run directory " + candidate + " already exists; trying another name");
        }
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Could not create a run directory under " + destDir, e);
    }
    throw new MojoExecutionException("Could not allocate a unique run directory under " + destDir);
  }

  static void validateLimits(int assets, int sites) throws MojoExecutionException {
    if (assets < 1 || assets > Inventory.MAX_ASSETS) {
      throw new MojoExecutionException(
          "census.maxAssets must be between 1 and " + Inventory.MAX_ASSETS);
    }
    if (sites < 1 || sites > Inventory.MAX_SITES) {
      throw new MojoExecutionException(
          "census.maxSites must be between 1 and " + Inventory.MAX_SITES);
    }
  }

  static String commandLineOverrideHint(String propertyName) {
    return "-D"
        + propertyName
        + " on the command line replaces the Crypto Census agent option, so the agent will not"
        + " attach. Put those JVM options in the POM instead, or use a separate property:"
        + " -Dcensus.propertyName=censusArgLine with <argLine>@{censusArgLine} ${"
        + propertyName
        + "}</argLine> in the Surefire configuration.";
  }

  static String append(String existing, String option) {
    String base = existing == null ? "" : OUR_AGENT.matcher(existing).replaceAll("").trim();
    return base.isEmpty() ? option : base + " " + option;
  }
}
