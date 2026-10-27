package com.developmentsprint.cryptocensus.maven;

import com.developmentsprint.cryptocensus.assessment.AssessmentEngine;
import com.developmentsprint.cryptocensus.assessment.AssessmentReport;
import com.developmentsprint.cryptocensus.assessment.ContextCodec;
import com.developmentsprint.cryptocensus.assessment.ContextDocument;
import com.developmentsprint.cryptocensus.assessment.PolicyProfile;
import com.developmentsprint.cryptocensus.core.DocumentFiles;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Both lifecycle and offline goals call the same authoritative Java assessment engine. */
final class ReportWorkflow {
  private ReportWorkflow() {}

  static AssessmentReport write(
      List<Path> inputs,
      File context,
      File policy,
      Path output,
      String workload,
      String environment)
      throws IOException {
    return write(inputs, List.of(), context, policy, output, workload, environment);
  }

  static AssessmentReport write(
      List<Path> inputs,
      List<Path> files,
      File context,
      File policy,
      Path output,
      String workload,
      String environment)
      throws IOException {
    var contextDocument =
        context == null ? ContextDocument.empty() : ContextCodec.read(context.toPath());
    var profile =
        policy == null
            ? PolicyProfile.defaults()
            : PolicyProfile.parse(DocumentFiles.read(policy.toPath()));
    final var report =
        AssessmentEngine.assess(
            ReportInputs.read(inputs, files, workload, environment), contextDocument, profile);
    Path json = output.resolve("assessment.json");
    protect(json, context);
    protect(json, policy);
    for (Path file : files) {
      protect(json, file.toFile());
    }
    report.write(json);
    return report;
  }

  private static void protect(Path output, File input) throws IOException {
    if (input != null && Files.exists(output) && Files.isSameFile(output, input.toPath())) {
      throw new IOException("Assessment output must not replace evidence, context or policy input");
    }
  }
}
