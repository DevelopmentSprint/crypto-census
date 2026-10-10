package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.delivery.preparation.PreparedBundle;
import java.net.URI;

/** Immutable prepared evidence and validated configuration; no credentials. */
public record AdapterContext(
    PreparedBundle bundle,
    URI endpoint,
    AdapterOptions options,
    DeliveryPolicy policy,
    VerifiedRetention retention) {
  @Override
  public String toString() {
    return "AdapterContext[endpoint=REDACTED]";
  }
}
