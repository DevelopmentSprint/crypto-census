package com.developmentsprint.cryptocensus.delivery.preparation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Local originals, publication and immutable transformation manifest. Local retention is not remote
 * retention.
 */
public record PreparedBundle(
    List<PreparedArtifact> sources,
    PreparedArtifact publication,
    Path manifest,
    String manifestSha256,
    Correlation correlation,
    List<String> lossCodes,
    int unresolvedCount) {
  /** Validates the immutable input contract. */
  public PreparedBundle {
    sources = List.copyOf(sources);
    lossCodes = List.copyOf(lossCodes);
    Objects.requireNonNull(publication);
    Objects.requireNonNull(manifest);
    Objects.requireNonNull(correlation);
    PreparationIo.checkHash(manifestSha256);
    if (unresolvedCount < 0) {
      throw new IllegalArgumentException("Negative unresolved count");
    }
  }

  /** Verifies manifest and all referenced artifact bytes against the sealed bundle. */
  public void verify() throws IOException {
    PreparationIo.read(manifest, manifestSha256);
    for (PreparedArtifact source : sources) {
      source.readVerified();
    }
    publication.readVerified();
  }
}
