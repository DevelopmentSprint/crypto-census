package com.developmentsprint.cryptocensus.it;

import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.annotations.DataProtection;

/** Invalid declarations must remain actionable without exporting their payloads. */
public final class InvalidAnnotationsWorkload {
  private InvalidAnnotationsWorkload() {}

  public static void run() throws Exception {
    invalid();
    orphan();
  }

  @CryptoFlow("private invalid value")
  public static void invalid() throws Exception {
    LateFlowWorkload.raw();
  }

  @DataProtection(classification = "private-classification")
  public static void orphan() throws Exception {
    LateFlowWorkload.raw();
  }

  @CryptoFlow("unsupported")
  public static native void unsupported();
}
