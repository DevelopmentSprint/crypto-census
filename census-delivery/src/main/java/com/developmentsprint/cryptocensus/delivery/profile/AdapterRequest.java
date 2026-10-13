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
    java.util.Map<String, String> headers,
    boolean publicationReadback) {
  /** Copies request bytes so protocol state cannot mutate an in-flight submission. */
  public AdapterRequest {
    body = body.clone();
    headers = java.util.Map.copyOf(headers);
    if (publicationReadback && (!"GET".equals(method) || mutation || body.length != 0)) {
      throw new IllegalArgumentException("Publication readback requires a bodyless GET");
    }
  }

  /** Constructs a bounded protocol request without publication readback. */
  public AdapterRequest(
      String method,
      String relativePath,
      String contentType,
      byte[] body,
      boolean mutation,
      long delayMillis,
      java.util.Map<String, String> headers) {
    this(method, relativePath, contentType, body, mutation, delayMillis, headers, false);
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

  /** Selects bounded exact-byte publication verification for this GET. */
  public AdapterRequest withPublicationReadback() {
    return new AdapterRequest(
        method, relativePath, contentType, body, mutation, delayMillis, headers, true);
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
