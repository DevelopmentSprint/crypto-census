package com.developmentsprint.cryptocensus.maven;

import com.developmentsprint.cryptocensus.assessment.ContextChanges;
import com.developmentsprint.cryptocensus.assessment.ContextStore;
import com.developmentsprint.cryptocensus.core.DocumentFiles;
import java.io.File;
import java.io.IOException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Applies an exported proposal to an explicitly selected durable context file. */
@Mojo(name = "context-apply", threadSafe = true)
public class ContextApplyMojo extends AbstractMojo {
  @Parameter(property = "census.contextFile")
  private File contextFile;

  @Parameter(property = "census.changesFile", required = true)
  private File changesFile;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (contextFile == null) {
      throw new MojoExecutionException(
          "Choose a durable private context destination with"
              + " -Dcensus.contextFile=/private/context.json (or an explicitly opted-in project"
              + " file). There is no target/ default; imported change sets cannot select the"
              + " destination.");
    }
    if (changesFile == null) {
      throw new MojoExecutionException(
          "Select an exported proposal with -Dcensus.changesFile=changes.json");
    }
    try {
      var changes = ContextChanges.parse(DocumentFiles.read(changesFile.toPath()));
      var applied = ContextStore.apply(contextFile.toPath(), changes);
      if (!applied.merge().conflicts().isEmpty()) {
        throw new MojoFailureException(
            "Context has "
                + applied.merge().conflicts().size()
                + " concurrent edit conflict(s); no changes were written. Reload the current"
                + " context in the report, review the conflicting fields and export a revised"
                + " proposal. Conflicts: "
                + applied.merge().conflicts().stream()
                    .map(conflict -> conflict.key().section() + ":" + conflict.key().id())
                    .collect(java.util.stream.Collectors.joining(", ")));
      }
      getLog()
          .info(
              applied.written()
                  ? "Context changes applied. Run census:assess to regenerate findings."
                  : "Context already contains these changes; no write needed.");
    } catch (IOException | IllegalArgumentException e) {
      throw new MojoExecutionException("Could not validate or apply the context proposal", e);
    }
  }
}
