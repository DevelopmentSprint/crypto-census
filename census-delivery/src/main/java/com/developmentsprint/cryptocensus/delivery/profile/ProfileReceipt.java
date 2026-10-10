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
    boolean successful) {}
