package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextBoundaryTest {
  @Test
  void canonicalJsonIsDeterministicAndStrict() {
    Map<String, Object> input = new java.util.LinkedHashMap<>();
    input.put("z", List.of((byte) 1, (short) 2, 3, 4L, "emoji 😀", true));
    input.put("a", null);
    byte[] bytes = CanonicalJson.encode(input);
    Object decoded = CanonicalJson.decode(bytes);
    assertThat(CanonicalJson.encode(decoded)).isEqualTo(bytes);
    assertThat(CanonicalJson.revision(input)).isEqualTo(CanonicalJson.hash(bytes));
    assertThat(CanonicalJson.bool(Map.of("x", true), "x")).isTrue();
    for (String invalid : List.of("{\"a\":1,\"a\":2}", "{\"x\":1.5}", "[] trailing")) {
      assertThatThrownBy(() -> CanonicalJson.decode(invalid.getBytes(StandardCharsets.UTF_8)))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> CanonicalJson.decode(new byte[] {(byte) 0xc0, (byte) 0xaf}))
        .hasMessageContaining("UTF-8");
    assertThatThrownBy(() -> CanonicalJson.encode(1.2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalJson.encode(Map.of(1, "x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalJson.encode(String.valueOf((char) 0xd800)))
        .hasMessageContaining("surrogate");
    assertThatThrownBy(() -> CanonicalJson.encode(String.valueOf((char) 0xdc00)))
        .hasMessageContaining("surrogate");
    assertThatThrownBy(() -> CanonicalJson.encode("x".repeat(65537)))
        .hasMessageContaining("string too long");
    assertThatThrownBy(() -> CanonicalJson.object(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalJson.object(Map.of(1, true)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalJson.text(Map.of("x", 1), "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalJson.integer(Map.of("x", "1"), "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalJson.bool(Map.of("x", "true"), "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CanonicalJson.array(Map.of("x", "[]"), "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void traversalBudgetsRejectDeepAndExpandedGraphsBeforeUnboundedAllocation() {
    Object value = "leaf";
    for (int i = 0; i < 34; i++) {
      value = List.of(value);
    }
    Object deep = value;
    assertThatThrownBy(() -> CanonicalJson.encode(deep)).hasMessageContaining("nesting");
    Object graph = "x";
    for (int i = 0; i < 20; i++) {
      graph = List.of(graph, graph);
    }
    Object expanded = graph;
    assertThatThrownBy(() -> CanonicalJson.encode(expanded)).hasMessageContaining("budget");
    assertThatThrownBy(() -> CanonicalJson.encode(java.util.Collections.nCopies(32769, "x")))
        .hasMessageContaining("array too large");
    assertThatThrownBy(() -> CanonicalJson.decode(new byte[CanonicalJson.MAX_BYTES + 1]))
        .hasMessageContaining("input budget");
  }

  @Test
  void sourceResolutionIsExplicitAndIndependentOfFilesystemState(@TempDir Path directory)
      throws Exception {
    String root = directory.toUri().toASCIIString();
    String nested = root + "classes/";
    SourceCatalog.Entry broad =
        new SourceCatalog.Entry(root, "group:parent", "app", ContextModel.Origin.MAIN, "build");
    SourceCatalog.Entry narrow =
        new SourceCatalog.Entry(nested, "group:child", "app", ContextModel.Origin.TEST, "build");
    SourceCatalog catalog = new SourceCatalog(1, List.of(broad, narrow));
    assertThat(catalog.resolve(nested + "Foo.class")).contains(narrow);
    assertThat(catalog.resolve(root + "other.jar")).contains(broad);
    assertThat(catalog.resolve("https://example.invalid/a.jar")).isEmpty();
    assertThat(catalog.resolve("file:relative")).isEmpty();
    assertThat(catalog.resolve(null)).isEmpty();
    String exact = nested.substring(0, nested.length() - 1);
    SourceCatalog.Entry before =
        new SourceCatalog.Entry(exact, "g:a", "app", ContextModel.Origin.MAIN, "build");
    Files.createDirectory(directory.resolve("classes"));
    assertThat(new SourceCatalog.Entry(exact, "g:a", "app", ContextModel.Origin.MAIN, "build"))
        .isEqualTo(before);
    assertThat(new SourceCatalog(1, List.of(before)).resolve(nested + "Foo.class")).isEmpty();
    assertThat(SourceCatalog.parse(catalog.encode()).encode()).isEqualTo(catalog.encode());
    assertThat(new SourceCatalog(1, List.of(narrow, broad)).encode()).isEqualTo(catalog.encode());
    assertThatThrownBy(() -> new SourceCatalog(1, List.of(broad, broad)))
        .hasMessageContaining("Duplicate");
    assertThatThrownBy(() -> new SourceCatalog(2, List.of())).hasMessageContaining("Unsupported");
    assertThatThrownBy(
            () ->
                SourceCatalog.parse(
                    CanonicalJson.encode(Map.of("schemaVersion", 2, "entries", List.of()))))
        .hasMessageContaining("Unsupported");
    assertThatThrownBy(
            () ->
                new SourceCatalog.Entry(
                    root + "?x", "g:a", "app", ContextModel.Origin.MAIN, "build"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void declarationsRetainUnknownAnswersAndRejectMalformedRequirements() {
    assertThat(ContextModel.Field.PURPOSE.substantive("UNSPECIFIED")).isFalse();
    assertThat(ContextModel.Field.EXPOSURE.substantive("unknown")).isFalse();
    assertThat(ContextModel.Field.IMPACT.substantive("unknown")).isFalse();
    assertThat(ContextModel.Field.IMPACT.substantive("high")).isTrue();
    assertThat(ContextModel.Field.CLASSIFICATION.validate("custom-classification"))
        .isEqualTo("custom-classification");
    assertThat(ContextModel.Field.OWNER.validate("Security team")).isEqualTo("Security team");
    for (String value : List.of("0", "1001", "01", "+1", "many")) {
      assertThatThrownBy(() -> ContextModel.Field.CONFIDENTIALITY_YEARS.validate(value))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(ContextModel.Field.AUTHENTICITY_YEARS.validate("1000")).isEqualTo("1000");
    assertThatThrownBy(() -> ContextModel.Field.CLASSIFICATION.validate("bad space"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ContextModel.Field.IMPACT.validate("huge"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ContextModel.Subject("app", "Bad Flow"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ContextModel.text("a\0b", "name", 10, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ContextModel.hash("abc", "hash"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ContextModel.bounded(List.of(1, 2), 1, "items"))
        .isInstanceOf(IllegalArgumentException.class);
    ContextModel.Subject subject = new ContextModel.Subject("a:b", "archive");
    assertThat(subject.ref()).isNotEqualTo(new ContextModel.Subject("a", "b.archive").ref());
    ContextModel.Source source =
        new ContextModel.Source(
            ContextModel.SourceKind.USER,
            "review",
            "a".repeat(64),
            "reviewer",
            "2026-10-26T00:00:00Z");
    assertThat(ContextJson.source(ContextJson.source(source))).isEqualTo(source);
    ContextModel.Source defaults =
        new ContextModel.Source(
            ContextModel.SourceKind.APPLICATION_DEFAULT, "default", "a".repeat(64), "", "");
    assertThatThrownBy(
            () -> new ContextModel.Declaration(subject, ContextModel.Field.OWNER, "team", defaults))
        .hasMessageContaining("Application default");
    List<ContextModel.Declaration> declarations = new ArrayList<>();
    declarations.add(
        new ContextModel.Declaration(subject, ContextModel.Field.OWNER, "team", source));
    ContextModel.FlowDefinition flow =
        new ContextModel.FlowDefinition(subject, "Archive", declarations, List.of());
    declarations.clear();
    assertThat(flow.declarations()).hasSize(1);
    assertThat(ContextJson.flow(ContextJson.flow(flow))).isEqualTo(flow);
    assertThatThrownBy(
            () ->
                new ContextModel.FlowDefinition(
                    new ContextModel.Subject("app", ""), "Default", List.of(), List.of()))
        .hasMessageContaining("flow identifier");
    assertThatThrownBy(
            () ->
                new ContextModel.FlowDefinition(
                    new ContextModel.Subject("other", "archive"),
                    "Archive",
                    flow.declarations(),
                    List.of()))
        .hasMessageContaining("another flow");
  }

  @Test
  void fileBoundaryRejectsLinksDirectoriesAndOversizedWrites(@TempDir Path directory)
      throws Exception {
    Path target = directory.resolve("document.json");
    DocumentFiles.write(target, "{}".getBytes(StandardCharsets.UTF_8));
    Path link = directory.resolve("link.json");
    Files.createSymbolicLink(link, target);
    assertThatThrownBy(() -> DocumentFiles.read(link)).isInstanceOf(java.io.IOException.class);
    assertThatThrownBy(() -> DocumentFiles.read(directory)).isInstanceOf(java.io.IOException.class);
    assertThatThrownBy(() -> DocumentFiles.write(target, new byte[CanonicalJson.MAX_BYTES + 1]))
        .isInstanceOf(java.io.IOException.class);
    assertThat(Files.readString(target)).isEqualTo("{}");
  }
}
