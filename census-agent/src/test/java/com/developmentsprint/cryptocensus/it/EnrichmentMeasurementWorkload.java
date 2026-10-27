package com.developmentsprint.cryptocensus.it;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Optional, synthetic measurement process. Run by scripts/measure-enrichment.py, without JaCoCo.
 */
public final class EnrichmentMeasurementWorkload {
  private static final String AGENT = "com.developmentsprint.cryptocensus.agent.";
  private static volatile int sink;

  private EnrichmentMeasurementWorkload() {}

  public static void main(String[] args) throws Exception {
    System.out.println("READY");
    String workload = args[0];
    int iterations = Integer.parseInt(args[1]);
    int rounds = Integer.parseInt(args[2]);
    Path fixture = Path.of(args[3]);
    if (workload.equals("stress")) {
      stress(fixture, iterations);
      return;
    }
    Operation operation = operation(workload, fixture);
    measure(operation, iterations, -1);
    for (int round = 0; round < rounds; round++) {
      measure(operation, iterations, round);
    }
    System.out.println("CHECKSUM " + sink);
  }

  private static Operation operation(String name, Path fixture) throws Exception {
    return switch (name) {
      case "plain-hot" -> EnrichmentMeasurementWorkload::plainHot;
      case "scoped-hot" -> EnrichmentMeasurementWorkload::scopedHot;
      case "plain-digest" -> value -> digest(value);
      case "scoped-digest" -> value -> scopedDigest(value);
      case "tls" ->
          value -> {
            tls(fixture);
            return value + 1;
          };
      case "stackwalk" -> reflected("Callsites", "current");
      case "prefilter-plain", "prefilter-annotated" -> prefilter(fixture);
      case "matching-plain", "matching-annotated" -> matching(fixture);
      case "load-plain", "load-annotated" -> value -> load(fixture, value, false);
      default -> throw new IllegalArgumentException("Unknown workload: " + name);
    };
  }

  private static void measure(Operation operation, int iterations, int round) throws Exception {
    var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    boolean allocation = bean.isThreadAllocatedMemorySupported();
    if (allocation && !bean.isThreadAllocatedMemoryEnabled()) {
      bean.setThreadAllocatedMemoryEnabled(true);
    }
    long beforeBytes = allocation ? bean.getCurrentThreadAllocatedBytes() : -1;
    long start = System.nanoTime();
    int value = sink;
    for (int index = 0; index < iterations; index++) {
      value = operation.run(value);
    }
    long elapsed = System.nanoTime() - start;
    long bytes = allocation ? bean.getCurrentThreadAllocatedBytes() - beforeBytes : -1;
    sink = value;
    if (round >= 0) {
      System.out.printf("SAMPLE %d %d %d %d%n", round, iterations, elapsed, bytes);
    }
  }

  private static int plainHot(int value) {
    return Integer.rotateLeft(value + 0x1f123bb5, 3) ^ 0x752a;
  }

  @CryptoFlow("measurement.hot")
  private static int scopedHot(int value) {
    return Integer.rotateLeft(value + 0x1f123bb5, 3) ^ 0x752a;
  }

  private static int digest(int value) throws Exception {
    return value + MessageDigest.getInstance("SHA-256").digest(new byte[32])[0];
  }

  @CryptoFlow("measurement.digest")
  private static int scopedDigest(int value) throws Exception {
    return digest(value);
  }

  @CryptoFlow("measurement.tls")
  private static void tls(Path store) throws Exception {
    // Reuse successful socket/resumption/engine traffic; rejection latency is not measured.
    Method context = TlsProbeWorkload.class.getDeclaredMethod("context", Path.class, String.class);
    context.setAccessible(true);
    Object trusted = context.invoke(null, store, "measurement");
    Method sockets =
        TlsProbeWorkload.class.getDeclaredMethod(
            "socketConnections",
            javax.net.ssl.SSLContext.class,
            javax.net.ssl.SSLContext.class,
            int.class,
            boolean.class);
    sockets.setAccessible(true);
    sockets.invoke(null, trusted, trusted, 2, false);
    Method engines =
        TlsProbeWorkload.class.getDeclaredMethod(
            "concurrentEngines", javax.net.ssl.SSLContext.class);
    engines.setAccessible(true);
    engines.invoke(null, trusted);
  }

