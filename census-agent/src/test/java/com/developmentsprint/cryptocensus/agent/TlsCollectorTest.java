package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Association;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Counter;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Flag;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Outcome;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.ScopeContext;
import java.lang.reflect.Proxy;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TlsCollectorTest {
  private final AtomicLong clock = new AtomicLong();
  private final Map<Counter, Long> health = new EnumMap<>(Counter.class);
  private final AtomicReference<ScopeContext> context =
      new AtomicReference<>(new ScopeContext("initial", List.of(), Association.NO_SCOPE));

  @BeforeEach
  void configure() {
    TlsCollector.configure(
        context::get, counter -> health.merge(counter, 1L, Long::sum), clock::get);
    TlsCollector.enable();
  }

  @AfterEach
  void close() {
    TlsCollector.close();
  }

  @Test
  void keepsInitiationAndAssetOwnershipAcrossNestedDispatchAndDuplicateTerminalEvents() {
    FakeHandshake first = handshake();
    FakeHandshake second = handshake();
    TlsCollector.created(first);
    context.set(new ScopeContext("later", List.of(), Association.NO_SCOPE));
    TlsCollector.created(second);
    Object firstFrame = TlsCollector.enter(first);
    TlsCollector.asset("first-asset");
    Object secondFrame = TlsCollector.enter(second);
    TlsCollector.asset("second-asset");
    TlsCollector.exit(secondFrame);
    Object unknownFrame = TlsCollector.enter(new Object());
    TlsCollector.asset("unowned-asset");
    TlsCollector.exit(unknownFrame);
    TlsCollector.asset("first-asset");
    TlsCollector.exit(firstFrame);
    TlsCollector.asset("outside-asset");
    TlsCollector.signature(first, new FakeSignature(), false);
    TlsCollector.signature(first, new FakeSignature(), true);
    TlsCollector.completed(first.conContext, first);
    TlsCollector.completed(first.conContext, first);
    TlsCollector.failed(first.conContext);
    TlsCollector.failed(second.conContext);
    TlsCollector.closed(second.conContext);
    assertThat(TlsCollector.snapshot())
        .hasSize(2)
        .satisfiesExactly(
            observed -> {
              assertThat(observed.outcome()).isEqualTo(Outcome.COMPLETED);
              assertThat(observed.initiation().identityId()).isEqualTo("initial");
              assertThat(observed.relatedAssetRefs()).containsExactly("first-asset");
              assertThat(observed.negotiation().localSignatureScheme()).isEqualTo("test-scheme");
              assertThat(observed.negotiation().peerSignatureScheme()).isEqualTo("test-scheme");
            },
            observed -> {
              assertThat(observed.outcome()).isEqualTo(Outcome.FAILED);
              assertThat(observed.initiation().identityId()).isEqualTo("later");
              assertThat(observed.relatedAssetRefs()).containsExactly("second-asset");
            });
    assertThat(health.get(Counter.LOST_ATTRIBUTION)).isEqualTo(2);
  }

  @Test
  void observesClientTicketLineageWithoutCountingTicketsOrCarryingSignaturesForward() {
    FakeHandshake original = handshake();
    TlsCollector.created(original);
    Object ticket = new Object();
    TlsCollector.ticket(ticket, original.conContext.conSession);
    TlsCollector.completed(original.conContext, original);
    FakeHandshake resumed = handshake();
    resumed.isResumption = true;
    resumed.resumingSession = ticket;
    TlsCollector.created(resumed);
    TlsCollector.completed(resumed.conContext, resumed);
    assertThat(TlsCollector.snapshot()).hasSize(2);
    var observed = TlsCollector.snapshot().get(1);
    assertThat(observed.resumption().resumed()).isEqualTo(Flag.YES);
    assertThat(observed.resumption().pskOrigin()).isEqualTo(TlsCollector.snapshot().get(0).ref());
    assertThat(observed.resumption().freshKeyExchange()).isEqualTo(Flag.UNKNOWN);
    assertThat(observed.negotiation().localSignatureScheme()).isEmpty();
    assertThat(observed.negotiation().peerSignatureScheme()).isEmpty();
  }

  @Test
  void keepsUnknownOriginAndFailedCompletionExplicit() {
    FakeHandshake resumed = handshake();
    resumed.isResumption = true;
    resumed.resumingSession = new Object();
    TlsCollector.created(resumed);
    TlsCollector.completed(resumed.conContext, resumed);
    FakeHandshake notNegotiated = handshake();
    notNegotiated.conContext.isNegotiated = false;
    TlsCollector.created(notNegotiated);
    TlsCollector.completed(notNegotiated.conContext, notNegotiated);
    assertThat(TlsCollector.snapshot().get(0).diagnostics()).contains("psk-origin-unobserved");
    assertThat(TlsCollector.snapshot().get(1).outcome()).isEqualTo(Outcome.UNKNOWN);
    assertThat(TlsCollector.snapshot().get(1).diagnostics()).contains("completion-unverified");
  }

  @Test
  void brokenMetadataAndScopeDoNotEscapeAndCloseLeavesNoActiveAttribution() {
    TlsCollector.created(new Object());
    TlsCollector.handshake(new Object());
    FakeHandshake pending = handshake();
    TlsCollector.created(pending);
    Object token = TlsCollector.enter(pending);
    TlsCollector.exit(new Object());
    TlsCollector.exit(token);
    TlsCollector.exit(token);
    TlsCollector.close();
    assertThat(TlsCollector.snapshot())
        .singleElement()
        .satisfies(
            observed -> {
              assertThat(observed.outcome()).isEqualTo(Outcome.UNKNOWN);
              assertThat(observed.diagnostics()).contains("shutdown-before-completion");
            });
    TlsCollector.created(handshake());
    TlsCollector.asset("disabled");
    assertThat(TlsCollector.enter(pending)).isNull();
    assertThat(TlsCollector.handshake(pending.conContext)).isNull();
    assertThat(health.get(Counter.RECORDING_FAILURES)).isEqualTo(3);
    assertThat(health.get(Counter.LOST_ATTRIBUTION)).isEqualTo(1);
  }

  @Test
  void boundsRelatedAssetsAndFinalizedAttemptRetention() {
    FakeHandshake pending = handshake();
    TlsCollector.created(pending);
    Object token = TlsCollector.enter(pending);
    for (int index = 0; index < 65; index++) {
      TlsCollector.asset("asset-" + index);
    }
    TlsCollector.exit(token);
    TlsCollector.closed(pending.conContext);
    assertThat(TlsCollector.snapshot().get(0).relatedAssetRefs()).hasSize(64);
    assertThat(health.get(Counter.LOST_ATTRIBUTION)).isEqualTo(1);
    for (int index = 1; index < EvidenceDocument.MAX_HANDSHAKES; index++) {
      FakeHandshake next = handshake();
      TlsCollector.created(next);
      TlsCollector.failed(next.conContext);
    }
    TlsCollector.created(handshake());
    assertThat(TlsCollector.snapshot()).hasSize(EvidenceDocument.MAX_HANDSHAKES);
    assertThat(health.get(Counter.DROPPED_HANDSHAKES)).isEqualTo(1);
  }

  @Test
  void timeoutAndRepeatedTransportAttemptsRemainDistinct() {
    FakeHandshake first = handshake();
    TlsCollector.created(first);
    TlsCollector.created(first);
    clock.addAndGet(TimeUnit.MINUTES.toNanos(6));
    assertThat(TlsCollector.snapshot())
        .singleElement()
        .satisfies(
            observed -> {
              assertThat(observed.outcome()).isEqualTo(Outcome.UNKNOWN);
              assertThat(observed.diagnostics()).contains("handshake-observation-timeout");
            });
    TlsCollector.completed(first.conContext, first);
    assertThat(TlsCollector.snapshot()).hasSize(1);
    FakeHandshake second = new FakeHandshake(first.conContext);
    first.conContext.handshakeContext = second;
    TlsCollector.created(second);
    TlsCollector.failed(first.conContext);
    assertThat(TlsCollector.snapshot().get(1).connectionId())
        .isEqualTo(TlsCollector.snapshot().get(0).connectionId());
    assertThat(TlsCollector.snapshot().get(1).attempt()).isEqualTo(2);
    TlsCollector.installationFailed();
    assertThat(health.get(Counter.TRANSFORM_ERRORS)).isEqualTo(1);
  }

  @Test
  void boundedOwnersMaskOverflowUntilRootExitAndRecoverForFreshDispatch() {
    FakeHandshake handshake = handshake();
    TlsCollector.created(handshake);
    List<Object> tokens = new java.util.ArrayList<>();
    for (int depth = 0; depth < 33; depth++) {
      tokens.add(TlsCollector.enter(handshake));
    }
    TlsCollector.asset("overflow");
    for (int depth = tokens.size() - 1; depth >= 0; depth--) {
      TlsCollector.exit(tokens.get(depth));
    }
    Object fresh = TlsCollector.enter(handshake);
    TlsCollector.asset("recovered");
    TlsCollector.exit(fresh);
    TlsCollector.failed(handshake.conContext);
    assertThat(TlsCollector.snapshot().get(0).relatedAssetRefs()).containsExactly("recovered");
    assertThat(health.get(Counter.LOST_ATTRIBUTION)).isEqualTo(2);
  }

  @Test
  void entryAndRestorationFaultsFailOwnershipClosedAndRemainVisible() {
    Runnable failure =
        () -> {
          throw new IllegalStateException("injected");
        };
    for (int point = 0; point < 3; point++) {
      configure();
      TlsCollector.ownerFaults(
          point == 0 ? failure : null, point == 1 ? failure : null, point == 2 ? failure : null);
      FakeHandshake handshake = handshake();
      TlsCollector.created(handshake);
      Object token = TlsCollector.enter(handshake);
      TlsCollector.exit(token);
      TlsCollector.asset("after-fault");
      TlsCollector.failed(handshake.conContext);
      assertThat(TlsCollector.snapshot().get(0).relatedAssetRefs()).isEmpty();
      assertThat(TlsCollector.recordingFailures()).isPositive();
      TlsCollector.close();
    }
  }

  @Test
  void failingMetadataCallbacksAndCounterSinkDoNotEscape() {
    FakeHandshake handshake = handshake();
    TlsCollector.created(handshake);
    Object token = TlsCollector.enter(handshake);
    TlsCollector.asset(null);
    TlsCollector.exit(token);
    TlsCollector.ticket(null, new Object());
    assertThat(TlsCollector.recordingFailures()).isEqualTo(2);
    TlsCollector.close();
    TlsCollector.configure(
        context::get,
        counter -> {
          throw new IllegalStateException("sink-fault");
        });
    TlsCollector.enable();
    TlsCollector.created(new Object());
    assertThat(TlsCollector.recordingFailures()).isEqualTo(2);
  }

  @Test
  void partialPublicationCannotAliasTheNextAttemptBinding() {
    FakeHandshake interrupted = handshake();
    TlsCollector.publicationFault(
        () -> {
          throw new IllegalStateException("publication-fault");
        });
    TlsCollector.created(interrupted);
    TlsCollector.publicationFault(null);
    FakeHandshake next = handshake();
    TlsCollector.created(next);
    Object old = TlsCollector.enter(interrupted);
    TlsCollector.asset("must-not-cross-associate");
    TlsCollector.exit(old);
    Object fresh = TlsCollector.enter(next);
    TlsCollector.asset("attribution-invalidated");
    TlsCollector.exit(fresh);
    TlsCollector.failed(next.conContext);
    assertThat(TlsCollector.snapshot())
        .singleElement()
        .satisfies(observed -> assertThat(observed.relatedAssetRefs()).isEmpty());
    assertThat(TlsCollector.recordingFailures()).isEqualTo(1);
  }

  @Test
  void admissionCanStopWhileAnAlreadyAdmittedCaptureHoldsTheCollectorLock() throws Exception {
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.ExecutorService workers =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    TlsCollector.configure(
        () -> {
          entered.countDown();
          try {
            if (!release.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("capture release timeout");
            }
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
          }
          return context.get();
        },
        counter -> health.merge(counter, 1L, Long::sum));
    TlsCollector.enable();
    FakeHandshake pending = handshake();
    try {
      final var capture = workers.submit(() -> TlsCollector.created(pending));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      workers.submit(TlsCollector::stopAdmission).get(1, TimeUnit.SECONDS);
      release.countDown();
      capture.get(5, TimeUnit.SECONDS);
      TlsCollector.created(handshake());
      assertThat(TlsCollector.enter(pending)).isNull();
      TlsCollector.close();
      assertThat(TlsCollector.snapshot()).hasSize(1);
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
  }

  private static FakeHandshake handshake() {
    FakeHandshake handshake = new FakeHandshake();
    handshake.conContext.handshakeContext = handshake;
    return handshake;
  }

  private static SSLSession session() {
    return (SSLSession)
        Proxy.newProxyInstance(
            SSLSession.class.getClassLoader(),
            new Class<?>[] {SSLSession.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getProtocol" -> "TLSv1.3";
                  case "getCipherSuite" -> "TLS_AES_256_GCM_SHA384";
                  default -> throw new UnsupportedOperationException(method.getName());
                });
  }

  // Deliberate structural doubles exercise collector failure/ownership logic; real JSSE uses ITs.
  // Structural fixture fields are read reflectively by the production adapter.
  @SuppressWarnings("UnusedVariable")
  private static final class FakeHandshake {
    final FakeTransport conContext;

    FakeHandshake() {
      this(new FakeTransport());
    }

    FakeHandshake(FakeTransport transport) {
      conContext = transport;
    }

    final Map<?, ?> handshakeExtensions = Map.of();
    boolean isResumption;
    Object resumingSession;
  }

  // Structural fixture fields are read reflectively by the production adapter.
  @SuppressWarnings("UnusedVariable")
  private static final class FakeTransport {
    final Object transport = new Object();
    final FakeConfig sslConfig = new FakeConfig();
    final SSLSession conSession = session();
    boolean isNegotiated = true;
    FakeHandshake handshakeContext;
  }

  // Structural fixture fields are read reflectively by the production adapter.
  @SuppressWarnings("UnusedVariable")
  private static final class FakeConfig {
    final boolean isClientMode = true;
  }

  // Structural fixture fields are read reflectively by the production adapter.
  @SuppressWarnings("UnusedVariable")
  private static final class FakeSignature {
    final FakeScheme signatureScheme = new FakeScheme();
  }

  // Structural fixture fields are read reflectively by the production adapter.
  @SuppressWarnings("UnusedVariable")
  private static final class FakeScheme {
    final String name = "test-scheme";
  }
}
