package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.DocumentFiles;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.SourceCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import net.bytebuddy.description.method.MethodDescription;

/** Optional context facade. Every entry point is isolated from canonical inventory health. */
final class AgentEnrichment {
  private static final System.Logger LOG = System.getLogger(AgentEnrichment.class.getName());
  private static final StackWalker WALKER =
      StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
  private static final ContextModel.Binding UNKNOWN_FRAME =
      new ContextModel.Binding("unknown", "unknown", "", "", ContextModel.Origin.UNKNOWN);
  private static volatile Run current;

  private AgentEnrichment() {}

  static void configure(AgentConfig config) {
    current = null;
    FlowBridge.configure(config.maxScopeDepth);
    if (config.enrichment) {
      current = new Run(config);
    }
  }

  static boolean enabled() {
    Run run = current;
    return run != null && run.accepting;
  }

  static String runId() {
    Run run = current;
    return run == null ? null : run.id;
  }

  static void installed() {
    Run run = current;
    if (run != null) {
      run.installed = true;
      run.scopes.enable();
    }
  }

  static int binding(MethodDescription method, ClassLoader loader, ProtectionDomain domain) {
    Run run = Objects.requireNonNull(current, "Enrichment is not configured");
    var identity = run.identities.resolve(domain, loader);
    if (identity.status() != EvidenceDocument.IdentityStatus.RESOLVED) {
      run.health.increment(EvidenceDocument.Counter.LOST_ATTRIBUTION);
      return 0;
    }
    return run.catalog.register(
        identity,
        FlowDeclarations.read(
            method,
            identity.applicationId(),
            identity.sourceArtifact(),
            run.identities.origin(domain, loader)));
  }

  static void diagnostic(EvidenceDocument.Counter counter) {
    Run run = current;
    if (run != null) {
      run.health.increment(counter);
    }
  }

  static Map<EvidenceDocument.Counter, Long> health() {
    Run run = current;
    return run == null ? EvidenceDocument.zeroHealth() : run.health.snapshot();
  }

  static EvidenceDocument.ScopeContext context() {
    Run run = current;
    if (run == null) {
      return FlowCatalog.unassigned(EvidenceDocument.Association.NO_SCOPE);
    }
    var context = run.catalog.context(run.scopes.current());
    if (context.association() == EvidenceDocument.Association.NO_SCOPE) {
      CapturedFrame frame = WALKER.walk(frames -> projectFrame(run, frames));
      return new EvidenceDocument.ScopeContext(
          frame.identityId(), List.of(), EvidenceDocument.Association.NO_SCOPE);
    }
    return context;
  }

