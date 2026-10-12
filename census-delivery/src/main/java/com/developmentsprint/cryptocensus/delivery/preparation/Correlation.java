package com.developmentsprint.cryptocensus.delivery.preparation;

/** Explicit operator assertion of a common application build; not authenticated provenance. */
public record Correlation(String application, String module, String version, String runId) {
  /** Validates the immutable input contract. */
  public Correlation {
    for (String value : new String[] {application, module, version, runId}) {
      if (value == null || !value.matches("[\\x21-\\x7e]{1,256}")) {
        throw new IllegalArgumentException(
            "Correlation requires bounded printable application/module/version/run identity");
      }
    }
  }
}
