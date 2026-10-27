package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.core.Cbom;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Bounded local evidence helpers; paths are trusted operator configuration. */
final class ProfileFiles {
  private ProfileFiles() {}

  static byte[] read(Path path, int maximum) throws IOException {
    byte[] bytes = Cbom.readBytes(path);
    if (bytes.length > maximum) {
      throw new IOException("Profile document exceeds its limit");
    }
    return bytes;
  }

  static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  static boolean sameHash(String expected, String actual) {
    return MessageDigest.isEqual(
        expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  static void create(Path path, byte[] bytes) throws IOException {
    Path absolute = path.toAbsolutePath();
    Path parent = absolute.getParent();
    if (parent == null) {
      throw new IOException("Output must have a parent directory");
    }
    Files.createDirectories(parent);
    if (absolute.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          absolute,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      Files.write(absolute, bytes, StandardOpenOption.WRITE);
    } else {
      Files.write(absolute, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
  }

  static void regularOutput(Path output) throws IOException {
    if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Output must be a regular file");
    }
  }
}