  // A failing attribution must not turn a completed core inventory update into a core failure.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  static void record(CryptoAsset asset, Inventory.RecordOutcome outcome) {
    Run run = current;
    if (run == null || !run.accepting || outcome.disposition() == Inventory.Disposition.DROPPED) {
      return;
    }
    try {
      var context = run.catalog.context(run.scopes.current());
      if (context.association() == EvidenceDocument.Association.SCOPE_LOST) {
        run.health.increment(EvidenceDocument.Counter.LOST_ATTRIBUTION);
      }
      CapturedFrame frame = WALKER.walk(frames -> projectFrame(run, frames));
      if (context.association() == EvidenceDocument.Association.NO_SCOPE) {
        context =
            new EvidenceDocument.ScopeContext(
                frame.identityId(), List.of(), EvidenceDocument.Association.NO_SCOPE);
      }
      run.evidence.record(asset.ref(), outcome, context, frame.binding(), 1);
    } catch (Throwable failure) {
      run.health.increment(EvidenceDocument.Counter.RECORDING_FAILURES);
      run.health.increment(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED);
    }
  }

  static void relatedAsset(CryptoAsset asset, Inventory.RecordOutcome outcome) {
    Run run = current;
    if (run != null
        && run.accepting
        && run.config.tlsCorrelation
        && outcome.disposition() != Inventory.Disposition.DROPPED) {
      TlsCollector.asset(asset.ref());
    }
  }

  static void stop(int stillRunning) {
    Run run = current;
    if (run != null) {
      run.accepting = false;
      run.scopes.disable();
      if (run.config.tlsCorrelation) {
        TlsCollector.stopAdmission();
      }
      if (stillRunning > 0) {
        run.health.increment(EvidenceDocument.Counter.DRAIN_TIMEOUT);
      }
    }
  }

  /**
   * Bounds this hook's waiting separately from core drain/write. A daemon blocked in filesystem I/O
   * can finish during other shutdown hooks; cancellation cannot forcibly bound atomic rename. Any
   * late sidecar still binds to the exact CBOM hash and run nonce.
   */
  static boolean publish(List<Inventory.Entry> written, long budgetMillis) {
    Run run = current;
    if (run == null) {
      return true;
    }
    final List<Inventory.Entry> snapshot = List.copyOf(written);
    final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
    FutureTask<Void> task =
        new FutureTask<>(
            () -> {
              run.publish(snapshot, deadline);
              return null;
            });
    Thread writer = new Thread(task, "crypto-census-evidence-publication");
    writer.setDaemon(true);
    writer.start();
    try {
      task.get(budgetMillis, TimeUnit.MILLISECONDS);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | TimeoutException failed) {
      task.cancel(true);
    }
    task.cancel(true);
    run.health.increment(EvidenceDocument.Counter.OUTPUT_FAILURES);
    LOG.log(System.Logger.Level.WARNING, "Enrichment evidence publication incomplete");
    return false;
  }

  private static CapturedFrame projectFrame(Run run, Stream<StackWalker.StackFrame> frames) {
    CapturedFrame fallback = new CapturedFrame(UNKNOWN_FRAME, FlowIdentities.UNKNOWN);
    for (StackWalker.StackFrame frame : frames.limit(64).toList()) {
      Class<?> declaring = frame.getDeclaringClass();
      ClassLoader loader = declaring.getClassLoader();
      if (loader == null || FlowInstrumentation.ignoredName(declaring.getName())) {
        continue;
      }
      ProtectionDomain domain = declaring.getProtectionDomain();
      var identity = run.identities.resolve(domain, loader);
      var source = run.identities.source(domain, loader);
      if (source.isPresent()
          && (source.get().origin() == ContextModel.Origin.MAIN
              || source.get().origin() == ContextModel.Origin.TEST)) {
        var binding =
            new ContextModel.Binding(
                frame.getClassName(),
                frame.getMethodName(),
                frame.getDescriptor(),
                source.get().artifactId(),
                source.get().origin());
        return new CapturedFrame(binding, identity.id());
      }
      if (FlowIdentities.UNKNOWN.equals(fallback.identityId())) {
        fallback = new CapturedFrame(UNKNOWN_FRAME, identity.id());
      }
    }
    return fallback;
  }

  private record CapturedFrame(ContextModel.Binding binding, String identityId) {}

  private static Path publicationTarget(Path evidence, Path cbom) throws IOException {
    Path absolute = evidence.toAbsolutePath();
    Path fileName = absolute.getFileName();
    if (fileName == null) {
      throw new IOException("Evidence target requires a filename");
    }
    Path parent = Objects.requireNonNull(absolute.getParent(), "Evidence parent");
    var missing = new ArrayDeque<Path>();
    while (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
      missing.push(Objects.requireNonNull(parent.getFileName(), "Missing parent segment"));
      Path next = parent.getParent();
      if (next == null) {
        throw new IOException("No accessible evidence parent");
      }
      parent = next;
    }
    Path resolved = parent.toRealPath();
    while (!missing.isEmpty()) {
      resolved = resolved.resolve(missing.pop());
    }
    Path target = resolved.resolve(fileName).normalize();
    if (target.equals(cbom.toRealPath())) {
      throw new IOException("Evidence target aliases the core CBOM");
    }
    return target;
  }

  private static void checkDeadline(long deadline) throws IOException {
    if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) {
      throw new IOException("Evidence publication budget exhausted");
    }
  }

  private static final class Run {
    private final String id = UUID.randomUUID().toString();
    private final FlowScopes scopes = FlowBridge.scopes();
    private final AgentConfig config;
    private final FlowHealth health = new FlowHealth();
    private final FlowIdentities identities;
    private final FlowCatalog catalog =
        new FlowCatalog(ContextModel.MAX_FLOWS, EvidenceDocument.MAX_BINDINGS, health::increment);
    private final FlowEvidence evidence =
        new FlowEvidence(EvidenceDocument.MAX_OBSERVATIONS, health::increment);
    private volatile boolean accepting = true;
    private volatile boolean installed;

    Run(AgentConfig config) {
      this.config = config;
      SourceCatalog sources = new SourceCatalog(1, List.of());
      if (config.sourcesFile != null) {
        try {
          sources = SourceCatalog.parse(DocumentFiles.read(config.sourcesFile));
        } catch (IOException | IllegalArgumentException failed) {
          health.increment(EvidenceDocument.Counter.RECORDING_FAILURES);
          LOG.log(System.Logger.Level.WARNING, "Enrichment source catalog unavailable");
        }
      }
      identities =
          new FlowIdentities(
              sources,
              config.sourcesFile == null ? config.applicationId : null,
              health::increment,
              ContextModel.MAX_FLOWS);
    }

    void publish(List<Inventory.Entry> written, long deadline) throws IOException {
      checkDeadline(deadline);
      health.add(EvidenceDocument.Counter.RECORDING_FAILURES, Recorder.enrichmentFailures());
      if (Recorder.enrichmentFailures() > 0) {
        health.increment(EvidenceDocument.Counter.CONSERVATION_UNVERIFIED);
      }
      health.add(EvidenceDocument.Counter.SCOPE_INTEGRITY, scopes.integrityFailures());
      health.add(EvidenceDocument.Counter.SCOPE_INTEGRITY, scopes.entryFailures());
      // Depth overflow marks only the affected path lost; independent scopes remain attributable.
      health.add(EvidenceDocument.Counter.LOST_ATTRIBUTION, scopes.overflowed());
      health.add(EvidenceDocument.Counter.SCOPE_RESTORE_FAILURES, scopes.restoreFailures());
      var observations = evidence.snapshot(written, scopes.unverified());
      final byte[] cbom = Cbom.readBytes(config.output);
      List<EvidenceDocument.Handshake> handshakes = List.of();
      List<EvidenceDocument.Capability> tlsCapabilities = List.of();
      if (config.tlsCorrelation) {
        TlsCollector.close();
        handshakes = TlsCollector.snapshot();
        tlsCapabilities = TlsInstrumentation.capabilities();
      }
      var counts = health.snapshot();
      var annotationStatus =
          !installed
              ? EvidenceDocument.CapabilityStatus.UNAVAILABLE
              : counts.get(EvidenceDocument.Counter.TRANSFORM_ERRORS) > 0
                      || counts.get(EvidenceDocument.Counter.SCOPE_INTEGRITY) > 0
                  ? EvidenceDocument.CapabilityStatus.PARTIAL
                  : EvidenceDocument.CapabilityStatus.SUPPORTED;
      List<EvidenceDocument.Capability> capabilities =
          new ArrayList<>(
              List.of(
                  new EvidenceDocument.Capability(
                      "annotation-scopes",
                      annotationStatus,
                      "Supported synchronous instrumentation path; explicit declaring identity"
                          + " required; unloaded and unobserved code remains unknown"),
                  new EvidenceDocument.Capability(
                      "scope-propagation",
                      EvidenceDocument.CapabilityStatus.PARTIAL,
                      "synchronous-only; off-thread work may be missing; enclosing execution does"
                          + " not prove protected-data causality"),
                  new EvidenceDocument.Capability(
                      "scope-resource-failure",
                      EvidenceDocument.CapabilityStatus.PARTIAL,
                      "JVM resource exhaustion can prevent cleanup and diagnostics; absence of a"
                          + " counter does not prove intact attribution")));
      capabilities.add(
          new EvidenceDocument.Capability(
              "project-origin",
              observations.isEmpty()
                      || observations.stream()
                          .anyMatch(
                              value -> value.projectFrame().origin() == ContextModel.Origin.UNKNOWN)
                  ? EvidenceDocument.CapabilityStatus.PARTIAL
                  : EvidenceDocument.CapabilityStatus.SUPPORTED,
              "Only configured artifacts establish main/test provenance; unmapped project origins"
                  + " remain unknown"));
      var tlsStatus =
          tlsCapabilities.stream()
              .filter(value -> "tls-sunjsse-1.3-correlation".equals(value.feature()))
              .map(EvidenceDocument.Capability::status)
              .findFirst()
              .orElse(EvidenceDocument.CapabilityStatus.UNAVAILABLE);
      capabilities.add(
          new EvidenceDocument.Capability(
              "tls-correlation",
              tlsStatus,
              config.tlsCorrelation
                  ? "SunJSSE adapter support is qualified by protocol and lineage subcapabilities"
                  : "TLS correlation not enabled"));
      capabilities.addAll(tlsCapabilities);
      var document =
          new EvidenceDocument(
              1,
              id,
              CanonicalJson.hash(cbom),
              catalog.snapshot(),
              observations,
              handshakes,
              identities.snapshot(),
              capabilities,
              counts);
      EvidenceCodec.validatePair(document, cbom);
      // Encoding validates bounded output before the final cancellation/deadline check.
      byte[] bytes = EvidenceCodec.encode(document);
      Path target = publicationTarget(config.evidenceOutput, config.output);
      checkDeadline(deadline);
      DocumentFiles.write(target, bytes);
    }
  }
}
