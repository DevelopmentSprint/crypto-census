package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonDocumentTest {
  @Test
  void canonicalBytesIgnoreObjectInsertionOrderIncludingNestedObjects() {
    java.util.Map<String, Object> first = new java.util.LinkedHashMap<>();
    first.put("z", Map.of("y", 2, "a", 1));
    first.put("a", List.of(Map.of("b", 2, "a", 1)));
    java.util.Map<String, Object> second = new java.util.LinkedHashMap<>();
    second.put("a", List.of(Map.of("a", 1, "b", 2)));
    second.put("z", Map.of("a", 1, "y", 2));
    assertThat(JsonDocument.write(first)).isEqualTo(JsonDocument.write(second));
    assertThat(JsonDocument.write(first)).startsWith("{\n  \"a\"");
  }

  @Test
  void rejectsInvalidTreesAndSuppressesRawInput() {
    assertThat(
            JsonDocument.parse(
                JsonDocument.write(Map.of("values", List.of(1, 1.5, true, "unicode-é")))))
        .isInstanceOf(Map.class);
    assertThatThrownBy(() -> JsonDocument.parse("{secret-private-value"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("JSON document is invalid or exceeds its limits");
    assertThatThrownBy(() -> JsonDocument.parse(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JsonDocument.parse(" ".repeat(16 * 1024 * 1024 + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JsonDocument.write(Map.of(1, "bad")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JsonDocument.write(new Object()))
        .isInstanceOf(IllegalArgumentException.class);
    List<Object> cyclic = new ArrayList<>();
    cyclic.add(cyclic);
    assertThatThrownBy(() -> JsonDocument.write(cyclic))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> JsonDocument.write("x".repeat(16 * 1024 * 1024)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
