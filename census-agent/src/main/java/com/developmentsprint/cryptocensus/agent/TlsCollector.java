package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.AttemptRef;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Counter;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Flag;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Handshake;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Negotiation;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Outcome;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.PeerRole;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Resumption;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.ScopeContext;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Transport;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.net.ssl.SSLSession;

/** Bounded, object-owned TLS evidence. All exported identifiers are opaque and run-local. */
public final class TlsCollector {
  private static final int LIMIT = EvidenceDocument.MAX_HANDSHAKES;
  private static final long TIMEOUT = TimeUnit.MINUTES.toNanos(5);
  private static final TlsIdentityMap<Connection> CONNECTIONS = new TlsIdentityMap<>(LIMIT);
  private static final TlsIdentityMap<Attempt> HANDSHAKES = new TlsIdentityMap<>(LIMIT);
  private static final TlsSessionLineage<AttemptRef> SESSIONS = new TlsSessionLineage<>(LIMIT * 2);
  private static final Map<AttemptRef, Attempt> PENDING = new LinkedHashMap<>();
  private static final Map<AttemptRef, Handshake> FINISHED = new LinkedHashMap<>();
  private static final ReentrantLock LOCK = new ReentrantLock();
  private static final AtomicLong OWNER_GENERATIONS = new AtomicLong();
  private static final List<Attempt> OWNED = new ArrayList<>();
  private static FlowScopes owners = new FlowScopes(32, OWNER_GENERATIONS);
  private static long ownerLosses;
  private static long ownerEntryFailures;
  private static long ownerRestoreFailures;
  private static long ownerIntegrityFailures;
  private static Runnable beforePublication;
  private static boolean ownershipFailed;
  private static Supplier<ScopeContext> contexts;
  private static Consumer<Counter> counters;
  private static LongSupplier clock = System::nanoTime;
  private static volatile boolean enabled;
  private static long nextConnection;
  private static long recordingFailures;

  private TlsCollector() {}

  /** Configures once before installation, independently of the primary inventory lifecycle. */
  public static void configure(
      Supplier<ScopeContext> contextSupplier, Consumer<Counter> counterSink) {
    LOCK.lock();
    try {
      configure(contextSupplier, counterSink, System::nanoTime);
    } finally {
      LOCK.unlock();
    }
  }

  static void configure(
      Supplier<ScopeContext> contextSupplier,
      Consumer<Counter> counterSink,
      LongSupplier monotonicClock) {
    LOCK.lock();
    try {
      clock = Objects.requireNonNull(monotonicClock);
      enabled = false;
      CONNECTIONS.clear();
      HANDSHAKES.clear();
      SESSIONS.clear();
      PENDING.clear();
      FINISHED.clear();
      owners.disable();
      OWNED.clear();
      owners = new FlowScopes(32, OWNER_GENERATIONS);
      ownerLosses = 0;
      ownerEntryFailures = 0;
      ownerRestoreFailures = 0;
      ownerIntegrityFailures = 0;
      beforePublication = null;
      ownershipFailed = false;
      nextConnection = 0;
      recordingFailures = 0;
      contexts = Objects.requireNonNull(contextSupplier);
      counters = Objects.requireNonNull(counterSink);
    } finally {
      LOCK.unlock();
    }
  }

  static void enable() {
    LOCK.lock();
    try {
      enabled = contexts != null && counters != null;
      owners.enable();
    } finally {
      LOCK.unlock();
    }
  }

  static void installationFailed() {
    LOCK.lock();
    try {
      enabled = false;
      count(Counter.TRANSFORM_ERRORS);
    } finally {
      LOCK.unlock();
    }
  }

  /** Called only after a real handshake context constructor succeeds. */
  public static void created(Object handshake) {
    LOCK.lock();
    try {
      if (!enabled || TlsSunJsse.postHandshake(handshake)) {
        return;
      }
      observe(() -> create(handshake));
    } finally {
      LOCK.unlock();
    }
  }

