package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {

  @Test
  void rejectsNonAsciiHexDigitsInUnicodeEscapes() {
    assertThatThrownBy(() -> Json.parse("\"\\u００４１\""))
        .hasMessageContaining("Invalid unicode escape");
    assertThatThrownBy(() -> Json.parse("\"\\u0Ｆ４１\""))
        .hasMessageContaining("Invalid unicode escape");
    assertThat(Json.parse("\"\\u00aF\"")).isEqualTo("¯");
  }

  @Test
  void measuresExactUtf8SizeIncludingEscapesAndIndentation() {
    Object tree =
        java.util.Map.of(
            "é中😀",
            java.util.List.of(
                java.util.Map.of("a", "\"\\\n\r\t\u0001" + Character.toString((char) 0xd800)),
                true,
                17,
                java.util.List.of()));
    assertThat(Json.measure(tree, 0).bytes() + 1)
        .isEqualTo(Json.write(tree).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    assertThat(Json.measure(java.util.Map.of("a", 1), 0).retained()).isEqualTo(170);
    assertThat(Json.measure(java.util.Map.of("a", 1), 0).values()).isEqualTo(2);
  }

  @Test
  void escapesStringsSafely() {
    Object parsed = Json.parse(Json.write(Map.of("k", "quote\" back\\slash\nnewline\u0001")));
    assertThat(parsed).isEqualTo(Map.of("k", "quote\" back\\slash\nnewline\u0001"));
  }

  @Test
  void deepNestingFailsCleanlyInsteadOfOverflowingTheStack() {
    // regression: 20,000 nested arrays used to throw StackOverflowError, which the plugin did not
    // catch
    String hostile = "[".repeat(20_000) + "]".repeat(20_000);
    assertThatThrownBy(() -> Json.parse(hostile))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Nesting deeper than");
  }

  @Test
  void acceptsNestingUpToTheLimit() {
    String ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
    assertThat(Json.parse(ok)).isInstanceOf(List.class);
  }

  @Test
  void wideShallowInputHitsTheValueBudgetBeforeExhaustingTheHeap() {
    // regression: ~9 MB of empty objects in an ignored field used to exhaust a 128 MB heap
    String wide = "[" + "{},".repeat(Json.MAX_VALUES + 10) + "{}]";
    // whichever budget trips first, the parse must fail cleanly rather than fill the heap
    assertThatThrownBy(() -> Json.parse(wide))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageMatching(".*(values|retain more than).*");
  }

  @Test
  void populatedObjectsHitTheRetainedMemoryBudget() {
    // regression: 650,000 {"a":0} objects (5.2 MB, ~1.3M values) passed the value budget and
    // exhausted a 128 MB heap
    String wide = "[" + "{\"a\":0},".repeat(650_000) + "{}]";
    assertThatThrownBy(() -> Json.parse(wide)).hasMessageContaining("retain more than");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"\\q\"", // invalid escape
        "\"raw\u0001control\"", // unescaped control character
        "+01", // not JSON numbers
        "01",
        "1.",
        "1e999", // non-finite
        "99999999999999999999", // integer overflow
        "{\"a\":1,\"a\":2}", // duplicate key
        "[1,]",
        "{\"a\":1}x",
        "\"\\u12\"",
      })
  void rejectsNonStrictInput(String input) {
    assertThatThrownBy(() -> Json.parse(input)).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-0", "7", "-12", "0.5", "1.25", "1e5", "1E+5", "2.5e-3", "-0.0"})
  void numberValidatorAcceptsRfc8259Numbers(String n) {
    assertThat(Json.isJsonNumber(n)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "-", "--1", "+1", "01", "1.", ".5", "1e", "1e+", "1.e5", "0x10", "1-"})
  void numberValidatorRejectsEverythingElse(String n) {
    assertThat(Json.isJsonNumber(n)).isFalse();
  }

  @Test
  void parsesValidNumbers() {
    assertThat(Json.parse("[0, -1, 12, 1.5, -2.5e3, 1E2]"))
        .isEqualTo(List.of(0L, -1L, 12L, 1.5, -2500.0, 100.0));
  }
}
