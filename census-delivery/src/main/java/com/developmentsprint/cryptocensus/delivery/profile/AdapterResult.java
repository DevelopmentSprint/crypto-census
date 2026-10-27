package com.developmentsprint.cryptocensus.delivery.profile;

import java.util.List;

/** Sanitized protocol observations; no response body, endpoint or credential. */
public record AdapterResult(
    Upload upload,
    Ingestion ingestion,
    String reasonCode,
    int httpStatus,
    String remoteOperationId,
    String serverVersion,
    List<String> lossCodes) {
  /** Validates output-safe protocol data. */
  public AdapterResult {
    if (reasonCode == null || !reasonCode.matches("[A-Z0-9_]{1,96}")) {
      throw new IllegalArgumentException("Adapter reason code is invalid");
    }
    if (remoteOperationId != null && !remoteOperationId.matches("[A-Za-z0-9._-]{1,128}")) {
      throw new IllegalArgumentException("Adapter operation identity is invalid");
    }
    if (serverVersion != null && !serverVersion.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalArgumentException("Adapter server version is invalid");
    }
    lossCodes = List.copyOf(lossCodes);
  }

  /** Upload acknowledgment only. */
  public enum Upload {
    NOT_ATTEMPTED,
    ACCEPTED,
    REJECTED,
    UNKNOWN
  }

  /** Explicit protocol ingestion observations. */
  public enum Ingestion {
    NOT_APPLICABLE,
    PENDING,
    COMPLETED,
    FAILED,
    UNVERIFIED
  }
}
