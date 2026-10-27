package com.developmentsprint.cryptocensus.delivery;

import java.net.http.HttpRequest;
import java.time.Duration;

/** Internal adapter seam. Destination support is compiled in, never loaded from evidence. */
interface DeliveryAdapter {
  HttpRequest prepare(
      DeliveryRequest request, byte[] artifact, String digest, String deliveryId, Duration timeout);

  DeliveryReceipt interpret(int status, String digest, String deliveryId);
}
