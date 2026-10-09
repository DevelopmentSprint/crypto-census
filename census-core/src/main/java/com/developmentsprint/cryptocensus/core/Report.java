package com.developmentsprint.cryptocensus.core;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Plain-text summary for consoles and build logs. */
public final class Report {

  private Report() {}

  /**
   * Findings table for a snapshot of {@code inventory}.
   *
   * @param inventory the inventory to snapshot
   * @return a multi-line, human-readable report
   */
  public static String text(Inventory inventory) {
    return text(inventory.entries(), inventory.droppedObservations());
  }

  /**
   * Findings table for an inventory snapshot.
   *
   * @param entries the snapshot
   * @param droppedObservations observations dropped by the inventory's caps
   * @return a multi-line, human-readable report
   */
  public static String text(List<Inventory.Entry> entries, long droppedObservations) {
    StringBuilder sb = new StringBuilder();
    if (entries.isEmpty() && droppedObservations == 0) {
      return "Crypto Census: no cryptographic operations observed.\n";
    }

    Map<Risk, Integer> counts = new EnumMap<>(Risk.class);
    entries.forEach(e -> counts.merge(e.assessment().risk(), 1, Integer::sum));

    sb.append("Crypto Census: ").append(entries.size()).append(" cryptographic assets observed\n");
    for (Risk risk :
        new Risk[] {
          Risk.BROKEN,
          Risk.WEAK,
          Risk.QUANTUM_VULNERABLE,
          Risk.UNKNOWN,
          Risk.ACCEPTABLE,
          Risk.QUANTUM_SAFE
        }) {
      if (counts.containsKey(risk)) {
        sb.append(String.format("  %-20s %d%n", risk.label(), counts.get(risk)));
      }
    }
    if (droppedObservations > 0) {
      sb.append(
          String.format(
              "  INCOMPLETE: %d observations dropped (asset limit reached)%n",
              droppedObservations));
    }
    sb.append('\n');
    for (Inventory.Entry e : entries) {
      CryptoAsset a = e.asset();
      String name =
          a.displayName() + (a.operation() != null ? " (" + a.operation().label() + ")" : "");
      sb.append(
          String.format(
              "  [%-18s] %-20s %-38s %s%s%n",
              e.assessment().risk().label(),
              a.primitive().name().toLowerCase(Locale.ROOT).replace('_', '-'),
              name,
              e.assessment().reason(),
              e.assessment().cnsa2() && !e.assessment().reason().contains("CNSA 2.0)")
                  ? " [CNSA 2.0]"
                  : ""));
      e.occurrences()
          .forEach((o, n) -> sb.append(String.format("  %22s %-55s x%d%n", "", "at " + o, n)));
    }
    return sb.toString();
  }
}
