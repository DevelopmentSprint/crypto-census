package com.developmentsprint.cryptocensus.delivery;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

/** Atomically replaces a private receipt with no destination URL, credentials or remote text. */
final class ReceiptStore {
  private ReceiptStore() {}

  static void validatePaths(DeliveryRequest request) throws IOException {
    Path artifact = request.artifact().toAbsolutePath().normalize();
    Path receipt = request.receipt().toAbsolutePath().normalize();
    if (artifact.equals(receipt)
        || (Files.exists(receipt) && Files.isSameFile(artifact, receipt))) {
      throw new IOException("Artifact and receipt must be different files");
    }
    if (Files.exists(receipt, LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Receipt must be a regular file");
    }
  }

  static void write(
      DeliveryRequest request, DeliveryReceipt receipt, String endpointHash, int artifactBytes)
      throws IOException {
    Path target = request.receipt().toAbsolutePath();
    Path directory = target.getParent();
    if (directory == null) {
      throw new IOException("Receipt requires a parent directory");
    }
    boolean posix = directory.getFileSystem().supportedFileAttributeViews().contains("posix");
    Files.createDirectories(directory, attributes(posix, "rwx------"));
    Path temporary =
        Files.createTempFile(directory, ".census-receipt-", ".tmp", attributes(posix, "rw-------"));
    try {
      try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer bytes =
            StandardCharsets.UTF_8.encode(json(request, receipt, endpointHash, artifactBytes));
        while (bytes.hasRemaining()) {
          output.write(bytes);
        }
        output.force(true);
      }
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static FileAttribute<?>[] attributes(boolean posix, String permissions) {
    return posix
        ? new FileAttribute<?>[] {
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))
        }
        : new FileAttribute<?>[0];
  }

  private static String json(
      DeliveryRequest request, DeliveryReceipt receipt, String endpointHash, int artifactBytes) {
    return "{\n  \"schema\": \"census-delivery/1\",\n"
        + "  \"adapter\": \"generic-https\",\n"
        + "  \"fidelity\": \"EXACT_ORIGINAL_BYTES\",\n"
        + "  \"metadataSource\": \"CALLER_SUPPLIED\",\n"
        + "  \"evidenceHealthReference\": \"ORIGINAL_ARTIFACT\",\n"
        + "  \"artifactBytes\": "
        + artifactBytes
        + ",\n"
        + "  \"connectTimeoutMillis\": "
        + request.options().connectTimeout().toMillis()
        + ",\n"
        + "  \"requestTimeoutMillis\": "
        + request.options().requestTimeout().toMillis()
        + ",\n"
        + "  \"overallTimeoutMillis\": "
        + request.options().overallTimeout().toMillis()
        + ",\n"
        + "  \"destinationId\": "
        + quote(request.destinationId())
        + ",\n"
        + "  \"endpointSha256\": "
        + quote(endpointHash)
        + ",\n"
        + "  \"application\": "
        + quote(request.application())
        + ",\n"
        + "  \"module\": "
        + quote(request.module())
        + ",\n"
        + "  \"version\": "
        + quote(request.version())
        + ",\n"
        + "  \"runId\": "
        + quote(request.runId())
        + ",\n"
        + "  \"artifactSha256\": "
        + quote(receipt.artifactSha256())
        + ",\n"
        + "  \"deliveryId\": "
        + quote(receipt.deliveryId())
        + ",\n"
        + "  \"outcome\": "
        + quote(receipt.outcome().name())
        + ",\n"
        + "  \"reasonCode\": "
        + quote(receipt.reasonCode())
        + ",\n"
        + "  \"httpStatus\": "
        + receipt.httpStatus()
        + ",\n"
        + "  \"attempts\": "
        + (receipt.outcome() == DeliveryReceipt.Outcome.FAILED ? 0 : 1)
        + ",\n"
        + "  \"recordedAt\": "
        + quote(Instant.now().toString())
        + "\n}\n";
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
