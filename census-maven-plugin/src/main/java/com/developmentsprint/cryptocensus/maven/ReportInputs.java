package com.developmentsprint.cryptocensus.maven;

import com.developmentsprint.cryptocensus.assessment.AssessmentInputs;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.DocumentFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Explicit saved run directories; collection never searches unrelated or previous build output. */
final class ReportInputs {
  private ReportInputs() {}

  static List<AssessmentInputs.Input> read(
      List<Path> directories, String workload, String environment) throws IOException {
    return read(directories, List.of(), workload, environment);
  }

  static List<AssessmentInputs.Input> read(
      List<Path> directories, List<Path> selectedFiles, String workload, String environment)
      throws IOException {
    if ((directories.isEmpty() && selectedFiles.isEmpty())
        || directories.size() + selectedFiles.size() > AssessmentInputs.MAX_RUNS) {
      throw new IOException(
          "Select between 1 and "
              + AssessmentInputs.MAX_RUNS
              + " saved run directories or explicit files");
    }
    Set<Path> files = new TreeSet<>();
    for (Path directory : directories) {
      try (var stream = Files.newDirectoryStream(directory, "cbom-*.json")) {
        for (Path file : stream) {
          if (!java.util.Objects.requireNonNull(file.getFileName())
              .toString()
              .endsWith(".evidence.json")) {
            add(files, file);
          }
        }
      }
    }
    for (Path file : selectedFiles) {
      add(files, file);
    }
    if (files.isEmpty()) {
      throw new IOException("No cbom-*.json inputs in the selected saved run directories");
    }
    long total = 0;
    List<AssessmentInputs.Input> inputs = new ArrayList<>();
    for (Path file : files) {
      byte[] cbom = Cbom.readBytes(file);
      String name = java.util.Objects.requireNonNull(file.getFileName()).toString();
      String stem = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
      Path sidecar = file.resolveSibling(stem + ".evidence.json");
      byte[] evidence = null;
      boolean unreadable = false;
      if (!Files.notExists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
        try {
          evidence = DocumentFiles.read(sidecar);
        } catch (IOException e) {
          unreadable = true;
        }
      }
      total += cbom.length + (evidence == null ? 0L : evidence.length);
      if (total > AssessmentInputs.MAX_TOTAL_BYTES) {
        throw new IOException("Assessment inputs exceed total byte budget");
      }
      inputs.add(
          unreadable
              ? AssessmentInputs.unreadableEvidence(cbom, workload, environment)
              : new AssessmentInputs.Input(cbom, evidence, workload, environment));
    }
    return List.copyOf(inputs);
  }

  private static void add(Set<Path> files, Path file) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Assessment inputs must be regular files, not links");
    }
    files.add(file.toRealPath());
    if (files.size() > AssessmentInputs.MAX_RUNS) {
      throw new IOException("Too many assessment input files");
    }
  }
}
