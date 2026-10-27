package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.DocumentFiles;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Census-managed context writes serialize through a persistent destination-specific file lock. */
public final class ContextStore {
  private ContextStore() {}

  /**
   * Conflict results leave the destination unchanged; successful no-op applies remain idempotent.
   */
  public record Applied(ContextChanges.Merge merge, boolean written, String previousRevision) {}

  /**
   * Locks, reloads and applies a validated change set to an explicitly selected destination. The
   * destination is never taken from imported data. External manual writers are not governed by this
   * lock protocol.
   */
  public static Applied apply(Path destination, ContextChanges.ChangeSet changes)
      throws IOException {
    if (destination == null) {
      throw new IllegalArgumentException(
          "Choose an explicit durable private context destination; do not use target/ as the"
              + " default");
    }
    Path absolute = destination.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    if (parent == null || absolute.getFileName() == null) {
      throw new IllegalArgumentException("Context destination requires a filename");
    }
    Files.createDirectories(parent, attributes(parent, true));
    // Canonicalize the parent so symlink aliases use the same lock; never follow a target link.
    Path realParent = parent.toRealPath();
    Path target = realParent.resolve(absolute.getFileName());
    Path lockPath = target.resolveSibling(target.getFileName() + ".census.lock");
    try (FileChannel channel =
        FileChannel.open(
            lockPath,
            Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
            attributes(realParent, false))) {
      try (FileLock lock = channel.tryLock()) {
        if (lock == null) {
          throw new IOException(
              "Context is locked by another Census apply; retry after it completes");
        }
        ContextDocument current =
            Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                ? ContextCodec.read(target)
                : ContextDocument.empty();
        String revision = ContextCodec.revision(current);
        ContextChanges.Merge merged = ContextChanges.merge(current, changes);
        if (!merged.conflicts().isEmpty()) {
          return new Applied(merged, false, revision);
        }
        boolean changed = !revision.equals(ContextCodec.revision(merged.document()));
        if (changed) {
          DocumentFiles.write(target, ContextCodec.encode(merged.document()));
        }
        return new Applied(merged, changed, revision);
      } catch (OverlappingFileLockException e) {
        throw new IOException("Context is locked by another Census apply in this JVM", e);
      }
    }
  }

  private static FileAttribute<?>[] attributes(Path path, boolean directory) {
    if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      return new FileAttribute<?>[] {
        PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))
      };
    }
    return new FileAttribute<?>[0];
  }
}
