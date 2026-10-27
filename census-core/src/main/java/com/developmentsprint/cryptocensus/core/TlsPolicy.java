package com.developmentsprint.cryptocensus.core;

import static com.developmentsprint.cryptocensus.core.TechnicalFacts.QuantumExposure.NOT_ESTABLISHED;
import static com.developmentsprint.cryptocensus.core.TechnicalFacts.QuantumExposure.RESISTANT;
import static com.developmentsprint.cryptocensus.core.TechnicalFacts.QuantumExposure.VULNERABLE;

import com.developmentsprint.cryptocensus.core.EvidenceDocument.AttemptRef;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Flag;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Handshake;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Outcome;
import com.developmentsprint.cryptocensus.core.TechnicalFacts.QuantumExposure;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One canonical TLS facts policy per evidence run. Capability qualification belongs to the caller.
 * Group and signature names follow the IANA TLS Parameters registry; v1 recognizes only the two
 * selected groups demonstrated by the production fixtures. Unknown identifiers never earn approval.
 */
public final class TlsPolicy {
  public static final String VERSION = "tls-facts-v1";

  private static final Set<String> CLASSICAL_SIGNATURES =
      Set.of(
          "ecdsa_secp256r1_sha256",
          "ecdsa_secp384r1_sha384",
          "ecdsa_secp521r1_sha512",
          "rsa_pss_rsae_sha256",
          "rsa_pss_rsae_sha384",
          "rsa_pss_rsae_sha512",
          "rsa_pss_pss_sha256",
          "rsa_pss_pss_sha384",
          "rsa_pss_pss_sha512",
          "ed25519",
          "ed448");
  private final Map<AttemptRef, Handshake> attempts;

  /** Indexes one run's bounded attempt list; duplicate references are rejected. */
  public TlsPolicy(List<Handshake> handshakes) {
    List<Handshake> bounded =
        ContextModel.bounded(handshakes, EvidenceDocument.MAX_HANDSHAKES, "TLS facts attempts");
    Map<AttemptRef, Handshake> indexed = new HashMap<>();
    for (Handshake handshake : bounded) {
      if (indexed.put(handshake.ref(), handshake) != null) {
        throw new IllegalArgumentException("Duplicate TLS attempt reference");
      }
    }
    attempts = Map.copyOf(indexed);
  }

  /** Returns facts for an indexed attempt; a missing reference is an explicit argument error. */
  public TlsFacts facts(AttemptRef reference) {
    Handshake handshake = attempts.get(Objects.requireNonNull(reference));
    if (handshake == null) {
      throw new IllegalArgumentException("TLS attempt is not in this evidence run");
    }
    return evaluate(handshake, new HashSet<>());
  }

  /** Requires the supplied record to equal this run's indexed record, preventing substitution. */
  public TlsFacts facts(Handshake handshake) {
    Objects.requireNonNull(handshake);
    if (!handshake.equals(attempts.get(handshake.ref()))) {
      throw new IllegalArgumentException("TLS attempt differs from this evidence run");
    }
    return facts(handshake.ref());
  }

  private TlsFacts evaluate(Handshake handshake, Set<AttemptRef> path) {
    path.add(handshake.ref());
    List<String> limitations = new ArrayList<>();
    QuantumExposure exchange = exchange(handshake, limitations);
    Origin origin = origin(handshake, path, limitations);
    QuantumExposure confidentiality = confidentiality(handshake, exchange, origin, limitations);
    path.remove(handshake.ref());
    return new TlsFacts(
        handshake.outcome(),
        exchange,
        confidentiality,
        authentication(handshake.negotiation().localSignatureScheme(), "local", limitations),
        authentication(handshake.negotiation().peerSignatureScheme(), "peer", limitations),
        handshake.resumption().resumed(),
        handshake.resumption().freshKeyExchange(),
        origin.lineage(),
        origin.exposure(),
        limitations,
        VERSION);
  }

  private Origin origin(Handshake handshake, Set<AttemptRef> path, List<String> limitations) {
    if (handshake.resumption().resumed() == Flag.NO) {
      return new Origin(TlsFacts.Lineage.NOT_APPLICABLE, NOT_ESTABLISHED);
    }
    if (handshake.resumption().resumed() == Flag.UNKNOWN) {
      limitations.add("resumption-unobserved");
      return new Origin(TlsFacts.Lineage.UNKNOWN, NOT_ESTABLISHED);
    }
    AttemptRef reference = handshake.resumption().pskOrigin();
    Handshake parent = reference == null ? null : attempts.get(reference);
    if (parent == null
        || parent.outcome() != Outcome.COMPLETED
        || path.contains(reference)
        || path.size() >= EvidenceDocument.MAX_PSK_DEPTH) {
      limitations.add("psk-lineage-unverified");
      return new Origin(TlsFacts.Lineage.UNKNOWN, NOT_ESTABLISHED);
    }
    TlsFacts facts = evaluate(parent, path);
    if (facts.lineage() == TlsFacts.Lineage.UNKNOWN) {
      limitations.add("psk-ancestry-unverified");
      return new Origin(TlsFacts.Lineage.UNKNOWN, facts.confidentialityExposure());
    }
    return new Origin(TlsFacts.Lineage.VERIFIED, facts.confidentialityExposure());
  }

  private static QuantumExposure exchange(Handshake handshake, List<String> limitations) {
    if (handshake.outcome() != Outcome.COMPLETED) {
      limitations.add("handshake-not-completed");
      return NOT_ESTABLISHED;
    }
    if (!"TLSv1.3".equals(handshake.negotiation().protocol())) {
      limitations.add("protocol-selection-unverified");
      return NOT_ESTABLISHED;
    }
    if (handshake.resumption().freshKeyExchange() != Flag.YES) {
      limitations.add("fresh-exchange-unverified");
      return NOT_ESTABLISHED;
    }
    int group = handshake.negotiation().selectedGroupId();
    String name = handshake.negotiation().selectedGroupName();
    String expected =
        switch (group) {
          case 29 -> "x25519";
          case 4588 -> "X25519MLKEM768";
          default -> "";
        };
    if (expected.isEmpty()) {
      limitations.add("selected-group-unrecognized");
      return NOT_ESTABLISHED;
    }
    if (!name.isEmpty() && !expected.equals(name)) {
      limitations.add("selected-group-name-mismatch");
      return NOT_ESTABLISHED;
    }
    return group == 4588 ? RESISTANT : VULNERABLE;
  }

  private static QuantumExposure confidentiality(
      Handshake handshake, QuantumExposure exchange, Origin origin, List<String> limitations) {
    if (exchange == NOT_ESTABLISHED) {
      return NOT_ESTABLISHED;
    }
    if (handshake.resumption().resumed() == Flag.NO) {
      return exchange;
    }
    if (origin.lineage() != TlsFacts.Lineage.VERIFIED) {
      limitations.add("composite-confidentiality-needs-psk-origin");
      return NOT_ESTABLISHED;
    }
    if (exchange == origin.exposure()) {
      return exchange;
    }
    limitations.add("mixed-or-unknown-psk-protection");
    return NOT_ESTABLISHED;
  }

  private static QuantumExposure authentication(
      String scheme, String direction, List<String> limitations) {
    if (CLASSICAL_SIGNATURES.contains(scheme)) {
      return VULNERABLE;
    }
    if (!scheme.isEmpty()) {
      limitations.add(direction + "-signature-unrecognized");
    }
    return NOT_ESTABLISHED;
  }

  private record Origin(TlsFacts.Lineage lineage, QuantumExposure exposure) {}
}
