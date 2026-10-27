package com.developmentsprint.cryptocensus.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Bounded reads and the same exclusive-create/atomic-replace boundary used for CBOM output. */
public final class DocumentFiles {
  private DocumentFiles() {}

  /** Reads a bounded regular file without following a symbolic link. */
  public static byte[] read(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Expected a regular document file: " + path);
    }
    try (InputStream input =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(CanonicalJson.MAX_BYTES + 1);
      if (bytes.length > CanonicalJson.MAX_BYTES) {
        throw new IOException("Document exceeds input budget");
      }
      return bytes;
    }
  }

  /**
   * Snapshots bytes and atomically replaces a target through an exclusively created private file.
   */
  public static void write(Path path, byte[] bytes) throws IOException {
    if (bytes.length > CanonicalJson.MAX_BYTES) {
      throw new IOException("Document exceeds output budget");
    }
    byte[] snapshot = bytes.clone();
    Path target = path.toAbsolutePath();
    Path directory = target.getParent();
    if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Document target is a directory");
    }
    boolean posix = directory.getFileSystem().supportedFileAttributeViews().contains("posix");
    FileAttribute<?>[] directoryAttributes =
        posix
            ? new FileAttribute<?>[] {
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            }
            : new FileAttribute<?>[0];
    FileAttribute<?>[] attributes =
        posix
            ? new FileAttribute<?>[] {
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            }
            : new FileAttribute<?>[0];
    Files.createDirectories(directory, directoryAttributes);
    Path temporary = directory.resolve(".census-" + UUID.randomUUID() + ".tmp");
    Cbom.writeVia(temporary, target, snapshot, attributes);
  }
}
