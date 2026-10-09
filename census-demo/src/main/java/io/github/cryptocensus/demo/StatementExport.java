package io.github.cryptocensus.demo;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Legacy statement export. The downstream mainframe job still expects a SHA-1 checksum: the kind of
 * thing nobody remembers until a crypto inventory finds it.
 */
public final class StatementExport {

    public String checksum(String statement) throws GeneralSecurityException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        return HexFormat.of().formatHex(sha1.digest(statement.getBytes(StandardCharsets.UTF_8)));
    }
}
