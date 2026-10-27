package com.developmentsprint.cryptocensus.it;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Executes real synchronous and asynchronous boundaries without context propagation wrappers. */
public final class ScopeBoundaryWorkload {
  private ScopeBoundaryWorkload() {}

  public static void main(String[] args) throws Exception {
    ChildJvmCoverage.dumpAfterAgentShutdown();
    var worker = Executors.newSingleThreadExecutor();
    try {
      worker.submit(ScopeBoundaryWorkload::workerScoped).get();
      worker.submit(ScopeBoundaryWorkload::workerAfter).get();
      offThread(worker);
      futureInline();
      callerRuns();
      var pool = new ForkJoinPool(1);
      try {
        pool.submit(ScopeBoundaryWorkload::helping).get();
      } finally {
        pool.shutdownNow();
      }
      reactiveInline();
      reactiveAsync(worker);
      if (Runtime.version().feature() >= 21) {
        virtualParent();
      }
    } finally {
      worker.shutdownNow();
    }
  }

  @CryptoFlow("worker")
  private static void workerScoped() {
    digest();
  }

  private static void workerAfter() {
    digest();
  }

  private static void offThreadCrypto() {
    digest();
  }

  @CryptoFlow("submit")
  private static void offThread(java.util.concurrent.ExecutorService worker) throws Exception {
    worker.submit(ScopeBoundaryWorkload::offThreadCrypto).get();
  }

  @CryptoFlow("future")
  private static void futureInline() {
    CompletableFuture.completedFuture("done").thenRun(ScopeBoundaryWorkload::inlineCrypto).join();
  }

  private static void inlineCrypto() {
    digest();
  }

  @SuppressWarnings("ReferenceEquality") // Prove execution on the exact submitting thread.
  @CryptoFlow("caller")
  private static void callerRuns() throws Exception {
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool =
        new ThreadPoolExecutor(
            1,
            1,
            1,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new ThreadPoolExecutor.CallerRunsPolicy());
    try {
      pool.execute(
          () -> {
            started.countDown();
            await(release);
          });
      if (!started.await(30, TimeUnit.SECONDS)) {
        throw new AssertionError("Worker did not start");
      }
      Thread owner = Thread.currentThread();
      pool.execute(
          () -> {
            if (Thread.currentThread() != owner) {
              throw new AssertionError("Not caller-runs");
            }
            callerCrypto();
          });
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private static void callerCrypto() {
    digest();
  }

  @SuppressWarnings("ReferenceEquality") // Prove execution on the exact joining worker.
  @CryptoFlow("helping")
  private static void helping() {
    Thread owner = Thread.currentThread();
    ForkJoinTask<?> task =
        ForkJoinTask.adapt(
            () -> {
              if (Thread.currentThread() != owner) {
                throw new AssertionError("Task was not helped inline");
              }
              helpingCrypto();
            });
    task.fork();
    task.join();
  }

  private static void helpingCrypto() {
    digest();
  }

  @CryptoFlow("reactive")
  private static void reactiveInline() {
    try (var publisher = new SubmissionPublisher<String>(Runnable::run, 1)) {
      publisher.subscribe(new Subscriber(ScopeBoundaryWorkload::reactiveCrypto));
      publisher.submit("value");
    }
  }

  private static void reactiveCrypto() {
    digest();
  }

  @CryptoFlow("reactive-submit")
  private static void reactiveAsync(java.util.concurrent.ExecutorService worker) throws Exception {
    var completed = new CompletableFuture<Void>();
    try (var publisher = new SubmissionPublisher<String>(worker, 1)) {
      publisher.subscribe(
          new Subscriber(
              () -> {
                reactiveAsyncCrypto();
                completed.complete(null);
              }));
      publisher.submit("value");
      completed.get(30, TimeUnit.SECONDS);
    }
  }

  private static void reactiveAsyncCrypto() {
    digest();
  }

  @CryptoFlow("platform-parent")
  private static void virtualParent() throws Exception {
    Thread child =
        (Thread)
            Thread.class
                .getMethod("startVirtualThread", Runnable.class)
                .invoke(
                    null,
                    (Runnable)
                        () -> {
                          virtualUnscoped();
                          virtualScoped();
                          virtualAfter();
                        });
    child.join();
  }

  private static void virtualUnscoped() {
    digest();
  }

  @CryptoFlow("virtual")
  private static void virtualScoped() {
    digest();
  }

  private static void virtualAfter() {
    digest();
  }

  private static void digest() {
    try {
      MessageDigest.getInstance("SHA3-256").digest(new byte[] {1});
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private record Subscriber(Runnable action) implements Flow.Subscriber<String> {
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      subscription.request(1);
    }

    @Override
    public void onNext(String value) {
      action.run();
    }

    @Override
    public void onError(Throwable failure) {
      throw new AssertionError(failure);
    }

    @Override
    public void onComplete() {}
  }
}
