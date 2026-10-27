package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {
  @Test
  void respectsSecondsAndHttpDatesWithoutShorteningAdvice() {
    Instant now = Instant.parse("2026-10-12T12:00:00Z");
    assertThat(RetryPolicy.delayMillis("2", 1, now)).isEqualTo(2000);
    assertThat(RetryPolicy.delayMillis("Mon, 12 Oct 2026 12:00:03 GMT", 1, now)).isEqualTo(3000);
    assertThat(RetryPolicy.delayMillis("Mon, 12 Oct 2026 11:59:00 GMT", 1, now))
        .isBetween(250L, 500L);
    assertThat(RetryPolicy.delayMillis(null, 2, now)).isBetween(500L, 1000L);
    for (String value :
        new String[] {"999999999", "9999999999999999999999999", "invalid", "-1", "1.5"}) {
      assertThat(RetryPolicy.delayMillis(value, 1, now)).isEqualTo(-1);
    }
  }

  @Test
  void onlyTransientResponsesAndAmbiguousNetworkFailuresAreEligible() {
    for (int status : new int[] {408, 429, 500, 502, 503, 504}) {
      assertThat(RetryPolicy.eligible(new ProtocolResponse(status, new byte[0], null))).isTrue();
    }
    for (int status : new int[] {200, 202, 302, 401, 403, 409, 501}) {
      assertThat(RetryPolicy.eligible(new ProtocolResponse(status, new byte[0], null))).isFalse();
    }
    for (String code : new String[] {"TIMEOUT", "TRANSPORT_FAILURE"}) {
      assertThat(RetryPolicy.eligible(new ProtocolResponse(0, new byte[0], code))).isTrue();
    }
    for (String code :
        new String[] {"INTERRUPTED", "DEADLINE_BEFORE_SEND", "TLS_FAILURE", "RESPONSE_TOO_LARGE"}) {
      assertThat(RetryPolicy.eligible(new ProtocolResponse(0, new byte[0], code))).isFalse();
    }
  }
}
