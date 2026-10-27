package com.developmentsprint.cryptocensus.maven;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.delivery.profile.JsonSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Local workflow state, deliberately not an authenticity or complete-coverage attestation. */
final class RunEvidence {
  static final String DESCRIPTOR = "run.json";
  static final String MANIFEST = "manifest.json";
  static final String ARTIFACT = "evidence.json";
  private static final String RUN_SCHEMA = "census.maven-run/1";
  private static final String MANIFEST_SCHEMA = "census.maven-evidence/1";
  private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

  private RunEvidence() {}

  static Path prepare(Path parent, String runId, String module, String version) throws IOException {
    String prefix = prefix(runId, module);
    Files.createDirectories(parent);
    Path directory = parent.resolve(prefix);
    privateDirectory(directory); // CREATE_NEW: never reuse another Maven invocation's run.
    Map<String, Object> descriptor = new LinkedHashMap<>();
    descriptor.put("schema", RUN_SCHEMA);
    descriptor.put("runId", runId);
    descriptor.put("module", module);
    descriptor.put("version", version);
    descriptor.put("moduleRunId", UUID.randomUUID().toString());
    descriptor.put("preparedAt", Instant.now().toString());
    descriptor.put("reports", ".");
    writeNew(directory.resolve(DESCRIPTOR), JsonSupport.write(descriptor));
    return directory;
  }

