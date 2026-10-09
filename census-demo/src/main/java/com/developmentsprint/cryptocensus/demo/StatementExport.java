package com.developmentsprint.cryptocensus.demo;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Legacy statement export. The downstream mainframe job still expects a SHA-1 checksum: the kind of
 * thing nobody remembers until a crypto inventory finds it.
 */
public final class StatementExport {

  /**
   * SHA-1 checksum the downstream job expects.
   *
   * @param statement the exported statement
   * @return lower-case hex SHA-1
   * @throws GeneralSecurityException if SHA-1 is unavailable
   */
  @SuppressFBWarnings(
      value = "WEAK_MESSAGE_DIGEST_SHA1",
      justification = "deliberate: the demo shows Crypto Census reporting a legacy SHA-1 checksum")
  public String checksum(String statement) throws GeneralSecurityException {
    MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
    return HexFormat.of().formatHex(sha1.digest(statement.getBytes(StandardCharsets.UTF_8)));
  }
}
