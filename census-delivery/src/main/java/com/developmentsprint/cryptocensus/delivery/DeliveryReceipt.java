package com.developmentsprint.cryptocensus.delivery;

/** Safe delivery result. HTTP acceptance is not confirmation of remote ingestion. */
public record DeliveryReceipt(
    Outcome outcome, String artifactSha256, String deliveryId, int httpStatus, String reasonCode) {
  /** Transport outcomes; UNKNOWN requires receiver reconciliation before a repeat submission. */
  public enum Outcome {
    HTTP_ACCEPTED,
    REJECTED,
    UNKNOWN,
    FAILED
  }

  /** True only for HTTP acceptance; does not assert successful remote ingestion. */
  public boolean successful() {
    return outcome == Outcome.HTTP_ACCEPTED;
  }
}
