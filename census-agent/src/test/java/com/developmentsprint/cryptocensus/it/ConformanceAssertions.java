package com.developmentsprint.cryptocensus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Primitive;
import com.developmentsprint.cryptocensus.core.Targets;
import java.util.List;

/** What every conformance run (APM agent, app server) must show, whatever it observed. */
final class ConformanceAssertions {

  private ConformanceAssertions() {}

  /** The agent started, wrote a valid CBOM, and every health counter is zero; no target gaps. */
  static void assertHealthy(ChildJvm.Result run, Cbom.Document document) {
    assertThat(run.exitCode()).as(run.stderr()).isZero();
    assertThat(run.stderr()).doesNotContain("[crypto-census] agent failed to start");
    assertThat(run.stderr()).doesNotContain("[crypto-census] failed to write");
    assertThat(run.stderr()).doesNotContain("[crypto-census] instrumentation gap");
    assertThat(document.hasAgentHealth()).as(run.stderr()).isTrue();
    assertThat(document.properties())
        .as(run.stderr())
        .containsEntry("cryptocensus:transformErrors", "0")
        .containsEntry("cryptocensus:recordingFailures", "0")
        .containsEntry("cryptocensus:dropped", "0")
        .containsEntry("cryptocensus:drainTimedOut", "0")
        .containsEntry("cryptocensus:untrackedTypes", "0");
    assertThat(Targets.parse(document.properties().get(Targets.PROPERTY)).values())
        .as(document.properties().get(Targets.PROPERTY))
        .isNotEmpty()
        .noneMatch(Targets.Status::isGap);
  }

  /** Entries of one primitive and algorithm. */
  static List<Inventory.Entry> entries(
      Cbom.Document document, Primitive primitive, String algorithm) {
    return document.inventory().entries().stream()
        .filter(e -> e.asset().primitive() == primitive)
        .filter(e -> e.asset().algorithm().equalsIgnoreCase(algorithm))
        .toList();
  }

  /**
   * The application's own use of the given asset is attributed to {@code location} (and {@code
   * symbol}, unless null), and no other code is blamed for it: every occurrence is either that call
   * site or went through a JDK subsystem ({@code via jsse}, ...). The JDK itself may use the same
   * algorithm, e.g. SHA3-256 inside ML-KEM in a JDK 27 TLS handshake.
   */
  static void assertAttributedTo(
      Cbom.Document document,
      Primitive primitive,
      String algorithm,
      String location,
      String symbol) {
    List<Occurrence> occurrences =
        entries(document, primitive, algorithm).stream()
            .flatMap(e -> e.occurrences().keySet().stream())
            .toList();
    assertThat(occurrences)
        .as(primitive + " " + algorithm + " in " + describe(document))
        .anySatisfy(
            o -> {
              assertThat(o.location()).isEqualTo(location);
              assertThat(o.via()).isNull();
              if (symbol != null) {
                assertThat(o.symbol()).isEqualTo(symbol);
              }
            })
        .allSatisfy(
            o -> {
              if (o.via() == null) {
                assertThat(o.location()).isEqualTo(location);
              }
            });
  }

  static String describe(Cbom.Document document) {
    StringBuilder sb = new StringBuilder();
    document
        .inventory()
        .entries()
        .forEach(e -> sb.append("\n  ").append(e.asset()).append(' ').append(e.occurrences()));
    return sb.toString();
  }
}
