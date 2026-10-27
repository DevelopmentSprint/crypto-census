package com.developmentsprint.cryptocensus.it;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Holds a real collector lock across shutdown, without altering the publisher or its deadline. */
public final class DelayedPublicationWorkload {
  private DelayedPublicationWorkload() {}

  public static void main(String[] args) throws Exception {
    ChildJvmCoverage.dumpAfterAgentShutdown();
    RetransformWorkload.raw();
    final Path output = Path.of(args[0]);
    final Path sidecar = Path.of(args[1]);
    Path proof = Path.of(args[2]);
    if ("halt".equals(args[3])) {
      Files.writeString(proof, "crypto-completed-before-halt");
      Runtime.getRuntime().halt(0);
    }
    Class<?> collector =
        Class.forName("com.developmentsprint.cryptocensus.agent.TlsCollector", true, null);
    Field field = collector.getDeclaredField("LOCK");
    field.setAccessible(true);
    ReentrantLock lock = (ReentrantLock) field.get(null);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread owner = new Thread(() -> hold(lock, held, release), "fixture-collector-lock-owner");
    owner.setDaemon(true);
    owner.start();
    if (!held.await(5, TimeUnit.SECONDS)) {
      throw new AssertionError("Fixture did not acquire the real collector lock");
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    verifyShutdown(lock, release, output, sidecar, proof);
                  } catch (Exception failure) {
                    throw new AssertionError("Delayed publication verification failed", failure);
                  } finally {
                    release.countDown();
                  }
                },
                "fixture-publication-verifier"));
  }

  private static void hold(ReentrantLock lock, CountDownLatch held, CountDownLatch release) {
    lock.lock();
    try {
      held.countDown();
      if (!release.await(20, TimeUnit.SECONDS)) {
        throw new AssertionError("Shutdown verifier never released the fixture lock");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError(interrupted);
    } finally {
      lock.unlock();
    }
  }

  private static void verifyShutdown(
      ReentrantLock lock, CountDownLatch release, Path output, Path sidecar, Path proof)
      throws Exception {
    Thread publisher = awaitThread("crypto-census-evidence-publication", lock);
    Thread shutdown = awaitThread("crypto-census-shutdown", null);
    final byte[] cbomBeforeTimeout = Files.readAllBytes(output);
    shutdown.join(5000);
    if (shutdown.isAlive() || !publisher.isAlive() || !lock.hasQueuedThread(publisher)) {
      throw new AssertionError(
          "Core shutdown did not finish while publication remained causally blocked");
    }
    Class<?> facade =
        Class.forName("com.developmentsprint.cryptocensus.agent.AgentEnrichment", true, null);
    var health = facade.getDeclaredMethod("health");
    health.setAccessible(true);
    Map<?, ?> counts = (Map<?, ?>) health.invoke(null);
    long failures =
        counts.entrySet().stream()
            .filter(entry -> "OUTPUT_FAILURES".equals(entry.getKey().toString()))
            .mapToLong(entry -> ((Number) entry.getValue()).longValue())
            .findFirst()
            .orElseThrow();
    if (failures != 1 || Files.exists(sidecar)) {
      throw new AssertionError(
          "Blocked writer must report exactly one timeout and publish no sidecar");
    }
    release.countDown();
    publisher.join(5000);
    if (publisher.isAlive()
        || Files.exists(sidecar)
        || !Arrays.equals(cbomBeforeTimeout, Files.readAllBytes(output))) {
      throw new AssertionError("Late cancelled publisher changed an output after its deadline");
    }
    Files.writeString(
        proof, "queued=true;core-finished-while-blocked=true;output-failures=1;late-output=false");
  }

  private static Thread awaitThread(String name, ReentrantLock lock) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      for (Thread thread : Thread.getAllStackTraces().keySet()) {
        if (name.equals(thread.getName()) && (lock == null || lock.hasQueuedThread(thread))) {
          return thread;
        }
      }
      Thread.sleep(1);
    }
    throw new AssertionError("Required live thread not observed: " + name);
  }
}
