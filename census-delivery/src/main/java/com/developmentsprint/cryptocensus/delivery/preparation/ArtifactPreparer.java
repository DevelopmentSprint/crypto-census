package com.developmentsprint.cryptocensus.delivery.preparation;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.delivery.EvidenceValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Offline, bounded preparation of immutable original and derived evidence. */
public final class ArtifactPreparer {
  @SuppressWarnings("ArrayRecordComponent") // Private per-call snapshot; arrays never escape.
  private record Source(
      ArtifactInput input,
      byte[] bytes,
      ObjectNode document,
      BomReferences references,
      String id) {}

  /** Validates all inputs before exclusively creating the output directory. */
  public PreparedBundle prepare(PreparationRequest request) throws IOException {
    List<Source> sources = validate(request);
    Correlation correlation = sources.get(0).input().correlation();
    ObjectNode manifest = BomSchema.JSON.createObjectNode();
    manifest.put("schema", "census.preparation/1");
    manifest.put("preparerVersion", "1");
    manifest.put("mode", request.mode().name());
    manifest.set("correlation", correlation(correlation));
    manifest.put("correlationEvidence", "CALLER_ASSERTED");
    final ArrayNode manifestSources = manifest.putArray("sources");
    Source crypto =
        sources.stream().filter(s -> isCensus(s.input().kind())).findFirst().orElse(null);
    int unresolved = crypto == null ? 0 : crypto.document().path("components").size();
    manifest.put("unresolvedCount", unresolved);
    manifest.put("attribution", "UNRESOLVED_CALLER_AND_PROVIDER");
    ArrayNode rewrites = manifest.putArray("referenceRewrites");
    ArrayNode transforms = manifest.putArray("transformations");
    List<String> losses = new ArrayList<>();
    byte[] combined = null;
    if (request.mode() == PreparationRequest.Mode.COMBINE) {
      combined = combine(sources, correlation, rewrites, transforms, losses);
    }
    for (Source source : sources) {
      ObjectNode item = manifestSources.addObject();
      item.put("id", source.id());
      item.put("kind", source.input().kind().name());
      item.put("sha256", source.input().expectedSha256());
      item.put("bytes", source.bytes().length);
      item.put("file", source.id() + ".json");
      item.put("serialNumber", source.document().path("serialNumber").asText());
      item.put("version", BomReferences.version(source.document()));
      item.put("componentCount", source.references().components());
      item.set("sourceMetadata", source.document().path("metadata"));
      item.put("signatureVerification", "NOT_PERFORMED");
    }
    Path output = request.outputDirectory().toAbsolutePath().normalize();
    final List<PreparedArtifact> retained = new ArrayList<>();
    Source selected = crypto == null ? sources.get(0) : crypto;
    PreparedArtifact publication =
        new PreparedArtifact(
            selected.id(),
            selected.input().kind(),
            output.resolve(selected.id() + ".json"),
            selected.input().expectedSha256());
    if (combined != null) {
      publication =
          new PreparedArtifact(
              "combined",
              InputKind.DERIVED_COMBINED_BOM,
              output.resolve("combined.json"),
              PreparationIo.hash(combined));
    }
    ObjectNode published = manifest.putObject("publication");
    published.put("id", publication.id());
    published.put("kind", publication.kind().name());
    published.put("sha256", publication.sha256());
    published.put(
        "file", java.util.Objects.requireNonNull(publication.path().getFileName()).toString());
    if (combined != null) {
      published.put("serialNumber", BomSchema.parse(combined).path("serialNumber").asText());
    }
    ArrayNode lossArray = manifest.putArray("lossCodes");
    losses.forEach(lossArray::add);
    byte[] manifestBytes = BomSchema.bytes(manifest);
    if (manifestBytes.length > PreparationIo.MAX_BYTES) {
      throw new PreparationException("OUTPUT_LIMIT_EXCEEDED");
    }
    List<Path> owned = new ArrayList<>();
    if (output.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.createDirectory(
          output,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } else {
      Files.createDirectory(output);
    }
    try {
      for (Source source : sources) {
        Path file = output.resolve(source.id() + ".json");
        owned.add(file);
        PreparationIo.write(file, source.bytes());
        retained.add(
            new PreparedArtifact(
                source.id(), source.input().kind(), file, source.input().expectedSha256()));
      }
      if (combined != null) {
        owned.add(publication.path());
        PreparationIo.write(publication.path(), combined);
      }
      Path manifestPath = output.resolve("manifest.json");
      owned.add(manifestPath);
      PreparationIo.write(manifestPath, manifestBytes);
      return new PreparedBundle(
          retained,
          publication,
          manifestPath,
          PreparationIo.hash(manifestBytes),
          correlation,
          losses,
          unresolved);
    } catch (IOException | RuntimeException failure) {
      for (Path file : owned) {
        try {
          Files.deleteIfExists(file);
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      try {
        Files.delete(output);
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  /**
   * Reloads sealed evidence by deterministic replay; hashes are integrity checks, not signatures.
   */
  @SuppressWarnings(
      "PMD.PreserveStackTrace") // Parser diagnostics may contain private artifact data.
  public PreparedBundle load(Path manifestPath, String expectedSha256) throws IOException {
    byte[] bytes = PreparationIo.read(manifestPath, expectedSha256);
    ObjectNode manifest = BomSchema.parseObject(bytes);
    if (!"census.preparation/1".equals(manifest.path("schema").asText())) {
      throw new PreparationException("INPUT_MANIFEST_INVALID");
    }
    Path directory =
        java.util.Objects.requireNonNull(manifestPath.toAbsolutePath().normalize().getParent());
    JsonNode identity = manifest.path("correlation");
    final Correlation correlation;
    final PreparationRequest.Mode mode;
    List<ArtifactInput> inputs = new ArrayList<>();
    try {
      correlation =
          new Correlation(
              identity.path("application").textValue(),
              identity.path("module").textValue(),
              identity.path("version").textValue(),
              identity.path("runId").textValue());
      mode = PreparationRequest.Mode.valueOf(manifest.path("mode").asText());
      if (!manifest.path("sources").isArray() || manifest.path("sources").size() > 2) {
        throw new PreparationException("INPUT_MANIFEST_INVALID");
      }
      for (JsonNode source : manifest.path("sources")) {
        InputKind kind = InputKind.valueOf(source.path("kind").asText());
        String file = isCensus(kind) ? "cbom.json" : "sbom.json";
        if (!file.equals(source.path("file").asText())) {
          throw new PreparationException("INPUT_MANIFEST_INVALID");
        }
        inputs.add(
            new ArtifactInput(
                directory.resolve(file), source.path("sha256").asText(), kind, correlation));
      }
    } catch (IllegalArgumentException e) {
      throw new PreparationException("INPUT_MANIFEST_INVALID");
    }
    Path scratch =
        Files.createTempDirectory(
            java.util.Objects.requireNonNull(directory.getParent()), ".census-prepare-");
    Path replayDirectory = scratch.resolve("replay");
    PreparedBundle replay = null;
    try {
      replay = prepare(new PreparationRequest(mode, inputs, replayDirectory));
      if (!PreparationIo.sameHash(expectedSha256, replay.manifestSha256())) {
        throw new PreparationException("INPUT_MANIFEST_MISMATCH");
      }
      List<PreparedArtifact> sources = new ArrayList<>();
      for (PreparedArtifact source : replay.sources()) {
        sources.add(
            new PreparedArtifact(
                source.id(),
                source.kind(),
                directory.resolve(source.path().getFileName()),
                source.sha256()));
      }
      PreparedArtifact publication =
          new PreparedArtifact(
              replay.publication().id(),
              replay.publication().kind(),
              directory.resolve(replay.publication().path().getFileName()),
              replay.publication().sha256());
      PreparedBundle bundle =
          new PreparedBundle(
              sources,
              publication,
              manifestPath,
              expectedSha256,
              correlation,
              replay.lossCodes(),
              replay.unresolvedCount());
      bundle.verify();
      return bundle;
    } finally {
      if (replay != null) {
        for (PreparedArtifact source : replay.sources()) {
          Files.deleteIfExists(source.path());
        }
        Files.deleteIfExists(replay.publication().path());
        Files.deleteIfExists(replay.manifest());
        Files.delete(replayDirectory);
      }
      Files.delete(scratch);
    }
  }

  @SuppressWarnings("PMD.PreserveStackTrace") // Never expose Census parser input through causes.
  private static List<Source> validate(PreparationRequest request) throws IOException {
    boolean identity = request.mode() == PreparationRequest.Mode.IDENTITY;
    if (request.inputs().size() != (identity ? 1 : 2)) {
      throw new PreparationException("INPUT_KIND_MISMATCH");
    }
    List<Source> sources = new ArrayList<>();
    Correlation correlation = request.inputs().get(0).correlation();
    for (ArtifactInput input :
        request.inputs().stream().sorted(Comparator.comparing(i -> i.kind().name())).toList()) {
      if (!correlation.equals(input.correlation())) {
        throw new PreparationException("INPUT_CORRELATION_MISMATCH");
      }
      if (input.kind() == InputKind.DERIVED_COMBINED_BOM) {
        throw new PreparationException("INPUT_MANIFEST_REQUIRED");
      }
      byte[] bytes = PreparationIo.read(input.path(), input.expectedSha256());
      ObjectNode doc = BomSchema.parse(bytes);
      if (isCensus(input.kind())) {
        try {
          Cbom.Document evidence = Cbom.parseEvidence(bytes);
          EvidenceValidator.validate(evidence);
          boolean raw = evidence.properties().containsKey(Cbom.SCHEMA_PROPERTY);
          if (raw != (input.kind() == InputKind.CENSUS_RAW_CBOM)) {
            throw new PreparationException("INPUT_KIND_MISMATCH");
          }
        } catch (IllegalArgumentException e) {
          throw new PreparationException("INPUT_CBOM_INVALID");
        }
      } else {
        if (hasCensusProperties(doc)) {
          throw new PreparationException("INPUT_KIND_MISMATCH");
        }
      }
      sources.add(
          new Source(
              input,
              bytes,
              doc,
              BomReferences.inspect(doc),
              isCensus(input.kind()) ? "cbom" : "sbom"));
    }
    if (!identity
        && (sources.stream().filter(s -> isCensus(s.input().kind())).count() != 1
            || sources.stream()
                    .filter(s -> s.input().kind() == InputKind.EXTERNAL_SOFTWARE_SBOM)
                    .count()
                != 1)) {
      throw new PreparationException("INPUT_KIND_MISMATCH");
    }
    return sources;
  }

  private static byte[] combine(
      List<Source> sources,
      Correlation correlation,
      ArrayNode rewrites,
      ArrayNode transforms,
      List<String> losses)
      throws IOException {
    Source software =
        sources.stream()
            .filter(s -> s.input().kind() == InputKind.EXTERNAL_SOFTWARE_SBOM)
            .findFirst()
            .orElseThrow();
    Source crypto =
        sources.stream().filter(s -> isCensus(s.input().kind())).findFirst().orElseThrow();
    if (crypto.references().hasMetadataReferences()) {
      throw new PreparationException("COMPOSITION_SURFACE_UNSUPPORTED");
    }
    ObjectNode result = software.document().deepCopy();
    ObjectNode cryptoCopy = crypto.document().deepCopy();
    Set<String> arrays =
        Set.of(
            "components",
            "services",
            "dependencies",
            "compositions",
            "vulnerabilities",
            "annotations",
            "formulation",
            "properties",
            "externalReferences");
    Set<String> envelope =
        Set.of(
            "$schema",
            "bomFormat",
            "specVersion",
            "serialNumber",
            "version",
            "metadata",
            "signature");
    Iterator<String> keys = cryptoCopy.properties().stream().map(Map.Entry::getKey).iterator();
    while (keys.hasNext()) {
      String key = keys.next();
      if (!arrays.contains(key) && !envelope.contains(key)) {
        throw new PreparationException("COMPOSITION_SURFACE_UNSUPPORTED");
      }
    }
    ObjectNode identity = correlation(correlation);
    for (Source source : sources) {
      identity.put(source.id(), source.input().expectedSha256());
    }
    String serial = "urn:uuid:" + UUID.nameUUIDFromBytes(BomSchema.bytes(identity));
    String derivedLink = serial.replace("urn:uuid:", "urn:cdx:") + "/1#";
    for (Source source : sources) {
      ObjectNode tree =
          source.input().kind() == InputKind.EXTERNAL_SOFTWARE_SBOM ? result : cryptoCopy;
      Map<String, String> map =
          source
              .references()
              .rewrite(
                  tree, source.id() + "-" + source.input().expectedSha256() + "-", derivedLink);
      map.forEach(
          (from, to) -> {
            ObjectNode rewrite = rewrites.addObject();
            rewrite.put("sourceId", source.id());
            rewrite.put("from", from);
            rewrite.put("to", to);
          });
    }
    // Keep sources' signed bytes, but never carry a signature over modified descendants.
    int signatures = removeSignatures(result) + removeSignatures(cryptoCopy);
    if (signatures > 0) {
      losses.add("SOURCE_SIGNATURE_NOT_TRANSFERRED");
      transforms.add("Source signatures retained only in exact original artifacts");
    }
    for (String field : arrays.stream().sorted().toList()) {
      if (cryptoCopy.has(field)) {
        ArrayNode target =
            result.has(field) ? (ArrayNode) result.get(field) : result.putArray(field);
        target.addAll((ArrayNode) cryptoCopy.get(field));
      }
    }
    ObjectNode metadata =
        result.has("metadata") ? (ObjectNode) result.get("metadata") : result.putObject("metadata");
    metadata.remove("timestamp");
    ArrayNode properties =
        metadata.has("properties")
            ? (ArrayNode) metadata.get("properties")
            : metadata.putArray("properties");
    properties
        .addObject()
        .put("name", "cryptocensus:preparation")
        .put("value", "census.preparation/1");
    for (Source source : sources) {
      properties
          .addObject()
          .put("name", "cryptocensus:source:" + source.id())
          .put("value", "sha256:" + source.input().expectedSha256());
    }
    // Preserve existing software producer metadata and declare the composer separately.
    properties.addObject().put("name", "cryptocensus:composer").put("value", "crypto-census");
    result.put("serialNumber", serial);
    result.put("version", 1);
    transforms.add("New deterministic document identity and source-scoped reference rewrites");
    transforms.add(
        "Original software metadata preserved; crypto metadata and health retained in source"
            + " manifest");
    transforms.add("No caller-to-package or provider-to-package relationships inferred");
    byte[] bytes = BomSchema.bytes(result);
    BomReferences.inspect(BomSchema.parse(bytes));
    return bytes;
  }

  private static int removeSignatures(JsonNode node) {
    int count = 0;
    if (node instanceof ObjectNode object && object.remove("signature") != null) {
      count++;
    }
    for (JsonNode child : node) {
      count += removeSignatures(child);
    }
    return count;
  }

  private static boolean hasCensusProperties(JsonNode node) {
    for (JsonNode property : node.path("properties")) {
      if (property.path("name").asText().startsWith("cryptocensus:")) {
        return true;
      }
    }
    for (JsonNode child : node) {
      if (hasCensusProperties(child)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isCensus(InputKind kind) {
    return kind == InputKind.CENSUS_RAW_CBOM || kind == InputKind.CENSUS_MERGED_CBOM;
  }

  private static ObjectNode correlation(Correlation value) {
    ObjectNode node = BomSchema.JSON.createObjectNode();
    node.put("application", value.application());
    node.put("module", value.module());
    node.put("version", value.version());
    node.put("runId", value.runId());
    return node;
  }
}
