package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.assessment.ContextChanges;
import com.developmentsprint.cryptocensus.assessment.ContextCodec;
import com.developmentsprint.cryptocensus.assessment.ContextDocument;
import com.developmentsprint.cryptocensus.core.ContextModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextApplyMojoTest {
  @TempDir Path temporary;

  @Test
  void requiresExplicitDestinationAndValidProposal() throws Exception {
    var mojo = new ContextApplyMojo();
    assertThatThrownBy(mojo::execute)
        .isInstanceOf(MojoExecutionException.class)
        .hasMessageContaining("durable private context destination");
    set(mojo, "contextFile", temporary.resolve("context.json").toFile());
    assertThatThrownBy(mojo::execute).hasMessageContaining("census.changesFile");
    Path changes = temporary.resolve("changes.json");
    Files.writeString(changes, "{}");
    set(mojo, "changesFile", changes.toFile());
    assertThatThrownBy(mojo::execute).hasMessageContaining("validate or apply");
    assertThat(temporary.resolve("context.json")).doesNotExist();
  }

  @Test
  void appliesIdempotentlyAndLeavesConcurrentConflictUnchanged() throws Exception {
    Path context = temporary.resolve("private/context.json");
    Path proposal = temporary.resolve("proposal.json");
    var initial = ContextDocument.empty();
    var expected = document("Archive");
    Files.write(proposal, ContextChanges.encode(ContextChanges.between(initial, expected)));
    var mojo = new ContextApplyMojo();
    set(mojo, "contextFile", context.toFile());
    set(mojo, "changesFile", proposal.toFile());
    mojo.execute();
    assertThat(ContextCodec.read(context)).isEqualTo(expected);
    final byte[] before = Files.readAllBytes(context);
    var modified = Files.getLastModifiedTime(context);
    mojo.execute();
    assertThat(Files.getLastModifiedTime(context)).isEqualTo(modified);
    Files.write(
        proposal, ContextChanges.encode(ContextChanges.between(initial, document("Different"))));
    assertThatThrownBy(mojo::execute)
        .isInstanceOf(MojoFailureException.class)
        .hasMessageContaining("no changes were written")
        .hasMessageContaining("FLOWS:flow:app:archive");
    assertThat(Files.readAllBytes(context)).isEqualTo(before);
  }

  private static ContextDocument document(String label) {
    return new ContextDocument(
        1,
        List.of(
            new ContextDocument.Flow(
                new ContextModel.Subject("app", "archive"),
                label,
                ContextDocument.State.ACTIVE,
                List.of())),
        List.of(),
        List.of(),
        List.of());
  }

  static void set(Object target, String name, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