  private static void create(Object handshake) throws ReflectiveOperationException {
    expire(clock.getAsLong());
    if (HANDSHAKES.get(handshake) != null) {
      return;
    }
    if (PENDING.size() + FINISHED.size() >= LIMIT) {
      count(Counter.DROPPED_HANDSHAKES);
      return;
    }
    Object transport = TlsSunJsse.transport(handshake);
    Connection connection = CONNECTIONS.get(transport);
    if (connection == null) {
      connection = new Connection("tls-" + ++nextConnection);
      if (!CONNECTIONS.put(transport, connection)) {
        count(Counter.DROPPED_HANDSHAKES);
        return;
      }
    }
    ScopeContext initiation = Objects.requireNonNull(contexts.get());
    Attempt attempt =
        new Attempt(
            new AttemptRef(connection.id, ++connection.sequence),
            handshake,
            transport,
            TlsSunJsse.socket(transport) ? Transport.SOCKET : Transport.ENGINE,
            TlsSunJsse.client(transport) ? PeerRole.CLIENT : PeerRole.SERVER,
            initiation);
    boolean published = false;
    try {
      if (HANDSHAKES.put(handshake, attempt)) {
        PENDING.put(attempt.ref, attempt);
        if (beforePublication != null) {
          beforePublication.run();
        }
        OWNED.add(attempt);
        published = true;
      } else {
        count(Counter.DROPPED_HANDSHAKES);
      }
    } finally {
      if (!published) {
        // Any partially committed ownership transaction invalidates attribution for this run.
        ownershipFailed = true;
        HANDSHAKES.remove(handshake);
        PENDING.remove(attempt.ref);
        OWNED.remove(attempt);
      }
    }
  }

