package com.developmentsprint.cryptocensus.delivery.profile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

/** Atomic operational receipt separate from sealed input, preparation and plan objects. */
final class ProfileReceiptStore {
  private ProfileReceiptStore() {}

  static void validate(PlannedDelivery plan) throws IOException {
    Path receipt = plan.profile().receiptPath();
    ProfileFiles.regularOutput(receipt);
    java.util.List<Path> protectedPaths = new java.util.ArrayList<>();
    protectedPaths.add(plan.profile().source());
    if (plan.profile().transport().caBundle() != null) {
      protectedPaths.add(plan.profile().transport().caBundle());
    }
    protectedPaths.add(plan.profile().planPath());
    protectedPaths.add(plan.bundle().manifest());
    protectedPaths.add(plan.bundle().publication().path());
    for (var source : plan.bundle().sources()) {
      protectedPaths.add(source.path());
    }
    for (var source : plan.profile().preparation().inputs()) {
      protectedPaths.add(source.path());
    }
    for (Path source : protectedPaths) {
      if (source.toAbsolutePath().normalize().equals(receipt.toAbsolutePath().normalize())
          || (Files.exists(receipt) && Files.isSameFile(source, receipt))) {
        throw new IOException("Receipt aliases protected evidence");
      }
    }
  }

  static void write(PlannedDelivery plan, ProfileReceipt result) throws IOException {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("schema", "census.delivery-receipt/2");
    root.put("planSha256", plan.planSha256());
    root.put("profileSha256", plan.profile().sourceSha256());
    root.put("manifestSha256", plan.bundle().manifestSha256());
    root.put("artifactSha256", plan.bundle().publication().sha256());
    root.put("adapter", plan.profile().adapter().id());
    root.put("capabilityRevision", plan.profile().policy().capabilityRevision());
    root.put("destinationId", plan.profile().destinationId());
    root.put("mapping", plan.sanitizedPlan().get("mapping"));
    root.put("adapterOptions", plan.profile().options().sanitized());
    root.put("transport", plan.sanitizedPlan().get("transport"));
    root.put("endpointSha256", plan.sanitizedPlan().get("endpointSha256"));
    AdapterResult observed = result.observation();
    root.put("upload", observed.upload().name());
    root.put("ingestion", observed.ingestion().name());
    root.put("reasonCode", observed.reasonCode());
    root.put("httpStatus", observed.httpStatus());
    root.put("remoteOperationId", observed.remoteOperationId());
    root.put("serverVersion", observed.serverVersion());
    root.put(
        "semanticFidelity",
        Map.of(
            "observation",
            "UNVERIFIED",
            "declaredLossCodes",
            plan.sanitizedPlan().get("lossCodes"),
            "adapterReportedLossCodes",
            observed.lossCodes()));
    root.put(
        "inventoryMutation",
        Map.of(
            "intended",
            plan.profile().adapter().capabilities().mutation().name(),
            "readback",
            "UNVERIFIED"));
    root.put(
        "originalRetention",
        result.retention() == null
            ? Map.of("local", "VERIFIED", "remote", "UNVERIFIED")
            : Map.of(
                "local",
                "VERIFIED",
                "remote",
                "RETRIEVED_EXACT_BYTES",
                "scope",
                "PUBLICATION_ARTIFACT_ONLY",
                "artifactSha256",
                result.retention().artifactSha256(),
                "endpointSha256",
                result.retention().endpointSha256(),
                "verifiedAt",
                result.retention().verifiedAt().toString()));
    root.put("evidenceHealthReference", "ORIGINAL_ARTIFACT");
    root.put(
        "destinationReadback",
        result.destinationReadback() == null
            ? Map.of("observation", "UNVERIFIED")
            : Map.of(
                "observation",
                "RETRIEVED_EXACT_BYTES",
                "scope",
                "PUBLICATION_ARTIFACT_ONLY",
                "artifactSha256",
                result.destinationReadback().artifactSha256(),
                "endpointSha256",
                result.destinationReadback().endpointSha256(),
                "verifiedAt",
                result.destinationReadback().verifiedAt().toString()));
    root.put("requiresPublicationReadback", plan.profile().adapter().requiresPublicationReadback());
    root.put("metadataSource", "CALLER_SUPPLIED");
    root.put("effectiveCompletionGate", plan.profile().policy().completion().name());
    root.put("gatePassed", result.successful());
    root.put("startedAt", result.startedAt().toString());
    root.put("finishedAt", result.finishedAt().toString());
    root.put("exchanges", result.exchanges());
    root.put("connectTimeoutMillis", 10000);
    root.put("requestTimeoutMillis", 30000);
    root.put("overallTimeoutMillis", 60000);
    Path target = plan.profile().receiptPath().toAbsolutePath();
    Path directory = target.getParent();
    if (directory == null) {
      throw new IOException("Receipt must have a parent directory");
    }
    boolean posix = directory.getFileSystem().supportedFileAttributeViews().contains("posix");
    Files.createDirectories(directory, attributes(posix, "rwx------"));
    Path temporary =
        Files.createTempFile(directory, ".census-profile-", ".tmp", attributes(posix, "rw-------"));
    try {
      try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer data = ByteBuffer.wrap(JsonSupport.write(root));
        while (data.hasRemaining()) {
          output.write(data);
        }
        output.force(true);
      }
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static FileAttribute<?>[] attributes(boolean posix, String value) {
    return posix
        ? new FileAttribute<?>[] {
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(value))
        }
        : new FileAttribute<?>[0];
  }
}
