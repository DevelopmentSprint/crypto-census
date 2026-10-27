package com.developmentsprint.cryptocensus.delivery.preparation;

import java.nio.file.Path;
import java.util.Objects;

/** Source bytes bound to an operator-supplied digest and build identity. */
public record ArtifactInput(
    Path path, String expectedSha256, InputKind kind, Correlation correlation) {
  /** Validates the immutable input contract. */
  public ArtifactInput {
    Objects.requireNonNull(path);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(correlation);
    PreparationIo.checkHash(expectedSha256);
  }
}
