package com.developmentsprint.cryptocensus.delivery;

/** Delivery completed but its receipt could not be persisted. Retrying may duplicate delivery. */
public final class ReceiptWriteException extends DeliveryException {
  private static final long serialVersionUID = 1L;
  private final boolean remoteAccepted;

  ReceiptWriteException(boolean remoteAccepted) {
    super(
        "RECEIPT_WRITE_FAILED",
        "Delivery receipt could not be persisted; remote acceptance=" + remoteAccepted);
    this.remoteAccepted = remoteAccepted;
  }

  /** Constructs the predefined receipt failure without accepting arbitrary diagnostic text. */
  public static ReceiptWriteException afterDelivery(boolean remoteAccepted) {
    return new ReceiptWriteException(remoteAccepted);
  }

  /**
   * Whether the endpoint returned a complete successful HTTP response before persistence failed.
   */
  public boolean remoteAccepted() {
    return remoteAccepted;
  }
}
