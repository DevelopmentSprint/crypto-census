package com.developmentsprint.cryptocensus.delivery.profile;

import java.util.Set;

/** Explicit acceptance requirements bound into the delivery plan. */
public record DeliveryPolicy(
    String capabilityRevision,
    Set<String> acceptedLossCodes,
    Completion completion,
    Set<String> requiredFields,
    boolean requireRemoteRetention) {
  /** Copies the exact named opt-ins. */
  public DeliveryPolicy {
    acceptedLossCodes = Set.copyOf(acceptedLossCodes);
    requiredFields = Set.copyOf(requiredFields);
  }

  /** Basic contract without stronger semantic or remote-retention requirements. */
  public DeliveryPolicy(String revision, Set<String> losses, Completion completion) {
    this(revision, losses, completion, Set.of(), false);
  }

  /** Requested operation gate; ingestion is stronger than HTTP acknowledgment. */
  public enum Completion {
    UPLOAD_ACCEPTED,
    INGESTION_COMPLETED
  }
}
