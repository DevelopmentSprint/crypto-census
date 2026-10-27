package com.developmentsprint.cryptocensus.delivery;

import java.net.ProxySelector;
import java.time.Duration;
import javax.net.ssl.SSLContext;

/** Bounded transport settings; null trust context uses JVM trust, null proxy means direct. */
public record DeliveryOptions(
    Duration connectTimeout,
    Duration requestTimeout,
    Duration overallTimeout,
    SSLContext sslContext,
    ProxySelector proxySelector,
    AuthenticatedProxy authenticatedProxy) {
  /** Rejects nonpositive or unbounded transport deadlines. */
  public DeliveryOptions {
    if (authenticatedProxy != null && proxySelector != null) {
      throw new IllegalArgumentException("Use exactly one explicit proxy configuration");
    }
    validate(connectTimeout);
    validate(requestTimeout);
    validate(overallTimeout);
  }

  /** Backwards-compatible unauthenticated transport configuration. */
  public DeliveryOptions(
      Duration connectTimeout,
      Duration requestTimeout,
      Duration overallTimeout,
      SSLContext sslContext,
      ProxySelector proxySelector) {
    this(connectTimeout, requestTimeout, overallTimeout, sslContext, proxySelector, null);
  }

  /** Default connect/request/overall limits are 10/30/60 seconds. */
  public static DeliveryOptions defaults() {
    return new DeliveryOptions(
        Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(60), null, null);
  }

  private static void validate(Duration duration) {
    if (duration == null
        || duration.compareTo(Duration.ofMillis(1)) < 0
        || duration.compareTo(Duration.ofSeconds(60)) > 0) {
      throw new IllegalArgumentException(
          "Delivery timeouts must be between 1 millisecond and 60 seconds");
    }
  }

  @Override
  public String toString() {
    return "DeliveryOptions[connectTimeout="
        + connectTimeout
        + ", requestTimeout="
        + requestTimeout
        + ", overallTimeout="
        + overallTimeout
        + "]";
  }
}
