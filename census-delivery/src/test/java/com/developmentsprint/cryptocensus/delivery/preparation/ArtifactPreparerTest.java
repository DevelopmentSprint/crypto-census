package com.developmentsprint.cryptocensus.delivery.preparation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Primitive;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ArtifactPreparerTest {
  @TempDir Path directory;
  private static final Correlation CORRELATION = new Correlation("app", "module", "1", "run-123");
  private ArtifactInput cbom;
  private ArtifactInput sbom;
  private final ArtifactPreparer preparer = new ArtifactPreparer();

  @BeforeEach
  void inputs() throws Exception {
    Inventory inventory = new Inventory();
    inventory.record(
        CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, "SUN"),
        new Occurrence("sample.Workload", "digest", 1, null));
    inventory.record(
        CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-512", 0, "SUN"),
        new Occurrence("sample.Workload", "digest", 2, null));
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    for (String health : Cbom.HEALTH_PROPERTIES) {
      metadata.put(health, "0");
    }
    cbom =
        input(
            "raw.json",
            Cbom.toJson(inventory, new Cbom.Metadata("module", metadata)),
            InputKind.CENSUS_RAW_CBOM);
    sbom =
        input(
            "software.json",
            """
            {"bomFormat":"CycloneDX","specVersion":"1.6","serialNumber":"urn:uuid:00000000-0000-0000-0000-000000000001","version":1,
            "metadata":{"component":{"type":"application","name":"app","bom-ref":"app"},"tools":[{"vendor":"CycloneDX","name":"cyclonedx-maven-plugin","version":"2.9.1"}]},
            "components":[{"type":"library","name":"library","version":"1.2","bom-ref":"lib","purl":"pkg:maven/example/library@1.2","licenses":[{"license":{"id":"Apache-2.0"}}],"externalReferences":[{"type":"website","url":"https://example.org/library"}]}],
            "dependencies":[{"ref":"app","dependsOn":["lib"]},{"ref":"lib","dependsOn":[]}],
            "compositions":[{"aggregate":"complete","assemblies":["app"],"dependencies":["lib"]}]}
            """,
            InputKind.EXTERNAL_SOFTWARE_SBOM);
  }

  private ArtifactInput input(String file, String json, InputKind kind) throws Exception {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    Path path = Files.write(directory.resolve(file), bytes);
    return new ArtifactInput(path, PreparationIo.hash(bytes), kind, CORRELATION);
  }

  private PreparedBundle prepare(PreparationRequest.Mode mode, String output) throws Exception {
    return preparer.prepare(
        new PreparationRequest(
            mode,
            mode == PreparationRequest.Mode.IDENTITY ? List.of(cbom) : List.of(sbom, cbom),
            directory.resolve(output)));
  }

  @Test
  void linkRetainsExactOriginalsAndSealsCorrelation() throws Exception {
    PreparedBundle bundle = prepare(PreparationRequest.Mode.LINK, "linked");
    assertThat(bundle.publication().readVerified()).isEqualTo(Files.readAllBytes(cbom.path()));
    assertThat(bundle.sources()).hasSize(2);
    assertThat(bundle.unresolvedCount()).isEqualTo(2);
    assertThat(Files.readString(bundle.manifest()))
        .contains("CALLER_ASSERTED", "UNRESOLVED_CALLER_AND_PROVIDER", "cryptocensus:schema");
    assertThat(preparer.load(bundle.manifest(), bundle.manifestSha256())).isEqualTo(bundle);
  }

  @Test
  void combinePreservesSoftwareGraphAndDistinctCryptoAndIsDeterministic() throws Exception {
    PreparedBundle first = prepare(PreparationRequest.Mode.COMBINE, "combined1");
    PreparedBundle second = prepare(PreparationRequest.Mode.COMBINE, "combined2");
    assertThat(first.publication().readVerified()).isEqualTo(second.publication().readVerified());
    assertThat(first.manifestSha256()).isEqualTo(second.manifestSha256());
    ObjectNode doc = BomSchema.parse(first.publication().readVerified());
    assertThat(doc.path("components")).hasSize(3);
    String root = doc.at("/metadata/component/bom-ref").asText();
    String library = doc.at("/components/0/bom-ref").asText();
    assertThat(doc.at("/dependencies/0/ref").asText()).isEqualTo(root);
    assertThat(doc.at("/dependencies/0/dependsOn/0").asText()).isEqualTo(library);
    assertThat(doc.at("/compositions/0/dependencies/0").asText()).isEqualTo(library);
    assertThat(doc.at("/components/0/purl").asText()).isEqualTo("pkg:maven/example/library@1.2");
    assertThat(doc.at("/components/0/externalReferences/0/url").asText())
        .isEqualTo("https://example.org/library");
    assertThat(doc.at("/dependencies")).hasSize(2);
    assertThat(first.lossCodes()).isEmpty();
    assertThat(preparer.load(first.manifest(), first.manifestSha256())).isEqualTo(first);
  }

  @Test
  void rejectsMismatchedBuildBeforeCreatingOutput() throws Exception {
    ArtifactInput other =
        new ArtifactInput(
            sbom.path(),
            sbom.expectedSha256(),
            sbom.kind(),
            new Correlation("app", "module", "1", "other"));
    assertThatThrownBy(
            () ->
                preparer.prepare(
                    new PreparationRequest(
                        PreparationRequest.Mode.LINK,
                        List.of(cbom, other),
                        directory.resolve("bad"))))
        .hasMessage("INPUT_CORRELATION_MISMATCH");
    assertThat(directory.resolve("bad")).doesNotExist();
  }

  @Test
  void originalAndManifestTamperingFailClosed() throws Exception {
    PreparedBundle bundle = prepare(PreparationRequest.Mode.IDENTITY, "identity");
    Files.writeString(bundle.publication().path(), "{}");
    assertThatThrownBy(bundle::verify).hasMessage("INPUT_DIGEST_MISMATCH");
    Files.writeString(bundle.manifest(), "{}");
    assertThatThrownBy(() -> preparer.load(bundle.manifest(), bundle.manifestSha256()))
        .hasMessage("INPUT_DIGEST_MISMATCH");
  }

  @Test
  void existingDestinationIsNeverReplaced() throws Exception {
    PreparedBundle bundle = prepare(PreparationRequest.Mode.IDENTITY, "identity");
    assertThatThrownBy(() -> prepare(PreparationRequest.Mode.IDENTITY, "identity"))
        .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    bundle.verify();
  }

  @ParameterizedTest
  @ValueSource(strings = {"duplicate", "dangling", "schema", "version", "ref", "deep", "trailing"})
  void rejectsUnsafeExternalDocuments(String mutation) throws Exception {
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(sbom.path()));
    String json;
    switch (mutation) {
      case "duplicate" ->
          json =
              "{\"bomFormat\":\"CycloneDX\",\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\"}";
      case "dangling" -> {
        ((ObjectNode) doc.path("dependencies").get(0)).put("ref", "missing");
        json = doc.toString();
      }
      case "schema" -> {
        ((ObjectNode) doc.path("components").get(0)).put("type", "invented");
        json = doc.toString();
      }
      case "version" -> {
        doc.put("specVersion", "1.7");
        json = doc.toString();
      }
      case "ref" -> {
        ((ObjectNode) doc.path("components").get(0)).put("bom-ref", "app");
        json = doc.toString();
      }
      case "deep" -> json = "[".repeat(100) + "0" + "]".repeat(100);
      default -> json = doc + "{}";
    }
    ArtifactInput bad = input("bad.json", json, InputKind.EXTERNAL_SOFTWARE_SBOM);
    assertThatThrownBy(
            () ->
                preparer.prepare(
                    new PreparationRequest(
                        PreparationRequest.Mode.IDENTITY,
                        List.of(bad),
                        directory.resolve("bad-output"))))
        .isInstanceOf(PreparationException.class);
  }

  @Test
  void manifestReplayRejectsReferenceRewriteAndPathTampering() throws Exception {
    PreparedBundle bundle = prepare(PreparationRequest.Mode.COMBINE, "combine");
    ObjectNode manifest = BomSchema.parseObject(Files.readAllBytes(bundle.manifest()));
    ((ObjectNode) manifest.path("referenceRewrites").get(0)).put("to", "tampered");
    byte[] bytes = BomSchema.bytes(manifest);
    Files.write(bundle.manifest(), bytes);
    String hash = PreparationIo.hash(bytes);
    assertThatThrownBy(() -> preparer.load(bundle.manifest(), hash))
        .hasMessage("INPUT_MANIFEST_MISMATCH");
    ((ObjectNode) manifest.path("sources").get(0)).put("file", "../escape");
    bytes = BomSchema.bytes(manifest);
    Files.write(bundle.manifest(), bytes);
    String pathHash = PreparationIo.hash(bytes);
    assertThatThrownBy(() -> preparer.load(bundle.manifest(), pathHash))
        .hasMessage("INPUT_MANIFEST_INVALID");
  }

  @Test
  void bomContentCannotSelectRemoteSchema() throws Exception {
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(sbom.path()));
    doc.put("$schema", "http://127.0.0.1:1/never-fetch");
    ArtifactInput external = input("remote.json", doc.toString(), InputKind.EXTERNAL_SOFTWARE_SBOM);
    assertThatThrownBy(
            () ->
                preparer.prepare(
                    new PreparationRequest(
                        PreparationRequest.Mode.IDENTITY,
                        List.of(external),
                        directory.resolve("remote"))))
        .hasMessage("INPUT_SCHEMA_INVALID");
  }

  @Test
  void preservesSignedOriginalWithoutTransferringSignature() throws Exception {
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(sbom.path()));
    doc.putObject("signature").put("algorithm", "HS256").put("value", "dGVzdA");
    ((ObjectNode) doc.path("components").get(0))
        .putObject("signature")
        .put("algorithm", "HS256")
        .put("value", "dGVzdA");
    sbom = input("signed.json", doc.toString(), InputKind.EXTERNAL_SOFTWARE_SBOM);
    PreparedBundle linked = prepare(PreparationRequest.Mode.LINK, "signed-link");
    assertThat(
            linked.sources().stream()
                .filter(a -> a.kind() == InputKind.EXTERNAL_SOFTWARE_SBOM)
                .findFirst()
                .orElseThrow()
                .readVerified())
        .isEqualTo(Files.readAllBytes(sbom.path()));
    PreparedBundle combined = prepare(PreparationRequest.Mode.COMBINE, "signed-combine");
    assertThat(combined.lossCodes()).containsExactly("SOURCE_SIGNATURE_NOT_TRANSFERRED");
    ObjectNode derived = BomSchema.parse(combined.publication().readVerified());
    assertThat(derived.has("signature")).isFalse();
    assertThat(derived.path("components").get(0).has("signature")).isFalse();
  }

  @Test
  void rewritesNestedComponentsServiceAndVulnerabilityReferences() throws Exception {
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(sbom.path()));
    ObjectNode lib = (ObjectNode) doc.path("components").get(0);
    lib.putArray("components")
        .addObject()
        .put("type", "library")
        .put("name", "nested")
        .put("bom-ref", "nested");
    lib.putObject("evidence")
        .putObject("identity")
        .put("field", "name")
        .put("confidence", 1)
        .putArray("tools")
        .add("app");
    doc.putArray("services").addObject().put("name", "service").put("bom-ref", "service");
    doc.withArray("dependencies")
        .addObject()
        .put("ref", "service")
        .putArray("dependsOn")
        .add("nested");
    doc.putArray("vulnerabilities")
        .addObject()
        .put("bom-ref", "vuln")
        .put("id", "CVE-example")
        .putArray("affects")
        .addObject()
        .put("ref", "nested");
    PreparedBundle combined =
        preparer.prepare(
            new PreparationRequest(
                PreparationRequest.Mode.COMBINE,
                List.of(cbom, input("rich.json", doc.toString(), InputKind.EXTERNAL_SOFTWARE_SBOM)),
                directory.resolve("rich")));
    ObjectNode result = BomSchema.parse(combined.publication().readVerified());
    String nested = result.at("/components/0/components/0/bom-ref").asText();
    assertThat(result.at("/vulnerabilities/0/affects/0/ref").asText()).isEqualTo(nested);
    assertThat(result.at("/dependencies/2/dependsOn/0").asText()).isEqualTo(nested);
    assertThat(result.at("/components/0/evidence/identity/tools/0").asText())
        .isEqualTo(result.at("/metadata/component/bom-ref").asText());
  }

  @Test
  void matchingSourceReferenceNamesRemainDistinct() throws Exception {
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(sbom.path()));
    ObjectNode crypto = BomSchema.parse(Files.readAllBytes(cbom.path()));
    String collision = crypto.at("/components/0/bom-ref").asText();
    ((ObjectNode) doc.path("components").get(0)).put("bom-ref", collision);
    ((com.fasterxml.jackson.databind.node.ArrayNode) doc.at("/dependencies/0/dependsOn"))
        .set(0, BomSchema.JSON.getNodeFactory().textNode(collision));
    ((ObjectNode) doc.path("dependencies").get(1)).put("ref", collision);
    ((com.fasterxml.jackson.databind.node.ArrayNode) doc.at("/compositions/0/dependencies"))
        .set(0, BomSchema.JSON.getNodeFactory().textNode(collision));
    sbom = input("collision.json", doc.toString(), InputKind.EXTERNAL_SOFTWARE_SBOM);
    ObjectNode result =
        BomSchema.parse(
            prepare(PreparationRequest.Mode.COMBINE, "collision").publication().readVerified());
    assertThat(result.at("/components/0/bom-ref").asText())
        .isNotEqualTo(result.at("/components/1/bom-ref").asText());
  }

  @Test
  void kindCannotBypassCensusValidationAndMergedEvidenceIsSupported() throws Exception {
    ArtifactInput mislabeled =
        new ArtifactInput(
            cbom.path(), cbom.expectedSha256(), InputKind.EXTERNAL_SOFTWARE_SBOM, CORRELATION);
    assertThatThrownBy(
            () ->
                preparer.prepare(
                    new PreparationRequest(
                        PreparationRequest.Mode.IDENTITY,
                        List.of(mislabeled),
                        directory.resolve("mislabeled"))))
        .hasMessage("INPUT_KIND_MISMATCH");
    Cbom.Document raw = Cbom.parseEvidence(Files.readAllBytes(cbom.path()));
    Map<String, String> metadata = new LinkedHashMap<>(raw.properties());
    metadata.remove(Cbom.SCHEMA_PROPERTY);
    metadata.put("cryptocensus:project", "com.example:module:1");
    metadata.put("cryptocensus:input:raw.json", "sha256:" + cbom.expectedSha256());
    ArtifactInput merged =
        input(
            "merged.json",
            Cbom.toJson(raw.inventory(), new Cbom.Metadata("module", metadata)),
            InputKind.CENSUS_MERGED_CBOM);
    PreparedBundle bundle =
        preparer.prepare(
            new PreparationRequest(
                PreparationRequest.Mode.LINK, List.of(sbom, merged), directory.resolve("merged")));
    assertThat(bundle.publication().kind()).isEqualTo(InputKind.CENSUS_MERGED_CBOM);
    ArtifactInput bad =
        new ArtifactInput(
            merged.path(), merged.expectedSha256(), InputKind.CENSUS_RAW_CBOM, CORRELATION);
    assertThatThrownBy(
            () ->
                preparer.prepare(
                    new PreparationRequest(
                        PreparationRequest.Mode.IDENTITY,
                        List.of(bad),
                        directory.resolve("bad-raw"))))
        .hasMessage("INPUT_KIND_MISMATCH");
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(cbom.path()));
    ((ObjectNode) doc.path("components").get(0)).put("type", "library");
    ArtifactInput malformed = input("malformed.json", doc.toString(), InputKind.CENSUS_RAW_CBOM);
    assertThatThrownBy(
            () ->
                preparer.prepare(
                    new PreparationRequest(
                        PreparationRequest.Mode.IDENTITY,
                        List.of(malformed),
                        directory.resolve("bad-cbom"))))
        .hasMessage("INPUT_CBOM_INVALID");
  }

  @Test
  void inputAndParserLimitsAndSymlinksFailClosed() throws Exception {
    byte[] oversized = new byte[PreparationIo.MAX_BYTES + 1];
    Path file = Files.write(directory.resolve("large.json"), oversized);
    ArtifactInput large =
        new ArtifactInput(
            file, PreparationIo.hash(oversized), InputKind.EXTERNAL_SOFTWARE_SBOM, CORRELATION);
    assertThatThrownBy(
            () ->
                preparer.prepare(
                    new PreparationRequest(
                        PreparationRequest.Mode.IDENTITY,
                        List.of(large),
                        directory.resolve("large"))))
        .hasMessage("INPUT_LIMIT_EXCEEDED");
    assertThatThrownBy(() -> BomSchema.parse(oversized)).hasMessage("INPUT_LIMIT_EXCEEDED");
    assertThatThrownBy(() -> PreparationIo.write(directory.resolve("large-output"), oversized))
        .hasMessage("OUTPUT_LIMIT_EXCEEDED");
    Path link = Files.createSymbolicLink(directory.resolve("symlink.json"), cbom.path());
    assertThatThrownBy(() -> PreparationIo.read(link, cbom.expectedSha256()))
        .hasMessage("INPUT_NOT_REGULAR_FILE");
    assertThatThrownBy(() -> BomSchema.parse(new byte[] {(byte) 0xc3, 0x28}))
        .hasMessage("INPUT_JSON_INVALID");
    assertThatThrownBy(
            () ->
                BomSchema.parse(
                    ("[" + "0,".repeat(250_001) + "0]").getBytes(StandardCharsets.UTF_8)))
        .hasMessage("INPUT_LIMIT_EXCEEDED");
  }

  @Test
  void externalBomLinksAreRetainedWithoutResolutionAndLocalLinksChecked() throws Exception {
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(sbom.path()));
    ((ObjectNode) doc.path("components").get(0))
        .withArray("externalReferences")
        .addObject()
        .put("type", "bom")
        .put("url", "urn:cdx:00000000-0000-0000-0000-000000000099/1#remote");
    sbom = input("links.json", doc.toString(), InputKind.EXTERNAL_SOFTWARE_SBOM);
    assertThat(prepare(PreparationRequest.Mode.LINK, "links").sources()).hasSize(2);
    ((ObjectNode) doc.at("/components/0/externalReferences/1"))
        .put("url", "urn:cdx:00000000-0000-0000-0000-000000000001/1#missing");
    ArtifactInput dangling =
        input("dangling-link.json", doc.toString(), InputKind.EXTERNAL_SOFTWARE_SBOM);
    assertThatThrownBy(
            () ->
                preparer.prepare(
                    new PreparationRequest(
                        PreparationRequest.Mode.IDENTITY,
                        List.of(dangling),
                        directory.resolve("dangling-link"))))
        .hasMessage("INPUT_REFERENCE_DANGLING");
  }

  @ParameterizedTest
  @ValueSource(strings = {"7", "2147483648", "1.0", "1e0"})
  void combineRewritesSelfBomLinksToDerivedIdentityAndRetainsExternalLinks(String version)
      throws Exception {
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(sbom.path()));
    doc.set("version", BomSchema.JSON.readTree(version));
    java.math.BigInteger revision = doc.path("version").bigIntegerValue();
    String original = "urn:cdx:00000000-0000-0000-0000-000000000001/" + revision + "#lib";
    String otherVersion =
        "urn:cdx:00000000-0000-0000-0000-000000000001/"
            + revision.add(java.math.BigInteger.ONE)
            + "#lib";
    String external = "urn:cdx:00000000-0000-0000-0000-000000000099/7#remote";
    ((ObjectNode) doc.at("/dependencies/0"))
        .putArray("dependsOn")
        .add(original)
        .add(otherVersion)
        .add(external);
    doc.putArray("services")
        .addObject()
        .put("name", "service")
        .putArray("data")
        .addObject()
        .put("flow", "inbound")
        .put("classification", "public")
        .putArray("source")
        .add(original)
        .add(external);
    ((ObjectNode) doc.at("/components/0"))
        .withArray("externalReferences")
        .addObject()
        .put("type", "bom")
        .put("url", original);
    sbom = input("self-links.json", doc.toString(), InputKind.EXTERNAL_SOFTWARE_SBOM);
    PreparedBundle combined = prepare(PreparationRequest.Mode.COMBINE, "self-links");
    ObjectNode result = BomSchema.parse(combined.publication().readVerified());
    String target =
        result.path("serialNumber").asText().replace("urn:uuid:", "urn:cdx:")
            + "/1#"
            + result.at("/components/0/bom-ref").asText();
    assertThat(result.at("/dependencies/0/dependsOn/0").asText()).isEqualTo(target);
    assertThat(result.at("/services/0/data/0/source/0").asText()).isEqualTo(target);
    assertThat(result.at("/components/0/externalReferences/1/url").asText()).isEqualTo(target);
    assertThat(result.at("/dependencies/0/dependsOn/1").asText()).isEqualTo(otherVersion);
    assertThat(result.at("/dependencies/0/dependsOn/2").asText()).isEqualTo(external);
    assertThat(result.at("/services/0/data/0/source/1").asText()).isEqualTo(external);
    assertThat(Files.readString(combined.manifest())).contains(original, target);
    ObjectNode manifest =
        (ObjectNode) BomSchema.JSON.readTree(Files.readAllBytes(combined.manifest()));
    for (var source : manifest.path("sources")) {
      if (source.path("kind").asText().equals(InputKind.EXTERNAL_SOFTWARE_SBOM.name())) {
        assertThat(source.path("version").bigIntegerValue()).isEqualTo(revision);
      }
    }
    assertThat(preparer.load(combined.manifest(), combined.manifestSha256())).isEqualTo(combined);
    assertThat(combined.publication().readVerified())
        .isEqualTo(
            prepare(PreparationRequest.Mode.COMBINE, "self-links-repeat")
                .publication()
                .readVerified());
  }

  @Test
  void boundsExpandedBomRevisionBeforeConstructingLocalLinks() throws Exception {
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(sbom.path()));
    doc.set("version", BomSchema.JSON.readTree("1e129"));
    assertThatThrownBy(() -> BomReferences.version(doc)).hasMessage("INPUT_LIMIT_EXCEEDED");
  }

  @Test
  void combinesRealCycloneDxMavenOutput() throws Exception {
    byte[] bytes;
    try (java.io.InputStream in =
        getClass().getResourceAsStream("/preparation/cyclonedx-maven-software.json")) {
      bytes = java.util.Objects.requireNonNull(in).readAllBytes();
    }
    Path source = Files.write(directory.resolve("maven-sbom.json"), bytes);
    sbom =
        new ArtifactInput(
            source, PreparationIo.hash(bytes), InputKind.EXTERNAL_SOFTWARE_SBOM, CORRELATION);
    PreparedBundle combined = prepare(PreparationRequest.Mode.COMBINE, "maven-combined");
    ObjectNode result = BomSchema.parse(combined.publication().readVerified());
    assertThat(result.at("/components/0/purl").asText())
        .isEqualTo("pkg:maven/org.slf4j/slf4j-api@2.0.17?type=jar");
    assertThat(result.at("/metadata/tools/components/0/name").asText())
        .isEqualTo("cyclonedx-maven-plugin");
    assertThat(result.path("components")).hasSize(3);
    assertThat(preparer.load(combined.manifest(), combined.manifestSha256())).isEqualTo(combined);
  }

  @Test
  void rejectsUnpairedSurrogatesAndPreservesExactDecimalValues() throws Exception {
    for (String escaped : List.of("d800", "dc00", "d800x")) {
      String json =
          "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
              + "\"properties\":[{\"name\":\"unicode\",\"value\":\""
              + "\\u"
              + escaped
              + "\"}]}";
      assertThatThrownBy(() -> BomSchema.parse(json.getBytes(StandardCharsets.UTF_8)))
          .hasMessage("INPUT_JSON_INVALID");
    }
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(sbom.path()));
    ((ObjectNode) doc.path("components").get(0))
        .putObject("evidence")
        .putObject("identity")
        .put("field", "name")
        .put("confidence", new java.math.BigDecimal("0.12345678901234567890123456789"));
    doc.putArray("properties").addObject().put("name", "unicode").put("value", "valid-𝄞");
    sbom = input("decimals.json", doc.toString(), InputKind.EXTERNAL_SOFTWARE_SBOM);
    ObjectNode result =
        BomSchema.parse(
            prepare(PreparationRequest.Mode.COMBINE, "decimals").publication().readVerified());
    assertThat(result.at("/components/0/evidence/identity/confidence").decimalValue())
        .isEqualByComparingTo("0.12345678901234567890123456789");
    assertThat(result.at("/properties/0/value").asText()).isEqualTo("valid-𝄞");
  }

  @Test
  void censusPropertiesInsideMixedSoftwareCannotSelectWeakerValidator() throws Exception {
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(sbom.path()));
    ((ObjectNode) doc.path("components").get(0))
        .putArray("properties")
        .addObject()
        .put("name", "cryptocensus:algorithm")
        .put("value", "contradictory");
    ArtifactInput bad =
        input("mixed-census.json", doc.toString(), InputKind.EXTERNAL_SOFTWARE_SBOM);
    assertThatThrownBy(
            () ->
                preparer.prepare(
                    new PreparationRequest(
                        PreparationRequest.Mode.IDENTITY,
                        List.of(bad),
                        directory.resolve("mixed"))))
        .hasMessage("INPUT_KIND_MISMATCH");
  }

  @Test
  void missingBomSerialDoesNotMisclassifyLocalReferencesAsBomLinks() throws Exception {
    ObjectNode doc = BomSchema.parse(Files.readAllBytes(sbom.path()));
    doc.remove("serialNumber");
    ((ObjectNode) doc.at("/metadata/component")).put("bom-ref", "/1#app");
    ((ObjectNode) doc.at("/dependencies/0")).put("ref", "/1#app");
    ((com.fasterxml.jackson.databind.node.ArrayNode) doc.at("/compositions/0/assemblies"))
        .set(0, BomSchema.JSON.getNodeFactory().textNode("/1#app"));
    sbom = input("no-serial.json", doc.toString(), InputKind.EXTERNAL_SOFTWARE_SBOM);
    PreparedBundle bundle = prepare(PreparationRequest.Mode.COMBINE, "no-serial");
    assertThat(
            BomSchema.parse(bundle.publication().readVerified()).at("/dependencies/0/ref").asText())
        .startsWith("sbom-");
  }
}
