package com.developmentsprint.cryptocensus.delivery;

import java.io.IOException;

/** A predefined, nonsecret local validation or persistence failure, with no unsafe nested cause. */
public class DeliveryException extends IOException {
  private static final long serialVersionUID = 1L;
  private final String reasonCode;

  DeliveryException(String reasonCode, String message) {
    super(message);
    this.reasonCode = reasonCode;
  }

  /** Safe failure for a receipt path that aliases protected profile evidence. */
  public static DeliveryException invalidReceiptPath() {
    return new DeliveryException(
        "RECEIPT_PATH_INVALID", "Receipt must be distinct from protected inputs and plans");
  }

  /** Stable diagnostic code safe to display beside this exception's predefined message. */
  public final String reasonCode() {
    return reasonCode;
  }
}
