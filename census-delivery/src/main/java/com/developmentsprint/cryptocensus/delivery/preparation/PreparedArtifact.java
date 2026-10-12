package com.developmentsprint.cryptocensus.delivery.preparation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** An artifact whose exact bytes must be reverified before use. */
public record PreparedArtifact(String id, InputKind kind, Path path, String sha256) {
  /** Validates the immutable input contract. */
  public PreparedArtifact {
    Objects.requireNonNull(id);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(path);
    PreparationIo.checkHash(sha256);
  }

  /** Reads bounded bytes once and verifies the bound digest; no network access. */
  public byte[] readVerified() throws IOException {
    return PreparationIo.read(path, sha256);
  }
}