  static Path select(Path parent, String runId, String module, String version) throws IOException {
    String prefix = prefix(runId, module);
    List<Path> matches = new ArrayList<>();
    if (Files.isDirectory(parent, NOFOLLOW)) {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, prefix + "*")) {
        for (Path path : stream) {
          if (matches.size() == 2) {
            break;
          }
          matches.add(path);
        }
      }
    }
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          "Current-run evidence is missing or ambiguous; supply the exact fresh census.runId");
    }
    Path directory = matches.get(0);
    descriptor(directory, runId, module, version);
    return directory;
  }

  static Map<String, Object> descriptor(Path directory, String runId, String module, String version)
      throws IOException {
    uuid(runId);
    if (module == null || module.isBlank() || version == null || version.isBlank()) {
      throw new IllegalArgumentException("Missing expected module/version identity");
    }
    requireDirectory(directory);
    Map<String, Object> descriptor = read(directory.resolve(DESCRIPTOR));
    JsonSupport.fields(
        descriptor, "schema", "runId", "module", "version", "moduleRunId", "preparedAt", "reports");
    if (!RUN_SCHEMA.equals(descriptor.get("schema"))
        || !runId.equals(descriptor.get("runId"))
        || !module.equals(descriptor.get("module"))
        || !version.equals(descriptor.get("version"))
        || !".".equals(descriptor.get("reports"))) {
      throw new IllegalArgumentException(
          "Run descriptor does not match the requested run/module/version");
    }
    uuid(JsonSupport.string(descriptor, "moduleRunId"));
    Instant.parse(JsonSupport.string(descriptor, "preparedAt"));
    return descriptor;
  }

  static String prefix(String runId, String module) {
    uuid(runId);
    if (module == null || module.isBlank()) {
      throw new IllegalArgumentException("Missing module identity");
    }
    return "run-" + runId + "-" + sha256(module.getBytes(StandardCharsets.UTF_8));
  }

  private static void uuid(String value) {
    if (value == null || !UUID.fromString(value).toString().equals(value)) {
      throw new IllegalArgumentException(
          "census.runId must be a canonical lowercase UUID generated for this CI attempt");
    }
  }

  static List<Path> inputs(Path directory) throws IOException {
    requireDirectory(directory);
    List<Path> files = new ArrayList<>();
    long total = 0;
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "cbom-*.json")) {
      for (Path file : stream) {
        if (basename(file).endsWith(".evidence.json")) {
          continue;
        }
        if (!basename(file).matches("cbom-[A-Za-z0-9._-]+\\.json")) {
          throw new IllegalArgumentException("Unsupported raw JVM report basename");
        }
        regular(file);
        if (files.size() >= CheckMojo.MAX_INPUT_FILES) {
          throw new IllegalArgumentException("Too many current-run JVM reports");
        }
        total += Files.size(file);
        if (total > CheckMojo.MAX_TOTAL_BYTES) {
          throw new IllegalArgumentException("Current-run reports exceed the aggregate size limit");
        }
        files.add(file);
      }
    }
    files.sort(null);
    return files;
  }

  static Map<String, String> digests(Path directory) throws IOException {
    Map<String, String> hashes = new LinkedHashMap<>();
    long total = 0;
    for (Path file : inputs(directory)) {
      byte[] bytes = Cbom.readBytes(file);
      total += bytes.length;
      if (total > CheckMojo.MAX_TOTAL_BYTES) {
        throw new IllegalArgumentException("Current-run reports exceed the aggregate size limit");
      }
      hashes.put(basename(file), sha256(bytes));
    }
    if (hashes.isEmpty()) {
      throw new IllegalArgumentException(
          "No current-run JVM evidence exists; refusing to use an earlier report");
    }
    return hashes;
  }

  static Path sealedDirectory(Path directory, String revision) {
    if (revision == null || !revision.matches("[1-9][0-9]{0,8}")) {
      throw new IllegalArgumentException(
          "census.revision must be a positive integer of at most nine digits");
    }
    return directory.resolve("revisions").resolve(revision);
  }

  static Path directoryForManifest(Path manifest) {
    Path absolute = manifest.toAbsolutePath().normalize();
    int count = absolute.getNameCount();
    if (count < 4
        || !MANIFEST.equals(absolute.getName(count - 1).toString())
        || !"revisions".equals(absolute.getName(count - 3).toString())) {
      throw new IllegalArgumentException(
          "census.manifest must name the exact run/revisions/N/manifest.json");
    }
    return parent(parent(parent(absolute)));
  }

  static String revisionForManifest(Path manifest) {
    directoryForManifest(manifest);
    Path absolute = manifest.toAbsolutePath().normalize();
    return absolute.getName(absolute.getNameCount() - 2).toString();
  }

  private static Path parent(Path path) {
    Path parent = path.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Expected a file inside an evidence directory");
    }
    return parent;
  }

  private static String basename(Path path) {
    Path name = path.getFileName();
    if (name == null) {
      throw new IllegalArgumentException("Expected an evidence filename");
    }
    return name.toString();
  }

  static boolean hashMatches(String expected, Object actual) {
    return actual instanceof String text
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII), text.getBytes(StandardCharsets.US_ASCII));
  }

  static Map<String, Object> verify(
      Path directory, String runId, String module, String version, String revision)
      throws IOException {
    final Map<String, Object> descriptor = descriptor(directory, runId, module, version);
    Path sealed = sealedDirectory(directory, revision);
    requireDirectory(directory.resolve("revisions"));
    requireDirectory(sealed);
    Map<String, Object> manifest = read(sealed.resolve(MANIFEST));
    JsonSupport.fields(
        manifest,
        "schema",
        "descriptor",
        "inputs",
        "artifact",
        "sha256",
        "policy",
        "testOutcome",
        "health",
        "policyOutcome",
        "finalizedAt",
        "revision");
    if (!MANIFEST_SCHEMA.equals(manifest.get("schema"))
        || !descriptor.equals(manifest.get("descriptor"))
        || !ARTIFACT.equals(manifest.get("artifact"))
        || !revision.equals(manifest.get("revision"))) {
      throw new IllegalArgumentException(
          "Sealed evidence does not match its run descriptor or original JVM reports");
    }
    Map<String, Object> inputHashes = JsonSupport.object(manifest.get("inputs"));
    if (inputHashes.isEmpty() || inputHashes.size() > CheckMojo.MAX_INPUT_FILES) {
      throw new IllegalArgumentException("Invalid sealed input set");
    }
    long total = 0;
    for (var entry : inputHashes.entrySet()) {
      String name = entry.getKey();
      if (!name.matches("cbom-[A-Za-z0-9._-]+\\.json")) {
        throw new IllegalArgumentException("Manifest input must name a raw JVM report basename");
      }
      Path raw = directory.resolve(name);
      regular(raw);
      byte[] rawBytes = Cbom.readBytes(raw);
      total += rawBytes.length;
      if (total > CheckMojo.MAX_TOTAL_BYTES || !hashMatches(sha256(rawBytes), entry.getValue())) {
        throw new IllegalArgumentException("Sealed input digest or aggregate size mismatch");
      }
    }
    testOutcome(JsonSupport.string(manifest, "testOutcome"));
    if (!Set.of("healthy", "incomplete").contains(manifest.get("health"))
        || !Set.of("passed", "failed").contains(manifest.get("policyOutcome"))) {
      throw new IllegalArgumentException("Invalid sealed health or policy assessment");
    }
    Instant.parse(JsonSupport.string(manifest, "finalizedAt"));
    JsonSupport.object(manifest.get("policy"));
    Path artifact = sealed.resolve(ARTIFACT);
    regular(artifact);
    byte[] bytes = Cbom.readBytes(artifact);
    Cbom.parseEvidence(bytes);
    if (!hashMatches(sha256(bytes), manifest.get("sha256"))) {
      throw new IllegalArgumentException("Sealed evidence artifact digest mismatch");
    }
    return manifest;
  }

  static void seal(
      Path directory,
      String runId,
      String module,
      String version,
      String revision,
      Map<String, String> inputs,
      Map<String, Object> policy,
      String testOutcome,
      boolean healthy,
      boolean policyPassed)
      throws IOException {
    if (!inputs.equals(digests(directory))) {
      throw new IllegalArgumentException(
          "JVM reports changed during finalization; no artifact was sealed");
    }
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("schema", MANIFEST_SCHEMA);
    manifest.put("revision", revision);
    manifest.put("descriptor", descriptor(directory, runId, module, version));
    manifest.put("inputs", inputs);
    manifest.put("artifact", ARTIFACT);
    manifest.put(
        "sha256", sha256(Cbom.readBytes(sealedDirectory(directory, revision).resolve(ARTIFACT))));
    manifest.put("policy", policy);
    manifest.put("testOutcome", testOutcome(testOutcome));
    manifest.put("health", healthy ? "healthy" : "incomplete");
    manifest.put("policyOutcome", policyPassed ? "passed" : "failed");
    manifest.put("finalizedAt", Instant.now().toString());
    writeNew(sealedDirectory(directory, revision).resolve(MANIFEST), JsonSupport.write(manifest));
  }

  static String testOutcome(String value) {
    if (!Set.of("passed", "failed", "unknown").contains(value)) {
      throw new IllegalArgumentException(
          "census.testOutcome must be passed, failed or unknown (supplied by CI)");
    }
    return value;
  }

  static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  static void privateDirectory(Path directory) throws IOException {
    if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.createDirectory(
          directory,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } else {
      Files.createDirectory(directory);
    }
  }

  static void rejectReceiptAlias(Path directory, Path receipt) throws IOException {
    Path source = directory.toRealPath();
    Path target = resolvedLocation(receipt);
    if (target.startsWith(source)) {
      throw new IllegalArgumentException(
          "Receipt must be outside the immutable evidence run directory");
    }
    if (Files.exists(target)) {
      try (var paths = Files.walk(source, 3)) {
        var iterator = paths.iterator();
        int count = 0;
        while (iterator.hasNext()) {
          Path path = iterator.next();
          if (++count > 50_000) {
            throw new IllegalArgumentException(
                "Run state exceeds the receipt-alias validation limit");
          }
          if (Files.isRegularFile(path, NOFOLLOW) && Files.isSameFile(path, target)) {
            throw new IllegalArgumentException("Receipt aliases immutable run evidence");
          }
        }
      }
    }
  }

  private static Path resolvedLocation(Path path) throws IOException {
    for (Path segment : path) {
      if ("..".equals(segment.toString()) || ".".equals(segment.toString())) {
        throw new IllegalArgumentException(
            "Evidence-bound output paths cannot contain dot segments");
      }
    }
    Path absolute = path.toAbsolutePath().normalize();
    Path ancestor = absolute;
    while (!Files.exists(ancestor)) {
      ancestor = ancestor.getParent();
      if (ancestor == null) {
        throw new IOException("Receipt location has no existing parent");
      }
    }
    return ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize();
  }

  private static void writeNew(Path file, byte[] bytes) throws IOException {
    Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
  }

  private static Map<String, Object> read(Path file) throws IOException {
    regular(file);
    try (var stream = Files.newInputStream(file)) {
      byte[] bytes = stream.readNBytes(4 * 1024 * 1024 + 1);
      if (bytes.length > 4 * 1024 * 1024) {
        throw new IllegalArgumentException("Run state exceeds its size limit");
      }
      return JsonSupport.object(JsonSupport.parse(bytes));
    }
  }

  private static void regular(Path path) throws IOException {
    if (!Files.isRegularFile(path, NOFOLLOW)) {
      throw new IOException("Current-run input must be a regular file, not a symlink");
    }
  }

  private static void requireDirectory(Path path) throws IOException {
    if (!Files.isDirectory(path, NOFOLLOW)) {
      throw new IOException("Current-run evidence directory is missing or is a symlink");
    }
  }
}
