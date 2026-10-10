package com.developmentsprint.cryptocensus.delivery.profile;

/** An adapter request executed only through centrally bounded same-origin transport. */
@SuppressWarnings(
    "ArrayRecordComponent") // Byte payloads are copied on input/output; records are never compared
// for identity.
public record AdapterRequest(
    String method,
    String relativePath,
    String contentType,
    byte[] body,
    boolean mutation,
    long delayMillis,
    java.util.Map<String, String> headers) {
  /** Copies request bytes so protocol state cannot mutate an in-flight submission. */
  public AdapterRequest {
    body = body.clone();
    headers = java.util.Map.copyOf(headers);
  }

  /** Constructs a vendor request without extension headers. */
  public AdapterRequest(
      String method,
      String relativePath,
      String contentType,
      byte[] body,
      boolean mutation,
      long delayMillis) {
    this(method, relativePath, contentType, body, mutation, delayMillis, java.util.Map.of());
  }

  @Override
  public byte[] body() {
    return body.clone();
  }

  @Override
  public String toString() {
    return "AdapterRequest[method=" + method + ", path=REDACTED]";
  }
}
