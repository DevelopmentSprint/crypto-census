package com.developmentsprint.cryptocensus.delivery;

import java.net.http.HttpRequest;
import java.time.Duration;

/** Sends an unchanged CBOM to an operator-controlled HTTP receiver. No vendor fidelity claim. */
final class GenericHttpsAdapter implements DeliveryAdapter {
  @Override
  public HttpRequest prepare(
      DeliveryRequest request,
      byte[] artifact,
      String digest,
      String deliveryId,
      Duration timeout) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(request.endpoint())
            .timeout(timeout)
            .header("Content-Type", "application/vnd.cyclonedx+json")
            .header("Accept", "application/json")
            .header("X-Census-Application", request.application())
            .header("X-Census-Module", request.module())
            .header("X-Census-Version", request.version())
            .header("X-Census-Run-Id", request.runId())
            .header("X-Census-Artifact-Sha256", digest)
            .header("X-Census-Delivery-Id", deliveryId)
            .POST(HttpRequest.BodyPublishers.ofByteArray(artifact));
    String authorization = request.credentials().authorization();
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return builder.build();
  }

  @Override
  public DeliveryReceipt interpret(int status, String digest, String deliveryId) {
    if (status >= 200 && status < 300) {
      return new DeliveryReceipt(
          DeliveryReceipt.Outcome.HTTP_ACCEPTED, digest, deliveryId, status, "HTTP_ACCEPTED");
    }
    return new DeliveryReceipt(
        status >= 500 ? DeliveryReceipt.Outcome.UNKNOWN : DeliveryReceipt.Outcome.REJECTED,
        digest,
        deliveryId,
        status,
        status >= 500 ? "REMOTE_SERVER_ERROR" : "HTTP_REJECTED");
  }
}
