package com.developmentsprint.cryptocensus.it;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.annotations.CryptoPurpose;
import com.developmentsprint.cryptocensus.annotations.DataProtection;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Annotated fixture: declarations describe synchronous execution, not protected-data tracing. */
public final class LateAnnotatedWorkload {
  private LateAnnotatedWorkload() {}

  @CryptoFlow(value = "archive", purpose = CryptoPurpose.STORAGE)
  @DataProtection(classification = "restricted", confidentialityYears = 20)
  public static void run(boolean stack) throws Exception {
    inner();
    overloaded(1);
    overloaded("one");
    try {
      exceptional();
    } catch (IllegalStateException expected) {
      LateFlowWorkload.raw();
    }
    var executor = Executors.newSingleThreadExecutor();
    try {
      for (int i = 0; i < 2; i++) {
        executor
            .submit(
                () -> {
                  LateFlowWorkload.raw();
                  return null;
                })
            .get(10, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
    if (stack) {
      try {
        recursive();
      } catch (StackOverflowError expected) {
        // Observe the window before the enclosing scope can execute its exit advice.
        LateFlowWorkload.raw();
      }
    }
  }

  @CryptoFlow(value = "transport", purpose = CryptoPurpose.TRANSPORT)
  @DataProtection(confidentialityYears = 1)
  public static void inner() throws Exception {
    LateFlowWorkload.raw();
  }

  @CryptoFlow("overload")
  public static void overloaded(int ignored) throws Exception {
    LateFlowWorkload.raw();
  }

  @CryptoFlow("overload")
  public static void overloaded(String ignored) throws Exception {
    LateFlowWorkload.raw();
  }

  @CryptoFlow("exceptional")
  public static void exceptional() throws Exception {
    LateFlowWorkload.raw();
    throw new IllegalStateException("expected application failure");
  }

  // Deliberately exhausts the child JVM stack to test the caught-error restoration window.
  @SuppressWarnings("InfiniteRecursion")
  @CryptoFlow("recursive")
  public static void recursive() {
    recursive();
  }

  @CryptoFlow("unobserved")
  public static void unobserved() throws Exception {
    LateFlowWorkload.raw();
  }
}
