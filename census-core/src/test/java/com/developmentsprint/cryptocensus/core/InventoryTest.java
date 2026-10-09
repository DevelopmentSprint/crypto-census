package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InventoryTest {

  @Test
  void capsDistinctAssetsAndCountsWhatWasDropped() {
    Inventory inventory = Inventory.bounded(10, 5);
    for (int i = 0; i < 1_000; i++) {
      inventory.record(
          CryptoAsset.of(Primitive.MESSAGE_DIGEST, "ALG" + i, 0, null),
          new Occurrence("a.A", "m", 1, null));
    }
    assertThat(inventory.entries()).hasSize(10);
    assertThat(inventory.droppedObservations()).isEqualTo(990);
  }

  @Test
  void foldsExcessCallSitesIntoOverflowWithoutLosingTheAsset() {
    Inventory inventory = Inventory.bounded(10, 3);
    CryptoAsset md5 = CryptoAsset.of(Primitive.MESSAGE_DIGEST, "MD5", 0, null);
    for (int i = 0; i < 100; i++) {
      inventory.record(md5, new Occurrence("gen.Class" + i, "m", 1, null));
    }
    Inventory.Entry entry = inventory.entries().get(0);
    assertThat(entry.occurrences()).hasSize(4).containsEntry(Occurrence.OVERFLOW, 97L);
    assertThat(entry.total()).isEqualTo(100);
    assertThat(inventory.droppedObservations()).isZero();
    // attribution was lost for 97 events, and that is counted
    assertThat(inventory.overflowedSites()).isEqualTo(97);
  }

  @Test
  void countsSaturateInsteadOfWrapping() {
    // regression: Long.MAX_VALUE + 1 used to wrap to Long.MIN_VALUE
    Inventory inventory = new Inventory();
    CryptoAsset md5 = CryptoAsset.of(Primitive.MESSAGE_DIGEST, "MD5", 0, null);
    Occurrence site = new Occurrence("a.A", "m", 1, null);
    inventory.add(md5, site, Long.MAX_VALUE);
    inventory.add(md5, site, 1);
    inventory.addDropped(Long.MAX_VALUE);
    inventory.addDropped(Long.MAX_VALUE);
    assertThat(inventory.entries().get(0).total()).isEqualTo(Long.MAX_VALUE);
    assertThat(inventory.droppedObservations()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  void truncatesCallerControlledNames() {
    String huge = "A".repeat(100_000);
    CryptoAsset asset = CryptoAsset.of(Primitive.MESSAGE_DIGEST, huge, 0, huge);
    assertThat(asset.algorithm()).hasSize(Names.MAX_LENGTH);
    assertThat(asset.provider()).hasSize(Names.MAX_LENGTH);
  }

  @Test
  void rejectsNegativeCounts() {
    Inventory inventory = new Inventory();
    assertThatThrownBy(
            () ->
                inventory.add(
                    CryptoAsset.of(Primitive.MESSAGE_DIGEST, "MD5", 0, null),
                    Occurrence.UNKNOWN,
                    -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void truncationNeverSplitsASurrogatePair() {
    // regression: a split emoji became '?' in UTF-8 and collided with a real '?' in bom-refs
    CryptoAsset emoji =
        CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, "A".repeat(255) + "\uD83D\uDE00");
    CryptoAsset question =
        CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, "A".repeat(255) + "?");
    assertThat(Character.isHighSurrogate(emoji.provider().charAt(emoji.provider().length() - 1)))
        .isFalse();
    assertThat(emoji.ref()).isNotEqualTo(question.ref());
  }

  @Test
  void strictPercentDecoding() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> Escapes.decode("a%00b"))
        .hasMessageContaining("NUL");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> Escapes.decode("a%GGb"))
        .hasMessageContaining("Malformed");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> Escapes.decode("a%FFb"))
        .hasMessageContaining("UTF-8");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> Escapes.decode("a%\uFF11\uFF12"))
        .hasMessageContaining("Malformed");
    assertThat(Escapes.decode("%252C")).isEqualTo("%2C"); // single pass: no double decoding
  }

  @Test
  void escapesRoundTrip() {
    String raw = "C:\\Program Files\\a,b=c;d%e\"f'g ü";
    assertThat(Escapes.decode(Escapes.encode(raw))).isEqualTo(raw);
    assertThat(Escapes.encode(raw)).doesNotContain(",", ";", "=", " ", "\"", "'");
  }
}
