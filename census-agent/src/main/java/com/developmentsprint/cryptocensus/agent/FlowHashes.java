package com.developmentsprint.cryptocensus.agent;

import com.developmentsprint.cryptocensus.core.CanonicalJson;

/** Suppresses only observer-owned hashing, never annotation loading or application callbacks. */
final class FlowHashes {
  private FlowHashes() {}

  static String revision(Object metadata) {
    boolean previous = Recorder.suppressInternalHash();
    try {
      return CanonicalJson.revision(metadata);
    } finally {
      Recorder.restoreInternalHash(previous);
    }
  }
}
