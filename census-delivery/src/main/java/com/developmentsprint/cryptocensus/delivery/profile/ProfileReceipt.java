package com.developmentsprint.cryptocensus.delivery.profile;

import java.time.Instant;

/** Independent upload/ingestion observations plus exact remote-readback proof when obtained. */
public record ProfileReceipt(
    String planSha256,
    AdapterResult observation,
    VerifiedRetention retention,
    Instant startedAt,
    Instant finishedAt,
    int exchanges,
    boolean successful,
    VerifiedRetention destinationReadback) {
  /** Constructs a receipt without a verified destination download. */
  public ProfileReceipt(
      String planSha256,
      AdapterResult observation,
      VerifiedRetention retention,
      Instant startedAt,
      Instant finishedAt,
      int exchanges,
      boolean successful) {
    this(planSha256, observation, retention, startedAt, finishedAt, exchanges, successful, null);
  }
}
