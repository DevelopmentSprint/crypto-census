package com.developmentsprint.cryptocensus.delivery;

import com.developmentsprint.cryptocensus.core.Cbom;
import java.util.Map;

/** Validates agent evidence or the current Maven merger envelope without inventing health data. */
public final class EvidenceValidator {
  private EvidenceValidator() {}

  /** Requires a complete recognized Census health envelope. */
  public static void validate(Cbom.Document document) {
    String schema = document.properties().get(Cbom.SCHEMA_PROPERTY);
    if (schema != null) {
      document.validateAgentHealth();
      return;
    }
    String project = document.properties().get("cryptocensus:project");
    if (project == null || project.isBlank()) {
      throw new IllegalArgumentException("Missing Maven project identity");
    }
    boolean input = false;
    for (Map.Entry<String, String> property : document.properties().entrySet()) {
      if (property.getKey().startsWith("cryptocensus:input:")) {
        if (property.getKey().length() <= "cryptocensus:input:".length()
            || !property.getValue().matches("sha256:[0-9a-f]{64}")) {
          throw new IllegalArgumentException("Invalid Maven input digest");
        }
        input = true;
      }
    }
    if (!input) {
      throw new IllegalArgumentException("Missing Maven input identity");
    }
    for (String name :
        new String[] {
          "transformErrors", "recordingFailures", "drainTimedOut", "dropped", "overflowedSites"
        }) {
      document.health("cryptocensus:" + name);
    }
    if (document.health("cryptocensus:dropped") != document.inventory().droppedObservations()
        || document.health("cryptocensus:overflowedSites")
            != document.inventory().overflowedSites()) {
      throw new IllegalArgumentException("Maven evidence counters are inconsistent");
    }
  }
}
