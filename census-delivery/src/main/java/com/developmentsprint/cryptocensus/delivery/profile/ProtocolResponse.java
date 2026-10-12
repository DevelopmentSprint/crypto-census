package com.developmentsprint.cryptocensus.delivery.profile;

/** Bounded response for protocol parsing; failure text is a predefined safe code. */
@SuppressWarnings(
    "ArrayRecordComponent") // Response payloads are copied and not part of equality decisions.
public record ProtocolResponse(int statusCode, byte[] body, String failureCode) {
  /** Copies bounded bytes. */
  public ProtocolResponse {
    body = body.clone();
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public String toString() {
    return "ProtocolResponse[statusCode=" + statusCode + "]";
  }
}
