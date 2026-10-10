package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CbomBudgetTest {
  static final Cbom.Metadata META =
      new Cbom.Metadata(
          "boundary",
          Map.of(
              Cbom.SCHEMA_PROPERTY,
              Cbom.AGENT_SCHEMA,
              "cryptocensus:transformErrors",
              "0",
              "cryptocensus:recordingFailures",
              "0",
              "cryptocensus:untrackedTypes",
              "0",
              "cryptocensus:drainTimedOut",
              "0",
              "cryptocensus:overflowedSites",
              "0"));

  @ParameterizedTest
  @ValueSource(strings = {"x", "中", "\u0001", "😀"})
  void defaultCapsRoundTripWithLongestNamesAndPreserveCounts(String unit, @TempDir Path dir)
      throws Exception {
    Inventory original = Inventory.bounded(500, 50);
    String name = unit.repeat(256 / unit.length());
    for (int assetIndex = 0; assetIndex < 500; assetIndex++) {
      CryptoAsset asset =
          CryptoAsset.of(Primitive.MESSAGE_DIGEST, "ALG-" + assetIndex, 0, "P" + name);
      for (int site = 0; site < 50; site++) {
        original.add(asset, new Occurrence(name, name, site, "jsse"), site + 1);
      }
      original.add(asset, Occurrence.OVERFLOW, 7);
    }
    original.addDropped(9);
    List<Inventory.Entry> bounded = Cbom.boundedEntries(original.entries(), 9, META);
    assertThat(bounded).hasSize(500);
    assertThat(Cbom.boundedEntries(bounded, 9, META)).isEqualTo(bounded);
    Path output = dir.resolve("cbom.json");
    Cbom.write(bounded, 9, META, output);
    assertThat(Files.size(output)).isLessThanOrEqualTo(Cbom.MAX_OUTPUT_BYTES);
    Cbom.Document parsed = Cbom.read(output);
    assertThat(parsed.hasAgentHealth()).isTrue();
    assertThat(parsed.inventory().droppedObservations()).isEqualTo(9);
    assertThat(parsed.inventory().entries()).isEqualTo(bounded);
    assertThat(parsed.inventory().entries()).allSatisfy(e -> assertThat(e.total()).isEqualTo(1282));
    assertThat(parsed.inventory().overflowedSites()).isGreaterThan(3500);
    assertThat(Report.text(bounded, 9)).isEqualTo(Report.text(parsed.inventory()));
    assertThat(original.overflowedSites()).isEqualTo(3500); // writer never mutates live collection

    Inventory merged = new Inventory();
    merged.mergeFrom(parsed.inventory());
    merged.mergeFrom(parsed.inventory());
    Cbom.Document again = Cbom.parse(Cbom.toJson(merged, META).getBytes(StandardCharsets.UTF_8));
    assertThat(again.hasAgentHealth()).isTrue();
    assertThat(again.inventory().entries()).allSatisfy(e -> assertThat(e.total()).isEqualTo(2564));
    assertThat(again.inventory().overflowedSites())
        .isGreaterThanOrEqualTo(2 * parsed.inventory().overflowedSites());
    assertThat(again.inventory().droppedObservations()).isEqualTo(18);
  }

  @Test
  void unrepresentableAssetsFailWithoutReplacingExistingOutput(@TempDir Path dir) throws Exception {
    CryptoAsset asset = CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA256", 0, null);
    Inventory.Entry entry =
        new Inventory.Entry(
            asset,
            Assessment.of(Risk.ACCEPTABLE, "x".repeat((int) Cbom.MAX_OUTPUT_BYTES)),
            Map.of(new Occurrence("application", "run", 1, null), 5L));
    Path output = dir.resolve("existing.json");
    Files.writeString(output, "previous evidence");
    assertThatThrownBy(() -> Cbom.write(List.of(entry), 0, META, output))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("even without call-site");
    assertThat(Files.readString(output)).isEqualTo("previous evidence");
    try (var files = Files.list(dir)) {
      assertThat(files.toList()).containsExactly(output);
    }
  }

  @Test
  void metadataIsIncludedInTheOutputBudget() {
    Cbom.Metadata huge = new Cbom.Metadata("x".repeat((int) Cbom.MAX_OUTPUT_BYTES), Map.of());
    assertThatThrownBy(() -> Cbom.toJson(new Inventory(), huge))
        .hasMessageContaining("metadata exceed serialization budgets");
  }

  @Test
  void malformedUtf8IsRejectedBeforeParsing() {
    byte[] valid = Cbom.toJson(new Inventory(), META).getBytes(StandardCharsets.UTF_8);
    int offset = new String(valid, StandardCharsets.UTF_8).indexOf("boundary");
    valid[offset] = (byte) 0xff;
    assertThatThrownBy(() -> Cbom.parse(valid)).hasMessageContaining("not valid UTF-8");
    for (byte[] malformed :
        new byte[][] {
          {(byte) 0xff},
          {(byte) 0xc0, (byte) 0xaf},
          {(byte) 0xe2, (byte) 0x82},
          {(byte) 0xed, (byte) 0xa0, (byte) 0x80}
        }) {
      assertThatThrownBy(() -> Cbom.parse(malformed)).hasMessageContaining("not valid UTF-8");
    }
  }
}
