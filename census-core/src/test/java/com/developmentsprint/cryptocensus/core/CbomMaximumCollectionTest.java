package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CbomMaximumCollectionTest {
  @Test
  void supportedMaximumRetainsEveryAssetAndObservation(@TempDir Path dir) throws Exception {
    Inventory inventory = Inventory.bounded(Inventory.MAX_ASSETS, Inventory.MAX_SITES);
    String location = ("com.example.payments." + "DigestService".repeat(20)).substring(0, 256);
    String symbol = ("calculateDigest" + "ForPaymentRecord".repeat(20)).substring(0, 256);
    for (int assetIndex = 0; assetIndex < Inventory.MAX_ASSETS; assetIndex++) {
      String provider = ("Provider" + assetIndex + "CryptoProvider".repeat(20)).substring(0, 256);
      CryptoAsset asset = CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA256", 0, provider);
      for (int site = 0; site < Inventory.MAX_SITES; site++) {
        inventory.record(asset, new Occurrence(location, symbol, site, "jsse"));
      }
    }
    List<Inventory.Entry> collected = inventory.entries();
    List<Inventory.Entry> bounded = Cbom.boundedEntries(collected, 0, CbomBudgetTest.META);
    assertThat(bounded).hasSize(2000);
    assertThat(bounded)
        .extracting(Inventory.Entry::asset)
        .containsExactlyElementsOf(collected.stream().map(Inventory.Entry::asset).toList());
    assertThat(bounded)
        .extracting(Inventory.Entry::assessment)
        .containsExactlyElementsOf(collected.stream().map(Inventory.Entry::assessment).toList());
    assertThat(Cbom.boundedEntries(bounded, 0, CbomBudgetTest.META)).isEqualTo(bounded);

    Path output = dir.resolve("maximum.json");
    Cbom.write(inventory, CbomBudgetTest.META, output);
    assertThat(Files.size(output)).isLessThanOrEqualTo(Cbom.MAX_OUTPUT_BYTES);
    Cbom.Document parsed = Cbom.read(output);
    assertThat(parsed.hasAgentHealth()).isTrue();
    assertThat(parsed.inventory().entries()).isEqualTo(bounded);
    assertThat(parsed.inventory().entries())
        .allSatisfy(entry -> assertThat(entry.total()).isEqualTo(100));
    assertThat(parsed.inventory().droppedObservations()).isZero();
    assertThat(parsed.inventory().overflowedSites()).isPositive();
    assertThat(inventory.overflowedSites()).isZero();
  }
}
