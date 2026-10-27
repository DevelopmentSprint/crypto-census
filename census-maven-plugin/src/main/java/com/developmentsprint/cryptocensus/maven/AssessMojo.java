package com.developmentsprint.cryptocensus.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Regenerates findings from saved run evidence without invoking application tests. */
@Mojo(name = "assess", threadSafe = true)
public class AssessMojo extends AbstractMojo {
  @Parameter(property = "census.inputDir")
  private File inputDir;

  /** Additional saved run directories for multi-module or multi-workload assessments. */
  @Parameter private List<File> inputDirectories = new ArrayList<>();

  /** Explicitly selected standalone or older CBOM filenames. */
  @Parameter private List<File> inputFiles = new ArrayList<>();

  @Parameter(property = "census.inputFile")
  private File inputFile;

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

  @Override
  public void execute() throws MojoExecutionException {
    List<Path> inputs = new ArrayList<>();
    if (inputDir != null) {
      inputs.add(inputDir.toPath());
    }
    inputDirectories.forEach(directory -> inputs.add(directory.toPath()));
    List<Path> files = new ArrayList<>();
    inputFiles.forEach(file -> files.add(file.toPath()));
    if (inputFile != null) {
      files.add(inputFile.toPath());
    }
    if (inputs.isEmpty() && files.isEmpty()) {
      throw new MojoExecutionException(
          "Select saved evidence with -Dcensus.inputDir=/path/to/run or"
              + " -Dcensus.inputFile=/path/to/cbom (POM lists: inputDirectories/inputFiles); assess"
              + " does not run tests or select a previous build implicitly.");
    }
    try {
      ReportWorkflow.write(
          inputs,
          files,
          contextFile,
          policyFile,
          assessmentOutputDirectory.toPath(),
          workload,
          environment);
      getLog().info("Assessment regenerated from saved evidence; no application tests were run.");
    } catch (IOException | IllegalArgumentException e) {
      throw new MojoExecutionException("Could not assess the selected evidence and context", e);
    }
  }
}
