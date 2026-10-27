package com.developmentsprint.cryptocensus.it;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** An unannotated helper usable on another thread without a propagated application scope. */
public final class LateFlowWorkload {
  private LateFlowWorkload() {}

  public static void raw() throws Exception {
    Cipher.getInstance("AES/GCM/NoPadding")
        .init(Cipher.ENCRYPT_MODE, new SecretKeySpec(new byte[16], "AES"));
  }
}
