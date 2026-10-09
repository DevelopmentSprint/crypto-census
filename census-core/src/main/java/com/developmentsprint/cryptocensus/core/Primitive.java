package com.developmentsprint.cryptocensus.core;

/** The JCA/JSSE entry point through which a cryptographic asset was observed. */
public enum Primitive {
  CIPHER,
  SIGNATURE,
  KEY_AGREEMENT,
  KEM,
  KEY_PAIR_GENERATION,
  KEY_GENERATION,
  MESSAGE_DIGEST,
  MAC,
  TLS
}
