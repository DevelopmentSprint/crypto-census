package com.developmentsprint.cryptocensus.core;

import static com.developmentsprint.cryptocensus.core.TechnicalFacts.QuantumExposure.NOT_ESTABLISHED;
import static com.developmentsprint.cryptocensus.core.TechnicalFacts.QuantumExposure.RESISTANT;
import static com.developmentsprint.cryptocensus.core.TechnicalFacts.QuantumExposure.VULNERABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.EvidenceDocument.Association;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.AttemptRef;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Flag;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Handshake;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Negotiation;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Outcome;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.PeerRole;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Resumption;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.ScopeContext;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Transport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TlsPolicyTest {
  @Test
  void verifiedHybridNeverErasesClassicalAuthentication() {
    Handshake hybrid = handshake("hybrid", 4588, Outcome.COMPLETED, Flag.NO, Flag.YES, null);
    TlsFacts facts = new TlsPolicy(List.of(hybrid)).facts(hybrid);
    assertThat(facts.selectedKeyExchangeExposure()).isEqualTo(RESISTANT);
    assertThat(facts.confidentialityExposure()).isEqualTo(RESISTANT);
    assertThat(facts.peerAuthenticationExposure()).isEqualTo(VULNERABLE);
    assertThat(facts.localAuthenticationExposure()).isEqualTo(NOT_ESTABLISHED);
    assertThat(facts.lineage()).isEqualTo(TlsFacts.Lineage.NOT_APPLICABLE);
    assertThat(facts.limitations()).isEmpty();
    assertThat(facts.version()).isEqualTo(TlsPolicy.VERSION);
  }

  @Test
  void selectedGroupsRequireCompletionFreshExchangeAndKnownIdentifier() {
    List<Handshake> attempts =
        List.of(
            handshake("classical", 29, Outcome.COMPLETED, Flag.NO, Flag.YES, null),
            handshake("unknown", 65535, Outcome.COMPLETED, Flag.NO, Flag.YES, null),
            handshake("failed", 4588, Outcome.FAILED, Flag.NO, Flag.YES, null),
            handshake("unfinished", 4588, Outcome.UNKNOWN, Flag.NO, Flag.YES, null),
            handshake("no-fresh", 4588, Outcome.COMPLETED, Flag.NO, Flag.NO, null),
            handshake("unknown-fresh", 4588, Outcome.COMPLETED, Flag.NO, Flag.UNKNOWN, null));
    TlsPolicy policy = new TlsPolicy(attempts);
    assertThat(policy.facts(attempts.get(0)).confidentialityExposure()).isEqualTo(VULNERABLE);
    for (Handshake attempt : attempts.subList(1, attempts.size())) {
      assertThat(policy.facts(attempt).selectedKeyExchangeExposure()).isEqualTo(NOT_ESTABLISHED);
      assertThat(policy.facts(attempt).confidentialityExposure()).isEqualTo(NOT_ESTABLISHED);
      assertThat(policy.facts(attempt).limitations()).isNotEmpty();
    }
  }

  @Test
  void contradictoryNamesAndUnsupportedProtocolDoNotEarnApproval() {
    Handshake base = handshake("base", 4588, Outcome.COMPLETED, Flag.NO, Flag.YES, null);
    for (Negotiation negotiation :
        List.of(
            new Negotiation("TLSv1.2", "suite", 4588, "X25519MLKEM768", "", ""),
            new Negotiation("TLSv1.3", "suite", 4588, "x25519", "", ""))) {
      Handshake changed = withNegotiation(base, negotiation);
      assertThat(new TlsPolicy(List.of(changed)).facts(changed).confidentialityExposure())
          .isEqualTo(NOT_ESTABLISHED);
    }
    Handshake numericOnly =
        withNegotiation(base, new Negotiation("TLSv1.3", "suite", 4588, "", "", ""));
    assertThat(new TlsPolicy(List.of(numericOnly)).facts(numericOnly).confidentialityExposure())
        .isEqualTo(RESISTANT);
  }

  @Test
  void lineageIsIndependentFromFreshHybridAndCurrentSignatures() {
    Handshake classical = handshake("classical", 29, Outcome.COMPLETED, Flag.NO, Flag.YES, null);
    Handshake mixed =
        handshake("mixed", 4588, Outcome.COMPLETED, Flag.YES, Flag.YES, classical.ref());
    mixed =
        withNegotiation(mixed, new Negotiation("TLSv1.3", "suite", 4588, "X25519MLKEM768", "", ""));
    TlsFacts facts = new TlsPolicy(List.of(classical, mixed)).facts(mixed);
    assertThat(facts.selectedKeyExchangeExposure()).isEqualTo(RESISTANT);
    assertThat(facts.pskOriginExposure()).isEqualTo(VULNERABLE);
    assertThat(facts.lineage()).isEqualTo(TlsFacts.Lineage.VERIFIED);
    assertThat(facts.confidentialityExposure()).isEqualTo(NOT_ESTABLISHED);
    assertThat(facts.localAuthenticationExposure()).isEqualTo(NOT_ESTABLISHED);
    assertThat(facts.peerAuthenticationExposure()).isEqualTo(NOT_ESTABLISHED);
    assertThat(facts.limitations()).contains("mixed-or-unknown-psk-protection");
  }

  @Test
  void sameProtectionOriginCanEstablishConservativeCompositeFacts() {
    for (int group : List.of(29, 4588)) {
      Handshake parent = handshake("parent", group, Outcome.COMPLETED, Flag.NO, Flag.YES, null);
      Handshake resumed =
          handshake("resumed", group, Outcome.COMPLETED, Flag.YES, Flag.YES, parent.ref());
      TlsFacts facts = new TlsPolicy(List.of(parent, resumed)).facts(resumed);
      assertThat(facts.confidentialityExposure()).isEqualTo(group == 4588 ? RESISTANT : VULNERABLE);
      assertThat(facts.lineage()).isEqualTo(TlsFacts.Lineage.VERIFIED);
    }
  }

  @Test
  void unknownMissingFailedAndCyclicOriginsStayUnknown() {
    Handshake failed = handshake("failed", 4588, Outcome.FAILED, Flag.NO, Flag.YES, null);
    Handshake unknown = handshake("unknown", 4588, Outcome.COMPLETED, Flag.UNKNOWN, Flag.YES, null);
    List<Handshake> attempts =
        List.of(
            failed,
            unknown,
            handshake("no-origin", 4588, Outcome.COMPLETED, Flag.YES, Flag.YES, null),
            handshake(
                "missing",
                4588,
                Outcome.COMPLETED,
                Flag.YES,
                Flag.YES,
                new AttemptRef("absent", 1)),
            handshake("failed-origin", 4588, Outcome.COMPLETED, Flag.YES, Flag.YES, failed.ref()),
            handshake("unknown-origin", 4588, Outcome.COMPLETED, Flag.YES, Flag.YES, unknown.ref()),
            handshake(
                "cycle-a",
                4588,
                Outcome.COMPLETED,
                Flag.YES,
                Flag.YES,
                new AttemptRef("cycle-b", 1)),
            handshake(
                "cycle-b",
                4588,
                Outcome.COMPLETED,
                Flag.YES,
                Flag.YES,
                new AttemptRef("cycle-a", 1)));
    TlsPolicy policy = new TlsPolicy(attempts);
    for (Handshake attempt : attempts.subList(1, attempts.size())) {
      TlsFacts facts = policy.facts(attempt);
      assertThat(facts.lineage()).isEqualTo(TlsFacts.Lineage.UNKNOWN);
      assertThat(facts.confidentialityExposure()).isEqualTo(NOT_ESTABLISHED);
    }
  }

  @Test
  void excessiveAncestryAndUnknownOriginProtectionRemainExplicit() {
    List<Handshake> attempts = new ArrayList<>();
    attempts.add(handshake("0", 65535, Outcome.COMPLETED, Flag.NO, Flag.YES, null));
    for (int i = 1; i <= EvidenceDocument.MAX_PSK_DEPTH; i++) {
      attempts.add(
          handshake(
              Integer.toString(i),
              4588,
              Outcome.COMPLETED,
              Flag.YES,
              Flag.YES,
              attempts.get(i - 1).ref()));
    }
    TlsPolicy policy = new TlsPolicy(attempts);
    assertThat(policy.facts(attempts.get(1)).lineage()).isEqualTo(TlsFacts.Lineage.VERIFIED);
    assertThat(policy.facts(attempts.get(1)).pskOriginExposure()).isEqualTo(NOT_ESTABLISHED);
    assertThat(policy.facts(attempts.get(attempts.size() - 1)).lineage())
        .isEqualTo(TlsFacts.Lineage.UNKNOWN);
  }

  @Test
  void indexBoundsAndExactRunRecordsPreventReferenceSubstitution() {
    Handshake base = handshake("base", 29, Outcome.COMPLETED, Flag.NO, Flag.YES, null);
    TlsPolicy policy = new TlsPolicy(List.of(base));
    assertThatThrownBy(() -> policy.facts(new AttemptRef("other-run", 1)))
        .isInstanceOf(IllegalArgumentException.class);
    Handshake replacement = handshake("base", 4588, Outcome.COMPLETED, Flag.NO, Flag.YES, null);
    assertThatThrownBy(() -> policy.facts(replacement))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TlsPolicy(List.of(base, base)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new TlsPolicy(Collections.nCopies(EvidenceDocument.MAX_HANDSHAKES + 1, base)))
        .isInstanceOf(IllegalArgumentException.class);
    Handshake unknownSignature =
        withNegotiation(
            base,
            new Negotiation(
                "TLSv1.3", "suite", 29, "x25519", "future_scheme", "rsa_pss_rsae_sha256"));
    TlsFacts facts = new TlsPolicy(List.of(unknownSignature)).facts(unknownSignature);
    assertThat(facts.localAuthenticationExposure()).isEqualTo(NOT_ESTABLISHED);
    assertThat(facts.peerAuthenticationExposure()).isEqualTo(VULNERABLE);
  }

  private static Handshake handshake(
      String id, int group, Outcome outcome, Flag resumed, Flag fresh, AttemptRef origin) {
    String name = group == 29 ? "x25519" : group == 4588 ? "X25519MLKEM768" : "";
    return new Handshake(
        id,
        1,
        Transport.SOCKET,
        PeerRole.CLIENT,
        outcome,
        new Negotiation(
            "TLSv1.3", "TLS_AES_256_GCM_SHA384", group, name, "", "ecdsa_secp256r1_sha256"),
        new Resumption(resumed, fresh, origin),
        List.of(),
        "sunjsse-structural-v1",
        List.of(),
        new ScopeContext("test", List.of(), Association.NO_SCOPE));
  }

  private static Handshake withNegotiation(Handshake handshake, Negotiation negotiation) {
    return new Handshake(
        handshake.connectionId(),
        handshake.attempt(),
        handshake.transport(),
        handshake.role(),
        handshake.outcome(),
        negotiation,
        handshake.resumption(),
        handshake.relatedAssetRefs(),
        handshake.adapter(),
        handshake.diagnostics(),
        handshake.initiation());
  }
}