  private static Operation reflected(String type, String method) throws Exception {
    Method target = Class.forName(AGENT + type, true, null).getDeclaredMethod(method);
    target.setAccessible(true);
    return value -> value + target.invoke(null).hashCode();
  }

  private static Operation prefilter(Path fixture) throws Exception {
    byte[] bytes = Files.readAllBytes(fixture);
    Method scan =
        Class.forName(AGENT + "FlowDescriptors", true, null)
            .getDeclaredMethod("scan", byte[].class);
    scan.setAccessible(true);
    return value -> value + ((java.util.Set<?>) scan.invoke(null, (Object) bytes)).size();
  }

  private static Operation matching(Path fixture) throws Exception {
    // The driver puts this one generated class on the application class path.
    Class<?> type = Class.forName(Files.readString(fixture).trim());
    String shaded = AGENT + "shaded.bytebuddy.description.";
    Class<?> description = Class.forName(shaded + "type.TypeDescription$ForLoadedType", true, null);
    Object loaded = description.getConstructor(Class.class).newInstance(type);
    Object methods = description.getMethod("getDeclaredMethods").invoke(loaded);
    List<Object> inputs = new ArrayList<>();
    for (Object method : (Iterable<?>) methods) {
      inputs.add(method);
    }
    Method declared =
        Class.forName(AGENT + "FlowDeclarations", true, null)
            .getDeclaredMethod(
                "declared", Class.forName(shaded + "method.MethodDescription", true, null));
    declared.setAccessible(true);
    return value -> {
      int matches = 0;
      for (Object method : inputs) {
        if (Boolean.TRUE.equals(declared.invoke(null, method))) {
          matches++;
        }
      }
      return value + matches;
    };
  }

  private static int load(Path fixture, int batch, boolean invoke) throws Exception {
    try (var loader =
        new URLClassLoader(
            "measurement-" + batch,
            new java.net.URL[] {fixture.toUri().toURL()},
            EnrichmentMeasurementWorkload.class.getClassLoader())) {
      int classes = Integer.parseInt(Files.readString(fixture.resolve("count.txt")).trim());
      for (int index = 0; index < classes; index++) {
        Class<?> type = Class.forName("measurement.Generated" + index, true, loader);
        if (invoke) {
          for (Method method : type.getDeclaredMethods()) {
            method.invoke(null);
          }
        }
      }
      return batch + 1;
    }
  }

  private static void stress(Path fixture, int batches) throws Exception {
    memory(0);
    for (int batch = 0; batch < batches; batch++) {
      load(fixture.resolve(Integer.toString(batch)), batch, true);
      recursive(40);
      System.gc();
      Thread.sleep(100);
      memory(batch + 1);
      registry(batch + 1);
    }
  }

  @CryptoFlow("measurement.recursive")
  private static void recursive(int depth) throws Exception {
    if (depth == 0) {
      sink = digest(sink);
    } else {
      recursive(depth - 1);
    }
  }

  private static void memory(int batch) {
    var memory = ManagementFactory.getMemoryMXBean();
    System.out.printf(
        "MEMORY %d %d %d %d%n",
        batch,
        memory.getHeapMemoryUsage().getUsed(),
        memory.getNonHeapMemoryUsage().getUsed(),
        ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());
  }

  private static Object field(Object owner, String name) throws Exception {
    Class<?> type = owner instanceof Class<?> clazz ? clazz : owner.getClass();
    Field field = type.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(owner instanceof Class<?> ? null : owner);
  }

  private static void registry(int batch) throws Exception {
    Object run = field(Class.forName(AGENT + "AgentEnrichment", true, null), "current");
    Object catalog = field(run, "catalog");
    Object evidence = field(run, "evidence");
    Object health = field(run, "health");
    Method snapshot = health.getClass().getDeclaredMethod("snapshot");
    snapshot.setAccessible(true);
    System.out.printf(
        "REGISTRY %d %d %d %s%n",
        batch,
        ((Map<?, ?>) field(catalog, "flows")).size(),
        ((Map<?, ?>) field(evidence, "observations")).size(),
        snapshot.invoke(health));
  }

  @FunctionalInterface
  private interface Operation {
    int run(int value) throws Exception;
  }
}
