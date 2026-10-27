package com.developmentsprint.cryptocensus.delivery.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SecretReferenceTest {
  static Stream<String> invalidBasicValues() {
    return Stream.of(
        null,
        "",
        "not-base64!",
        "Basic dTpw",
        "dTpw\r\n",
        "dTpw====",
        "dXNlcjpwYXNzd29yZA", // Missing required padding.
        "dXNlcjpwYXNzd29yZB==", // Nonzero discarded pad bits.
        encoded("username-password"),
        encoded(":password"),
        encoded("user:"),
        encoded("user name:password"),
        encoded("user\n:password"),
        encoded("user:pass\u007fword"),
        encoded("user:pässword"),
        encoded("u".repeat(1025) + ":password"),
        encoded("user:" + "p".repeat(4097)),
        "A".repeat(8193));
  }

  static Stream<String> validBasicValues() {
    return Stream.of(
        encoded("u:p"),
        encoded("user:password"),
        encoded("user: pass:word "),
        encoded("u".repeat(1024) + ":" + "p".repeat(4096)));
  }

  private static String encoded(String pair) {
    return Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  @ParameterizedTest
  @MethodSource("invalidBasicValues")
  void rejectsUnsupportedPairsWithoutCredentialOrDecoderDetails(String value) {
    assertThatThrownBy(() -> SecretReference.validateBasic(value))
        .isInstanceOf(SecretReference.InvalidBasicCredentialsException.class)
        .hasMessage(
            "BASIC_CREDENTIALS_INVALID: Basic credentials are missing or invalid; check"
                + " the selected username and password sources.")
        .hasNoCause();
  }

  @ParameterizedTest
  @MethodSource("validBasicValues")
  void canonicalPairsIncludingBoundaryLengthsAreUnchanged(String value) {
    SecretReference.validateBasic(value);
    String pair = new String(Base64.getDecoder().decode(value), StandardCharsets.US_ASCII);
    int separator = pair.indexOf(':');
    assertThat(
            SecretReference.encodeBasic(
                pair.substring(0, separator), pair.substring(separator + 1)))
        .isEqualTo(value);
  }
}
