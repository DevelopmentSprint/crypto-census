package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.delivery.preparation.PreparedBundle;
import java.util.Map;

/**
 * In-memory immutable profile/bundle identity; persisted plans are revalidated before execution.
 */
public record PlannedDelivery(
    DeliveryProfile profile,
    PreparedBundle bundle,
    String planSha256,
    Map<String, Object> sanitizedPlan) {
  /** Copies top-level identity; callers receive no mutable byte buffers. */
  public PlannedDelivery {
    sanitizedPlan = Map.copyOf(sanitizedPlan);
  }

  @Override
  public String toString() {
    return "PlannedDelivery[planSha256=" + planSha256 + "]";
  }
}
