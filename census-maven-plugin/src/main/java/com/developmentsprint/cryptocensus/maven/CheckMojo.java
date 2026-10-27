package com.developmentsprint.cryptocensus.maven;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Report;
import com.developmentsprint.cryptocensus.core.Risk;
import com.developmentsprint.cryptocensus.core.Targets;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Merges the per-JVM CBOMs written during this build into one {@code cbom.json}, prints a summary
 * and fails the build when an asset at or above {@link #failOn} was observed.
 *
 * <p>Enforcement fails closed: missing data, agent errors or dropped observations fail the build by
 * default, because "no findings" from an agent that never ran is not a pass.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class CheckMojo extends AbstractMojo {

  static final int MAX_INPUT_FILES = 10_000;
  static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
  static final int MAX_MERGED_ASSETS = 5_000;
  static final int MAX_MERGED_SITES = 200;

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  /** Merged CBOM. */
  @Parameter(
      property = "census.output",
      defaultValue = "${project.build.directory}/cryptocensus/cbom.json")
  private File output;

  /**
   * Lowest risk that fails the build: {@code broken}, {@code weak}, {@code quantum-vulnerable}, or
   * {@code none} to only report.
   */
  @Parameter(property = "census.failOn", defaultValue = "broken")
  private String failOn;

  /** Generate a canonical business-context assessment for this build's exact run. */
  @Parameter(property = "census.assessment", defaultValue = "false")
  private boolean assessment;

  @Parameter(property = "census.contextFile")
  private File contextFile;

  @Parameter(property = "census.policyFile")
  private File policyFile;

  @Parameter(
      property = "census.assessmentOutputDirectory",
      defaultValue = "${project.build.directory}/cryptocensus/assessment")
  private File assessmentOutputDirectory;

  @Parameter(property = "census.workload", defaultValue = "unspecified")
  private String workload;

  @Parameter(property = "census.environment", defaultValue = "unspecified")
  private String environment;

  /**
   * Also fail on assets the policy cannot judge (unrecognized algorithms, unobserved key strength).
   * On by default: an algorithm the policy cannot classify is not evidence that it is safe.
   */
  @Parameter(property = "census.failOnUnknown", defaultValue = "true")
  private boolean failOnUnknown;

  /**
   * Fail when tests ran but no CBOM was produced (agent not attached, argLine overridden,
   * forkCount=0).
   */
  @Parameter(property = "census.failOnMissingData", defaultValue = "true")
  private boolean failOnMissingData;

  /**
   * Fail when the agent reported instrumentation errors, recording failures or dropped
   * observations.
   */
  @Parameter(property = "census.failOnIncompleteCollection", defaultValue = "true")
  private boolean failOnIncompleteCollection;

  /**
   * Fail when a JDK class the agent always instruments was absent, not transformed, or had none of
   * the expected methods in some test JVM (for example a newer JDK that renamed {@code
   * sun.security.ssl.SSLSessionImpl}): that kind of cryptography was then not observed. Off by
   * default, because an unfamiliar JDK build can legitimately lack a JDK-internal class; the gap is
   * always reported as a warning.
   */
  @Parameter(property = "census.failOnMissingTargets", defaultValue = "false")
  private boolean failOnMissingTargets;

  /**
   * Algorithms accepted regardless of risk, matched case-insensitively against the asset's
   * canonical algorithm or display name (for example {@code SHA-1} for a legacy checksum you have
   * signed off).
   */
  @Parameter private List<String> allow = new ArrayList<>();

  /**
   * Ignore assets used only by the JDK itself with no application frame on the stack (jar
   * verification, certificate path validation).
   */
  @Parameter(property = "census.ignoreJdkInternal", defaultValue = "false")
  private boolean ignoreJdkInternal;

  @Parameter(property = "census.skip", defaultValue = "false")
  private boolean skip;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("Skipping Crypto Census");
      return;
    }
    Object runDirValue = project.getContextValue(PrepareAgentMojo.RUN_DIR_CONTEXT);
    if (runDirValue == null) {
      throw new MojoExecutionException(
          "census:check needs census:prepare-agent to run earlier in the same build");
    }
    String runDir = runDirValue.toString();

    // Whatever the skip flags say, evidence that exists is always checked: -DskipTests may skip
    // Surefire while Failsafe (or an explicitly configured execution) still runs.
    Map<String, String> inputs = new LinkedHashMap<>();
    // bounded like the agent's own inventory: exceeding it is reported as dropped (incomplete)
    Inventory merged = Inventory.bounded(MAX_MERGED_ASSETS, MAX_MERGED_SITES);
    long transformErrors = 0;
    long recordingFailures = 0;
    long drainTimedOut = 0;
    List<Map<String, Targets.Status>> targets = new ArrayList<>();
    for (Path file : inputs(Path.of(runDir))) {
      try {
        byte[] bytes = Cbom.readBytes(file); // read once ...
        Cbom.Document doc = Cbom.parse(bytes); // ... parse those bytes ...
        doc.validateAgentHealth(); // schema, every counter present and numeric, counters consistent
        // absent (an older agent, or one that could not determine its targets) parses as empty,
        // which targetReport counts as a gap: no evidence of coverage is not evidence of coverage
        targets.add(Targets.parse(doc.properties().get(Targets.PROPERTY)));
        merged.mergeFrom(doc.inventory());
        transformErrors = add(transformErrors, doc.health("cryptocensus:transformErrors"));
        recordingFailures = add(recordingFailures, doc.health("cryptocensus:recordingFailures"));
        drainTimedOut = add(drainTimedOut, doc.health("cryptocensus:drainTimedOut"));
        inputs.put(String.valueOf(file.getFileName()), sha256(bytes)); // ... and hash exactly them
      } catch (IOException | IllegalArgumentException e) {
        throw new MojoExecutionException("Invalid CBOM " + file + ": " + e.getMessage(), e);
      }
    }

    if (inputs.isEmpty()) {
      // Absence of evidence is acceptable only when we can positively establish that no tests ran:
      // fresh test reports prove the opposite, and silence (reports disabled or relocated) is not
      // proof.
      String noTests = testsRanInThisBuild() ? null : establishedNoTests();
      if (noTests != null) {
        getLog().info(noTests + "; nothing for Crypto Census to check");
        return;
      }
      String message =
          "No Crypto Census data was produced although tests ran. The agent was probably not"
              + " attached: check that Surefire/Failsafe use ${argLine} (or @{argLine}), that"
              + " forkCount is not 0, and that argLine is not replaced elsewhere.";
      if (session.getUserProperties().containsKey("argLine")) {
        message = PrepareAgentMojo.commandLineOverrideHint("argLine") + " " + message;
      }
      if (!failOnMissingData) {
        getLog().warn(message);
        return;
      }
      throw new MojoFailureException(message + " Set failOnMissingData=false to accept this.");
    }

    final List<Inventory.Entry> snapshot;
    final long dropped = merged.droppedObservations();
    Map<String, String> meta = new LinkedHashMap<>();
    meta.put(
        "cryptocensus:project",
        project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion());
    meta.put("cryptocensus:transformErrors", String.valueOf(transformErrors));
    meta.put("cryptocensus:recordingFailures", String.valueOf(recordingFailures));
    meta.put("cryptocensus:drainTimedOut", String.valueOf(drainTimedOut));
    Map<String, Targets.Status> mergedTargets = Targets.merge(targets);
    meta.put(Targets.PROPERTY, Targets.format(mergedTargets));
    meta.put(Targets.MISSING_PROPERTY, Targets.format(Targets.missing(mergedTargets)));
    inputs.forEach((name, hash) -> meta.put("cryptocensus:input:" + name, "sha256:" + hash));
    try {
      Cbom.Metadata metadata = new Cbom.Metadata(project.getArtifactId(), meta);
      snapshot = Cbom.boundedEntries(merged.entries(), dropped, metadata);
      Cbom.write(snapshot, dropped, metadata, output.toPath());
    } catch (IOException | IllegalArgumentException e) {
      throw new MojoExecutionException("Could not write " + output, e);
    }

    for (String line : Report.text(snapshot, dropped).lines().toList()) {
      getLog().info(line);
    }
    getLog()
        .info(
            "CBOM: "
                + output
                + " (merged from "
                + inputs.size()
                + " JVM"
                + (inputs.size() == 1 ? "" : "s")
                + ")");

    List<String> problems = new ArrayList<>();
    long overflowed = Cbom.overflowedSites(snapshot);
    if (overflowed > 0) {
      // every asset and verdict is still present; only where-it-was-called is truncated
      getLog()
          .warn(
              overflowed
                  + " observation(s) lost their call-site attribution (collection limits or CBOM"
                  + " serialization budget)");
    }
    if (transformErrors > 0 || recordingFailures > 0 || dropped > 0 || drainTimedOut > 0) {
      String message =
          "Incomplete collection: "
              + transformErrors
              + " instrumentation error(s), "
              + recordingFailures
              + " recording failure(s), "
              + dropped
              + " dropped observation(s), "
              + drainTimedOut
              + " operation(s) still running at shutdown";
      if (failOnIncompleteCollection) {
        problems.add(message + " (set failOnIncompleteCollection=false to accept)");
      } else {
        getLog().warn(message);
      }
    }

    TargetReport targetReport = targetReport(targets, failOnMissingTargets);
    targetReport.info().forEach(getLog()::info);
    targetReport.warnings().forEach(getLog()::warn);
    targetReport.failure().ifPresent(problems::add);

    List<Inventory.Entry> unknown =
        snapshot.stream()
            .filter(
                e ->
                    e.assessment().risk() == Risk.UNKNOWN
                        && !isAllowed(e)
                        && !(ignoreJdkInternal && jdkOnly(e)))
            .collect(Collectors.toList());
    if (!unknown.isEmpty()) {
      String message = unknown.size() + " asset(s) the policy cannot judge:\n" + describe(unknown);
      if (failOnUnknown) {
        problems.add(message);
      } else {
        getLog().warn(message + "\nSet failOnUnknown=true to enforce.");
      }
    }

    if (!isNone(failOn)) {
      Risk threshold;
      try {
        threshold = Risk.parse(failOn);
      } catch (IllegalArgumentException e) {
        throw new MojoExecutionException(
            "Invalid failOn '" + failOn + "'; use broken, weak, quantum-vulnerable or none", e);
      }
      List<Inventory.Entry> violations =
          snapshot.stream()
              .filter(
                  e ->
                      e.assessment().risk() != Risk.UNKNOWN
                          && e.assessment().risk().isAtLeast(threshold))
              .filter(e -> !isAllowed(e))
              .filter(e -> !(ignoreJdkInternal && jdkOnly(e)))
              .collect(Collectors.toList());
      if (!violations.isEmpty()) {
        problems.add(
            violations.size()
                + " cryptographic asset(s) at or above '"
                + threshold.label()
                + "':\n"
                + describe(violations)
                + "\nAllow specific algorithms with <allow>, or raise <failOn>.");
      }
    }

    MojoExecutionException assessmentFailure = null;
    if (assessment) {
      try {
        ReportWorkflow.write(
            List.of(Path.of(runDir)),
            contextFile,
            policyFile,
            assessmentOutputDirectory.toPath(),
            workload,
            environment);
      } catch (IOException | IllegalArgumentException | java.time.DateTimeException e) {
        assessmentFailure =
            new MojoExecutionException(
                "Could not generate the requested business-context assessment", e);
      }
    }
    if (!problems.isEmpty()) {
      if (assessmentFailure != null) {
        problems.add(assessmentFailure.getMessage());
      }
      MojoFailureException failure = new MojoFailureException(String.join("\n\n", problems));
      if (assessmentFailure != null) {
        failure.addSuppressed(assessmentFailure);
      }
      throw failure;
    }
    if (assessmentFailure != null) {
      throw assessmentFailure;
    }
  }

  private List<Path> inputs(Path dir) throws MojoExecutionException {
    List<Path> files = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return files;
    }
    long total = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "cbom-*.json")) {
      for (Path file : stream) {
        if (java.util.Objects.requireNonNull(file.getFileName())
            .toString()
            .endsWith(".evidence.json")) {
          continue;
        }
        if (files.size() >= MAX_INPUT_FILES) {
          throw new MojoExecutionException("More than " + MAX_INPUT_FILES + " CBOMs in " + dir);
        }
        // advisory early stop; each file's real size is enforced again while it is read
        total += Files.size(file);
        if (total > MAX_TOTAL_BYTES) {
          throw new MojoExecutionException(
              "CBOMs in " + dir + " exceed " + MAX_TOTAL_BYTES + " bytes in total");
        }
        files.add(file);
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Could not list CBOMs in " + dir, e);
    }
    files.sort(null);
    return files;
  }

  /** Whether {@code failOn} is the keyword {@code none}, which turns the risk threshold off. */
  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "failOn comes from the build owner's own POM or command line, the party the gate serves;"
              + " no character outside ASCII case-folds to the letters of none anyway")
  private static boolean isNone(String failOn) {
    return "none".equalsIgnoreCase(failOn.trim());
  }

  /**
   * What to say about the fixed instrumentation targets.
   *
   * @param info targets nothing exercised
   * @param warnings gaps, when they only warn
   * @param failure gaps, when {@code failOnMissingTargets} makes them fail the build
   */
  record TargetReport(List<String> info, List<String> warnings, Optional<String> failure) {}

  /**
   * Judges the per-JVM target statuses: never-loaded targets are informational, gaps (absent, not
   * transformed, no matching methods) warn, or fail the build when configured to.
   */
  static TargetReport targetReport(
      List<Map<String, Targets.Status>> perJvm, boolean failOnMissingTargets) {
    List<String> info = new ArrayList<>();
    List<String> notLoaded = Targets.notLoaded(Targets.merge(perJvm));
    if (!notLoaded.isEmpty()) {
      info.add("Not exercised by any test JVM (never loaded): " + String.join(", ", notLoaded));
    }
    List<String> gaps = new ArrayList<>(Targets.describeGaps(perJvm));
    long unreported = perJvm.stream().filter(Map::isEmpty).count();
    if (unreported > 0) {
      gaps.add(
          unreported
              + " of "
              + perJvm.size()
              + " test JVM(s) reported no instrumentation targets (an older agent, or the agent"
              + " could not determine them), so their coverage is unknown");
    }
    if (gaps.isEmpty()) {
      return new TargetReport(info, List.of(), Optional.empty());
    }
    String message =
        "Instrumentation gap: some cryptography could not be observed:\n  "
            + String.join("\n  ", gaps);
    return failOnMissingTargets
        ? new TargetReport(
            info,
            List.of(),
            Optional.of(message + "\n(set failOnMissingTargets=false to only warn)"))
        : new TargetReport(info, List.of(message), Optional.empty());
  }

  /** Saturating add: health counters must never wrap to a value that reads as healthy. */
  private static long add(long a, long b) {
    long r = a + b;
    return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
  }

  /**
   * Why it is certain that no tests ran, or null when that cannot be established: the module has no
   * compiled tests, or all test execution was explicitly skipped (and no fresh report says
   * otherwise). {@code skipITs} alone is not enough: it skips Failsafe, never Surefire.
   */
  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification =
          "lists the module's own test output directory as configured in its POM; the build owner"
              + " chooses that path")
  private String establishedNoTests() {
    File testClasses = new File(project.getBuild().getTestOutputDirectory());
    String[] children = testClasses.list();
    if (children == null || children.length == 0) {
      return "No compiled tests in " + project.getArtifactId();
    }
    for (String key : new String[] {"skipTests", "maven.test.skip"}) {
      String v =
          session.getUserProperties().getProperty(key, project.getProperties().getProperty(key));
      if (v != null && (v.isEmpty() || Boolean.parseBoolean(v))) {
        return "Tests were skipped (" + key + ")";
      }
    }
    return null;
  }

  /** Surefire/Failsafe report directories this mojo considers when deciding whether tests ran. */
  @Parameter(
      defaultValue =
          "${project.build.directory}/surefire-reports,${project.build.directory}/failsafe-reports")
  private List<File> testReportDirectories;

  /**
   * True when a test report was written during this build. Reports are what Surefire and Failsafe
   * leave behind whenever tests actually execute, whatever the skip properties said.
   */
  private boolean testsRanInThisBuild() {
    // Maven's API exposes java.util.Date; convert at the boundary
    long buildStart =
        Optional.ofNullable(session.getRequest().getStartTime())
            .map(start -> start.toInstant().toEpochMilli())
            .orElse(0L);
    for (File dir : testReportDirectories) {
      File[] reports = dir.listFiles((d, name) -> name.endsWith(".xml") || name.endsWith(".txt"));
      if (reports == null) {
        continue;
      }
      for (File report : reports) {
        if (report.lastModified() >= buildStart - 1_000) {
          return true;
        }
      }
    }
    return false;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String describe(List<Inventory.Entry> entries) {
    return entries.stream()
        .map(
            e ->
                "  "
                    + e.asset().displayName()
                    + (e.asset().operation() != null
                        ? " (" + e.asset().operation().label() + ")"
                        : "")
                    + " ["
                    + e.assessment().risk().label()
                    + "] "
                    + e.assessment().reason()
                    + "\n    first seen at "
                    + e.occurrences().keySet().iterator().next())
        .collect(Collectors.joining("\n"));
  }

  private boolean isAllowed(Inventory.Entry e) {
    String algorithm = e.asset().algorithm().toLowerCase(Locale.ROOT);
    String display = e.asset().displayName().toLowerCase(Locale.ROOT);
    return allow.stream()
        .map(a -> a.trim().toLowerCase(Locale.ROOT))
        .anyMatch(a -> a.equals(algorithm) || a.equals(display));
  }

  static boolean jdkOnly(Inventory.Entry e) {
    return e.occurrences().keySet().stream()
        .allMatch(o -> Occurrence.JDK_LOCATION.equals(o.location()));
  }
}
