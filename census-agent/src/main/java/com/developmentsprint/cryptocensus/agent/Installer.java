package com.developmentsprint.cryptocensus.agent;

import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Report;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.security.KeyPairGenerator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

/** Installs the instrumentation. Loaded from the bootstrap class path by {@link CensusAgent}. */
public final class Installer {

  private static final String PREFIX = "[crypto-census] ";
  private static final AtomicBoolean INSTALLED = new AtomicBoolean();

  /**
   * Bound for the per-type bookkeeping below; provider subclasses could otherwise grow it forever.
   */
  private static final int MAX_TRACKED_TYPES = 256;

  /**
   * Types we always target by name: errors count even before (or instead of) the transformer
   * callback.
   */
  private static final Set<String> TARGETS =
      Set.of(
          "javax.crypto.Cipher",
          "java.security.Signature",
          "javax.crypto.Mac",
          "javax.crypto.KeyAgreement",
          "javax.crypto.KEM",
          "javax.crypto.KeyGenerator",
          "java.security.KeyPairGenerator",
          "java.security.MessageDigest",
          "sun.security.ssl.SSLSessionImpl");

  /**
   * Types our matchers selected for transformation; an error on any of them means incomplete
   * collection.
   */
  private static final Set<String> SELECTED = ConcurrentHashMap.newKeySet();

  private static final Set<String> INSTRUMENTED = new ConcurrentSkipListSet<>();
  private static final LongAdder UNTRACKED_TYPES = new LongAdder();
  private static final LongAdder TRANSFORM_ERRORS = new LongAdder();

  private Installer() {}

  /**
   * Installs the instrumentation once per JVM and registers the shutdown hook that writes the CBOM.
   *
   * @param args agent options
   * @param inst the JVM's instrumentation handle
   * @param agentJar the agent jar, used to locate advice class files
   * @throws IOException if the agent jar cannot be read
   */
  // Catching Throwable is the agent's contract: observation must never break the host application.
  // The class-file locator stays open for the JVM's lifetime: advice is resolved lazily on class
  // load.
  @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CloseResource"})
  public static void install(String args, Instrumentation inst, File agentJar) throws IOException {
    if (!INSTALLED.compareAndSet(false, true)) {
      System.err.println(
          PREFIX + "already installed in this JVM; ignoring repeated -javaagent / attach");
      return;
    }
    AgentConfig config = AgentConfig.parse(args);
    config.warnings.forEach(w -> System.err.println(PREFIX + w));
    Recorder.configure(config.maxAssets, config.maxSites);

    // We never inject classes, so keep Byte Buddy off sun.misc.Unsafe (JDK 24+ warns about it).
    // The shade plugin relocates this literal along with Byte Buddy's own constant.
    System.setProperty("net.bytebuddy.safe", "true");
    ClassFileLocator locator = ClassFileLocator.ForJarFile.of(agentJar);

    AgentBuilder.Listener listener = new HealthListener();
    if (config.debug) {
      listener =
          new AgentBuilder.Listener.Compound(
              listener, AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly());
    }

    // Captured as soon as Byte Buddy registers the transformer, so a failure later during
    // retransformation can still undo it (installOn() never returns in that case).
    AtomicReference<ResettableClassFileTransformer> registered = new AtomicReference<>();
    try {
      new AgentBuilder.Default()
          .disableClassFormatChanges()
          .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
          .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
          .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
          // Describe types by parsing class files, not reflection. Reflecting on a loaded
          // KeyPairGenerator loads its nested Delegate inside the transformer, where Byte
          // Buddy's
          // circularity guard skips it - and it would never be instrumented.
          .with(AgentBuilder.DescriptionStrategy.Default.POOL_FIRST)
          .with(listener)
          .with(
              new AgentBuilder.InstallationListener.Adapter() {
                @Override
                public void onBeforeInstall(
                    Instrumentation instrumentation,
                    ResettableClassFileTransformer classFileTransformer) {
                  registered.set(classFileTransformer);
                }
              })
          // Replaces the default ignore matcher, which would skip bootstrap (JDK) classes.
          .ignore(nameStartsWith("com.developmentsprint.cryptocensus.agent.").or(isSynthetic()))
          .type(named("javax.crypto.Cipher"))
          .transform(advice(locator, Advices.CipherInit.class, named("init").and(isPublic())))
          .assureReadEdgeTo(inst, Recorder.class)
          .type(named("java.security.Signature"))
          .transform(
              advice(
                  locator,
                  Advices.KeyArg0.class,
                  named("initSign").or(named("initVerify")).and(isPublic())))
          .assureReadEdgeTo(inst, Recorder.class)
          .type(named("javax.crypto.Mac"))
          .transform(advice(locator, Advices.KeyArg0.class, named("init").and(isPublic())))
          .assureReadEdgeTo(inst, Recorder.class)
          .type(named("javax.crypto.KeyAgreement"))
          .transform(advice(locator, Advices.KeyArg0.class, named("init").and(isPublic())))
          .assureReadEdgeTo(inst, Recorder.class)
          .type(named("javax.crypto.KEM"))
          .transform(
              advice(
                  locator,
                  Advices.KeyArg0.class,
                  named("newEncapsulator").or(named("newDecapsulator")).and(isPublic())))
          .assureReadEdgeTo(inst, Recorder.class)
          .type(named("javax.crypto.KeyGenerator"))
          .transform(
              advice(locator, Advices.ReturnsKey.class, named("generateKey").and(isPublic())))
          .assureReadEdgeTo(inst, Recorder.class)

          // KeyPairGenerator.getInstance returns provider subclasses that override
          // generateKeyPair
          .type(isSubTypeOf(KeyPairGenerator.class))
          .transform(
              advice(
                  locator,
                  Advices.ReturnsKey.class,
                  named("generateKeyPair")
                      .or(named("genKeyPair"))
                      .and(isPublic())
                      .and(not(isAbstract()))))
          .assureReadEdgeTo(inst, Recorder.class)

          // Negotiated TLS sessions. SSLSessionImpl is JSSE-internal (stable since the JDK 11
          // rewrite) but we only read it through the public SSLSession interface.
          .type(config.tls ? named("sun.security.ssl.SSLSessionImpl") : none())
          .transform(advice(locator, Advices.Constructed.class, isConstructor()))
          .assureReadEdgeTo(inst, Recorder.class)
          .type(named("java.security.MessageDigest"))
          .transform(
              advice(
                  locator,
                  Advices.StaticFactory.class,
                  named("getInstance").and(isStatic()).and(isPublic())))
          .assureReadEdgeTo(inst, Recorder.class)
          .installOn(inst);
    } catch (Throwable t) {
      // Leave the JVM exactly as we found it rather than half-instrumented.
      ResettableClassFileTransformer partial = registered.get();
      if (partial != null) {
        partial.reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
      }
      System.err.println(PREFIX + "instrumentation failed; agent disabled: " + t);
      return;
    }

    Recorder.enable();
    Runtime.getRuntime()
        .addShutdownHook(new Thread(() -> shutdown(config), "crypto-census-shutdown"));
  }

