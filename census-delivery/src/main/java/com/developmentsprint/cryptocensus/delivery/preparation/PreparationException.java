package com.developmentsprint.cryptocensus.delivery.preparation;

import java.io.IOException;

/** Safe, stable failure without source content or paths in diagnostics. */
public final class PreparationException extends IOException {
  private static final long serialVersionUID = 1L;
  private final String reasonCode;

  PreparationException(String code) {
    super(code);
    reasonCode = code;
  }

  /** Stable diagnostic without artifact content. */
  public String reasonCode() {
    return reasonCode;
  }
}