  /** Activates only a verified handshake owner for the dynamic duration of JSSE dispatch. */
  public static Object enter(Object handshake) {
    LOCK.lock();
    try {
      if (!enabled || ownershipFailed) {
        return null;
      }
      return ownerEntry(handshake);
    } finally {
      LOCK.unlock();
    }
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static Object ownerEntry(Object handshake) {
    try {
      Attempt attempt = HANDSHAKES.get(handshake);
      long token = owners.enter(attempt == null ? 0 : attempt.binding);
      ownerHealth();
      return token;
    } catch (Throwable failure) {
      owners.disable();
      ownershipFailed = true;
      count(Counter.RECORDING_FAILURES);
      return null;
    }
  }

  /** Restores the exact prior dynamic owner, including nested or untracked dispatch. */
  public static void exit(Object token) {
    LOCK.lock();
    try {
      observe(
          () -> {
            if (token instanceof Long balancingToken) {
              owners.exit(balancingToken);
              ownerHealth();
            }
          });
    } finally {
      LOCK.unlock();
    }
  }

  /** Associates only retained core assets recorded inside an owned handshake dispatch. */
  public static void asset(String assetRef) {
    LOCK.lock();
    try {
      if (!enabled) {
        return;
      }
      observe(() -> associate(assetRef));
    } finally {
      LOCK.unlock();
    }
  }

  private static void associate(String assetRef) {
    if (ownershipFailed) {
      count(Counter.LOST_ATTRIBUTION);
      return;
    }
    FlowScopes.Snapshot scope = owners.current();
    if (scope.status() != FlowScopes.Status.SYNCHRONOUS_SCOPE) {
      if (scope.status() == FlowScopes.Status.SCOPE_LOST) {
        count(Counter.LOST_ATTRIBUTION);
      }
      return;
    }
    int binding = scope.bindings().get(scope.bindings().size() - 1);
    if (binding < 1 || binding > OWNED.size()) {
      count(Counter.LOST_ATTRIBUTION);
      return;
    }
    Attempt attempt = OWNED.get(binding - 1);
    if (attempt.terminal) {
      return;
    }
    if (attempt.assets.size() < 64 || attempt.assets.contains(assetRef)) {
      attempt.assets.add(Objects.requireNonNull(assetRef));
    } else {
      count(Counter.LOST_ATTRIBUTION);
      attempt.diagnostic("related-assets-truncated");
    }
  }

  private static void ownerHealth() {
    long losses = owners.overflowed();
    long entryFailures = owners.entryFailures();
    long restoreFailures = owners.restoreFailures();
    long integrityFailures = owners.integrityFailures();
    if (losses > ownerLosses) {
      count(Counter.LOST_ATTRIBUTION);
      ownerLosses = losses;
    }
    if (entryFailures > ownerEntryFailures
        || restoreFailures > ownerRestoreFailures
        || integrityFailures > ownerIntegrityFailures) {
      count(Counter.RECORDING_FAILURES);
      count(Counter.LOST_ATTRIBUTION);
      ownerEntryFailures = entryFailures;
      ownerRestoreFailures = restoreFailures;
      ownerIntegrityFailures = integrityFailures;
    }
  }

  static void publicationFault(Runnable failure) {
    LOCK.lock();
    try {
      beforePublication = failure;
    } finally {
      LOCK.unlock();
    }
  }

  static void ownerFaults(Runnable beforeEntry, Runnable beforeRestore, Runnable afterRestore) {
    LOCK.lock();
    try {
      owners = new FlowScopes(32, beforeEntry, beforeRestore, afterRestore);
      owners.enable();
    } finally {
      LOCK.unlock();
    }
  }

  /** Captures the context before finishHandshake clears the owning transport field. */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  public static Object handshake(Object transport) {
    LOCK.lock();
    try {
      if (!enabled) {
        return null;
      }
      try {
        return TlsSunJsse.field(transport, "handshakeContext");
      } catch (Throwable failure) {
        count(Counter.RECORDING_FAILURES);
        return null;
      }
    } finally {
      LOCK.unlock();
    }
  }

  /** Successful JSSE completion is distinct from session construction or ticket processing. */
  public static void completed(Object transport, Object handshake) {
    LOCK.lock();
    try {
      if (!enabled) {
        return;
      }
      Attempt attempt = HANDSHAKES.get(handshake);
      if (attempt == null || attempt.terminal || !attempt.transport.refersTo(transport)) {
        return;
      }
      observe(
          () -> {
            try {
              complete(attempt, transport, handshake);
            } catch (ReflectiveOperationException failure) {
              finish(attempt, Outcome.UNKNOWN, "completion-metadata-unavailable");
              throw failure;
            }
          });
    } finally {
      LOCK.unlock();
    }
  }

  private static void complete(Attempt attempt, Object transport, Object handshake)
      throws ReflectiveOperationException {
    if (!Boolean.TRUE.equals(TlsSunJsse.field(transport, "isNegotiated"))) {
      finish(attempt, Outcome.UNKNOWN, "completion-unverified");
      return;
    }
    SSLSession session = TlsSunJsse.session(transport);
    attempt.protocol = session.getProtocol();
    attempt.suite = session.getCipherSuite();
    attempt.group = TlsSunJsse.group(handshake);
    attempt.groupName = TlsSunJsse.groupName(attempt.group);
    attempt.resumed =
        Boolean.TRUE.equals(TlsSunJsse.field(handshake, "isResumption")) ? Flag.YES : Flag.NO;
    if (attempt.resumed == Flag.YES) {
      attempt.origin = SESSIONS.origin(TlsSunJsse.field(handshake, "resumingSession"));
      if (!validOrigin(attempt.origin)) {
        attempt.origin = null;
      }
      if (attempt.origin == null) {
        attempt.diagnostic("psk-origin-unobserved");
      }
    }
    if (!"TLSv1.3".equals(attempt.protocol)) {
      attempt.diagnostic("tls-version-selection-partial");
    } else if (attempt.group < 0) {
      attempt.diagnostic("selected-key-share-unobserved");
    }
    finish(attempt, Outcome.COMPLETED, null);
    if (!SESSIONS.completed(session, attempt.ref)) {
      count(Counter.LOST_ATTRIBUTION);
    }
  }

  private static boolean validOrigin(AttemptRef origin) {
    AttemptRef current = origin;
    for (int depth = 1; current != null && depth < EvidenceDocument.MAX_PSK_DEPTH; depth++) {
      Handshake parent = FINISHED.get(current);
      if (parent == null || parent.outcome() != Outcome.COMPLETED) {
        return false;
      }
      current = parent.resumption().pskOrigin();
    }
    return current == null;
  }

  /** Failure and close paths finalize at most one attempt without manufacturing negotiation. */
  public static void failed(Object transport) {
    LOCK.lock();
    try {
      terminal(transport, Outcome.FAILED, "handshake-failed");
    } finally {
      LOCK.unlock();
    }
  }

  /** An orderly close before completion is unknown, not a successful handshake. */
  public static void closed(Object transport) {
    LOCK.lock();
    try {
      terminal(transport, Outcome.UNKNOWN, "transport-closed-before-completion");
    } finally {
      LOCK.unlock();
    }
  }

  private static void terminal(Object transport, Outcome outcome, String diagnostic) {
    if (!enabled) {
      return;
    }
    observe(
        () -> {
          Attempt attempt = HANDSHAKES.get(TlsSunJsse.field(transport, "handshakeContext"));
          if (attempt != null && attempt.transport.refersTo(transport)) {
            finish(attempt, outcome, diagnostic);
          }
        });
  }

  /** Captures only successful local production or peer verification of CertificateVerify. */
  public static void signature(Object handshake, Object message, boolean peer) {
    LOCK.lock();
    try {
      if (!enabled) {
        return;
      }
      observe(
          () -> {
            Attempt attempt = HANDSHAKES.get(handshake);
            if (attempt != null && !attempt.terminal) {
              String scheme = TlsSunJsse.signature(message);
              if (peer) {
                attempt.peerSignature = scheme;
              } else {
                attempt.localSignature = scheme;
              }
            }
          });
    } finally {
      LOCK.unlock();
    }
  }

  /** Ticket duplicates never create attempts or overwrite an already observed completion. */
  public static void ticket(Object session, Object parent) {
    LOCK.lock();
    try {
      if (enabled) {
        observe(
            () -> {
              if (!SESSIONS.derived(session, parent)) {
                count(Counter.LOST_ATTRIBUTION);
              }
            });
      }
    } finally {
      LOCK.unlock();
    }
  }

  /** Stops new callbacks without acquiring collector locks or delaying the core CBOM writer. */
  public static void stopAdmission() {
    enabled = false;
  }

  /** Stops admission and finalizes unfinished attempts without waiting for application threads. */
  public static void close() {
    LOCK.lock();
    try {
      enabled = false;
      for (Attempt attempt : List.copyOf(PENDING.values())) {
        finish(attempt, Outcome.UNKNOWN, "shutdown-before-completion");
      }
      CONNECTIONS.clear();
      HANDSHAKES.clear();
      SESSIONS.clear();
      owners.disable();
      OWNED.clear();
    } finally {
      LOCK.unlock();
    }
  }

  /** Immutable bounded snapshot; no polling, sleeping or application callbacks. */
  public static List<Handshake> snapshot() {
    LOCK.lock();
    try {
      expire(clock.getAsLong());
      return List.copyOf(FINISHED.values());
    } finally {
      LOCK.unlock();
    }
  }

  private static void expire(long now) {
    for (Attempt attempt : List.copyOf(PENDING.values())) {
      if (attempt.handshake.get() == null || attempt.transport.get() == null) {
        finish(attempt, Outcome.UNKNOWN, "owner-collected-before-completion");
      } else if (now - attempt.started >= TIMEOUT) {
        finish(attempt, Outcome.UNKNOWN, "handshake-observation-timeout");
      }
    }
  }

  private static void finish(Attempt attempt, Outcome outcome, String diagnostic) {
    if (attempt.terminal) {
      return;
    }
    if (diagnostic != null) {
      attempt.diagnostic(diagnostic);
    }
    Handshake result =
        new Handshake(
            attempt.ref.connectionId(),
            attempt.ref.attempt(),
            attempt.kind,
            attempt.role,
            outcome,
            new Negotiation(
                attempt.protocol,
                attempt.suite,
                attempt.group,
                attempt.groupName,
                attempt.localSignature,
                attempt.peerSignature),
            new Resumption(
                attempt.resumed, attempt.group >= 0 ? Flag.YES : Flag.UNKNOWN, attempt.origin),
            List.copyOf(attempt.assets),
            "sunjsse-structural-v1",
            List.copyOf(attempt.diagnostics),
            attempt.initiation);
    attempt.terminal = true;
    FINISHED.put(attempt.ref, result);
    PENDING.remove(attempt.ref);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static void observe(Observation observation) {
    try {
      observation.run();
    } catch (Throwable failure) {
      count(Counter.RECORDING_FAILURES);
    }
  }

  static long recordingFailures() {
    LOCK.lock();
    try {
      return recordingFailures;
    } finally {
      LOCK.unlock();
    }
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static void count(Counter counter) {
    if (counter == Counter.RECORDING_FAILURES && recordingFailures < Long.MAX_VALUE) {
      recordingFailures++;
    }
    if (counters != null) {
      try {
        counters.accept(counter);
      } catch (Throwable ignored) {
        // Preserve a local degradation signal even when the external counter sink fails.
        if (recordingFailures < Long.MAX_VALUE) {
          recordingFailures++;
        }
      }
    }
  }

  @FunctionalInterface
  private interface Observation {
    void run() throws ReflectiveOperationException;
  }

  private static final class Connection {
    private final String id;
    private long sequence;

    Connection(String id) {
      this.id = id;
    }
  }

  private static final class Attempt {
    private final int binding = OWNED.size() + 1;
    private final AttemptRef ref;
    private final WeakReference<Object> handshake;
    private final WeakReference<Object> transport;
    private final Transport kind;
    private final PeerRole role;
    private final ScopeContext initiation;
    private final long started = clock.getAsLong();
    private final Set<String> assets = new LinkedHashSet<>();
    private final List<String> diagnostics = new ArrayList<>();
    private boolean terminal;
    private String protocol = "";
    private String suite = "";
    private int group = -1;
    private String groupName = "";
    private String localSignature = "";
    private String peerSignature = "";
    private Flag resumed = Flag.UNKNOWN;
    private AttemptRef origin;

    Attempt(
        AttemptRef ref,
        Object handshake,
        Object transport,
        Transport kind,
        PeerRole role,
        ScopeContext initiation) {
      this.ref = ref;
      this.handshake = new WeakReference<>(handshake);
      this.transport = new WeakReference<>(transport);
      this.kind = kind;
      this.role = role;
      this.initiation = initiation;
    }

    void diagnostic(String diagnostic) {
      if (!diagnostics.contains(diagnostic) && diagnostics.size() < 32) {
        diagnostics.add(diagnostic);
      }
    }
  }
}
