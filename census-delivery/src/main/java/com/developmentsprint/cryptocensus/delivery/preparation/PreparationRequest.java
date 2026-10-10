package com.developmentsprint.cryptocensus.delivery.preparation;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Offline preparation with an exclusively created output directory. */
public record PreparationRequest(Mode mode, List<ArtifactInput> inputs, Path outputDirectory) {
  /** Closed set of evidence transformations. */
  public enum Mode {
    IDENTITY,
    LINK,
    COMBINE
  }

  /** Validates the immutable input contract. */
  public PreparationRequest {
    Objects.requireNonNull(mode);
    inputs = List.copyOf(inputs);
    Objects.requireNonNull(outputDirectory);
    if (inputs.isEmpty() || inputs.size() > 2) {
      throw new IllegalArgumentException("Preparation requires one or two inputs");
    }
  }
}
