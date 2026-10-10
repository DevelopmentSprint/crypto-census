package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Primitive;
import org.junit.jupiter.api.Test;

class CheckAttributionTest {
  @Test
  void overflowNeverEstablishesJdkOnlyEvidence() {
    Inventory inventory = new Inventory();
    CryptoAsset asset = CryptoAsset.of(Primitive.MESSAGE_DIGEST, "MD5", 0, null);
    inventory.record(asset, new Occurrence(Occurrence.JDK_LOCATION, null, -1, "jsse"));
    assertThat(CheckMojo.jdkOnly(inventory.entries().get(0))).isTrue();
    inventory.record(asset, Occurrence.OVERFLOW);
    assertThat(CheckMojo.jdkOnly(inventory.entries().get(0))).isFalse();
  }
}
