package com.developmentsprint.cryptocensus.delivery.profile;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bounded retries require an explicit receiver deduplication contract; never inferred from HTTP.
 */
final class RetryPolicy {
  private RetryPolicy() {}

  static boolean eligible(ProtocolResponse response) {
    return response.failureCode() == null
        ? Set.of(408, 429, 500, 502, 503, 504).contains(response.statusCode())
        : Set.of("TIMEOUT", "TRANSPORT_FAILURE").contains(response.failureCode());
  }

  /** Returns -1 for invalid or excessive advice, so it can never accelerate a server's delay. */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "PREDICTABLE_RANDOM",
      justification =
          "Scheduling jitter is not a security token and carries no confidentiality guarantee")
  static long delayMillis(String retryAfter, int attempts, Instant now) {
    long backoff = ThreadLocalRandom.current().nextLong(250L * attempts, 500L * attempts + 1);
    if (retryAfter == null) {
      return backoff;
    }
    try {
      long delay;
      if (retryAfter.matches("[0-9]{1,9}")) {
        delay = Math.multiplyExact(Long.parseLong(retryAfter), 1000L);
      } else {
        delay =
            Math.max(
                0,
                Duration.between(
                        now,
                        ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant())
                    .toMillis());
      }
      return delay > 60_000 ? -1 : Math.max(delay, backoff);
    } catch (DateTimeParseException | ArithmeticException error) {
      return -1;
    }
  }
}
