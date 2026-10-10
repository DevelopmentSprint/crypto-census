package com.developmentsprint.cryptocensus.delivery.preparation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class PreparationIo {
  static final int MAX_BYTES = 16 * 1024 * 1024;

  private PreparationIo() {}

  static void checkHash(String hash) {
    if (hash == null || !hash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("SHA-256 requires 64 lowercase hexadecimal characters");
    }
  }

  static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static boolean sameHash(String first, String second) {
    return MessageDigest.isEqual(
        first.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        second.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  static byte[] read(Path path, String hash) throws IOException {
    checkHash(hash);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new PreparationException("INPUT_NOT_REGULAR_FILE");
    }
    final byte[] bytes;
    try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
      bytes = in.readNBytes(MAX_BYTES + 1);
    }
    if (bytes.length > MAX_BYTES) {
      throw new PreparationException("INPUT_LIMIT_EXCEEDED");
    }
    if (!sameHash(hash(bytes), hash)) {
      throw new PreparationException("INPUT_DIGEST_MISMATCH");
    }
    return bytes;
  }

  static void write(Path path, byte[] bytes) throws IOException {
    if (bytes.length > MAX_BYTES) {
      throw new PreparationException("OUTPUT_LIMIT_EXCEEDED");
    }
    if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else {
      Files.createFile(path);
    }
    Files.write(path, bytes, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
  }
}
