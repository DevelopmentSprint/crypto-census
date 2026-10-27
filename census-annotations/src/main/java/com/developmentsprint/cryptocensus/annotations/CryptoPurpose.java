package com.developmentsprint.cryptocensus.annotations;

/** A declared business purpose, not an assertion that an observed operation provides protection. */
public enum CryptoPurpose {
  /**
   * No purpose was declared; observers may establish an operation's role from technical evidence.
   */
  UNSPECIFIED,
  /** Protection of information in transit. */
  TRANSPORT,
  /** Protection of stored information. */
  STORAGE,
  /** Protection of cryptographic key material. */
  KEY_PROTECTION,
  /** Creation of signatures for authenticity or integrity. */
  SIGNING,
  /** Verification of signatures for authenticity or integrity. */
  VERIFICATION
}