  private static AgentBuilder.Transformer advice(
      ClassFileLocator locator,
      Class<?> adviceClass,
      ElementMatcher<? super MethodDescription> methods) {
    Advice advice = Advice.to(adviceClass, locator);
    return (builder, type, classLoader, module, protectionDomain) -> {
      // Only invoked for types a matcher selected, so an error reported for this name afterwards
      // is a failure to instrument something we meant to observe, whatever the class is called.
      track(SELECTED, type.getName());
      return builder.visit(advice.on(methods));
    };
  }

  private static void track(Set<String> set, String name) {
    if (set.size() < MAX_TRACKED_TYPES || set.contains(name)) {
      set.add(name);
    } else {
      UNTRACKED_TYPES.increment();
    }
  }

  /** Tracks which selected types were instrumented and whether any of them failed. */
  private static final class HealthListener extends AgentBuilder.Listener.Adapter {
    @Override
    public void onTransformation(
        TypeDescription type,
        ClassLoader loader,
        JavaModule module,
        boolean loaded,
        DynamicType dynamicType) {
      track(INSTRUMENTED, type.getName());
    }

    @Override
    public void onError(
        String typeName,
        ClassLoader loader,
        JavaModule module,
        boolean loaded,
        Throwable throwable) {
      // Errors while merely *matching* unrelated classes (unresolvable hierarchies in app servers)
      // are not ours to report; failures on types we selected are.
      // Once tracking is saturated we can no longer tell, so fail closed and count every error.
      if (TARGETS.contains(typeName) || SELECTED.contains(typeName) || UNTRACKED_TYPES.sum() > 0) {
        TRANSFORM_ERRORS.increment();
      }
    }
  }

  // Catching Throwable is the agent's contract: observation must never break the host application.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static void shutdown(AgentConfig config) {
    Recorder.disable();
    // let admitted observations (including those still inside the provider) finish, briefly
    long deadline = System.nanoTime() + 500_000_000L;
    while (Recorder.inFlight() > 0 && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    int stillRunning = Recorder.inFlight();
    try {
      Inventory inventory = Recorder.inventory();
      // one consistent snapshot, taken before anything else, feeds both the CBOM and the summary
      final List<Inventory.Entry> snapshot = inventory.entries();
      final long dropped = inventory.droppedObservations();

      Map<String, String> props = new LinkedHashMap<>();
      props.put("cryptocensus:java.version", System.getProperty("java.version"));
      props.put("cryptocensus:java.vendor", System.getProperty("java.vendor"));
      props.put(
          "cryptocensus:os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
      props.put("cryptocensus:pid", String.valueOf(ProcessHandle.current().pid()));
      props.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
      props.put("cryptocensus:collection", "agent; ends when the JVM shutdown hook runs");
      props.put("cryptocensus:instrumented", String.join(",", INSTRUMENTED));
      props.put("cryptocensus:untrackedTypes", String.valueOf(UNTRACKED_TYPES.sum()));
      props.put("cryptocensus:transformErrors", String.valueOf(TRANSFORM_ERRORS.sum()));
      props.put("cryptocensus:recordingFailures", String.valueOf(Recorder.failures()));
      props.put("cryptocensus:drainTimedOut", String.valueOf(stillRunning));
      Cbom.write(snapshot, dropped, new Cbom.Metadata(config.app, props), config.output);
      if (config.summary) {
        System.err.print(Report.text(snapshot, dropped));
        System.err.println(PREFIX + "CBOM written to " + config.output.toAbsolutePath());
      }
    } catch (Throwable t) {
      System.err.println(PREFIX + "failed to write CBOM: " + t);
    }
  }
}
