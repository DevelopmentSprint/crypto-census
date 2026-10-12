package com.developmentsprint.cryptocensus.delivery.profile;

/** Per-invocation pure protocol state machine; it never opens sockets or resolves secrets. */
public interface AdapterSession {
  /** First bounded operation and initial observation. */
  ProtocolStep first();

  /** Next operation after a bounded response or safe transport failure. */
  ProtocolStep accept(ProtocolResponse response);
}
