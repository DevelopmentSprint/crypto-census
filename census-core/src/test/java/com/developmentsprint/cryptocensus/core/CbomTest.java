package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.cyclonedx.Version;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class CbomTest {

  private static final Cbom.Metadata META =
      new Cbom.Metadata("payments-service", Map.of("java.version", "21.0.8"));

  private static Inventory sample() {
    Inventory inventory = new Inventory();
    inventory.record(
        CryptoAsset.cipher("AES/GCM/NoPadding", 256, "SunJCE", Operation.ENCRYPT),
        new Occurrence("com.acme.Payments", "encrypt", 42, null));
    inventory.record(
        CryptoAsset.tls("TLSv1.3", "TLS_AES_128_GCM_SHA256"),
        new Occurrence("com.acme.Client", "call", 7, "jsse"));
    inventory.record(
        CryptoAsset.of(Primitive.KEM, "ML-KEM-768", 0, "SunJCE"),
        new Occurrence("com.acme.Kem", "establish", 3, null));
    inventory.record(
        CryptoAsset.of(Primitive.SIGNATURE, "SHA256withRSA", 2048, "SunRsaSign", Operation.SIGN),
        new Occurrence("com.acme.Signer", "sign", 12, null));
    return inventory;
  }

  @Test
  void outputValidatesAgainstTheCycloneDx16Schema() throws Exception {
    byte[] json = Cbom.toJson(sample(), META).getBytes(StandardCharsets.UTF_8);
    assertThat(new JsonParser().validate(json, Version.VERSION_16)).isEmpty();
  }

  @Test
  void schemaValidationIsNotVacuous() throws Exception {
    // negative control: the validator must reject a value outside the 1.6 primitive enum
    String bad =
        Cbom.toJson(sample(), META)
            .replace("\"primitive\": \"ae\"", "\"primitive\": \"not-a-primitive\"");
    assertThat(bad).contains("not-a-primitive");
    assertThat(new JsonParser().validate(bad.getBytes(StandardCharsets.UTF_8), Version.VERSION_16))
        .isNotEmpty();
  }

  @Test
  void writesCycloneDxCryptoAssets() {
    @SuppressWarnings("unchecked")
    Map<String, Object> bom = (Map<String, Object>) Json.parse(Cbom.toJson(sample(), META));

    assertThat(bom).containsEntry("bomFormat", "CycloneDX").containsEntry("specVersion", "1.6");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> components = (List<Map<String, Object>>) bom.get("components");
    assertThat(components).hasSize(4);
    assertThat(components)
        .allSatisfy(c -> assertThat(c).containsEntry("type", "cryptographic-asset"));

    assertThat(component(components, "AES-256-GCM"))
        .extractingByKey("cryptoProperties")
        .asString()
        .contains(
            "assetType=algorithm",
            "primitive=ae",
            "mode=gcm",
            "nistQuantumSecurityLevel=5",
            "cryptoFunctions=[encrypt]")
        .doesNotContain("executionEnvironment");
    assertThat(component(components, "ML-KEM-768"))
        .extractingByKey("cryptoProperties")
        .asString()
        .contains("parameterSetIdentifier=768");
    assertThat(
            components.stream()
                .filter(c -> c.get("name").toString().startsWith("TLSv1.3"))
                .findFirst()
                .orElseThrow())
        .extractingByKey("cryptoProperties")
        .asString()
        .contains("assetType=protocol", "version=1.3", "TLS_AES_128_GCM_SHA256");
  }

  private static Map<String, Object> component(List<Map<String, Object>> components, String name) {
    return components.stream().filter(c -> c.get("name").equals(name)).findFirst().orElseThrow();
  }

  @Test
  void roundTripsAndMergesInventories() {
    CryptoAsset rsa =
        CryptoAsset.of(Primitive.SIGNATURE, "SHA256withRSA", 2048, "SunRsaSign", Operation.SIGN);
    Occurrence site = new Occurrence("com.acme.Signer", "sign", 12, null);

    Inventory first = new Inventory();
    first.add(rsa, site, 3);
    Inventory second = new Inventory();
    second.add(rsa, site, 4);
    second.record(
        CryptoAsset.of(Primitive.MESSAGE_DIGEST, "MD5", 0, "SUN"),
        new Occurrence(Occurrence.JDK_LOCATION, null, -1, "jar-verification"));

    Inventory merged = Cbom.parse(Cbom.toJson(first, META)).inventory();
    merged.mergeFrom(Cbom.parse(Cbom.toJson(second, META)).inventory());

    List<Inventory.Entry> entries = merged.entries();
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).asset().algorithm()).isEqualTo("MD5");
    assertThat(entries.get(0).occurrences().keySet().iterator().next().via())
        .isEqualTo("jar-verification");
    assertThat(entries.get(1).asset()).isEqualTo(rsa);
    assertThat(entries.get(1).occurrences()).containsEntry(site, 7L);
  }

  @Test
  void viaSurvivesRoundTripEvenWithStructuralCharacters() {
    Inventory inventory = new Inventory();
    Occurrence odd = new Occurrence("com.acme.X", "m", 1, "vendor;sub=system");
    inventory.record(CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, null), odd);

    Inventory back = Cbom.parse(Cbom.toJson(inventory, META)).inventory();
    assertThat(back.entries().get(0).occurrences()).containsKey(odd);
  }

  @Test
  void bomRefsAreUniqueForDistinctAssets() {
    CryptoAsset a = CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, "A B");
    CryptoAsset b = CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, "A_B");
    assertThat(a).isNotEqualTo(b);
    assertThat(a.ref()).isNotEqualTo(b.ref());
  }

  @Test
  void recordsDroppedObservationsAndPolicyVersion() {
    Inventory inventory = Inventory.bounded(1, 1);
    inventory.record(
        CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, null),
        new Occurrence("a.A", "m", 1, null));
    inventory.record(
        CryptoAsset.of(Primitive.MESSAGE_DIGEST, "MD5", 0, null),
        new Occurrence("a.A", "m", 2, null));

    Cbom.Document doc = Cbom.parse(Cbom.toJson(inventory, META));
    assertThat(doc.inventory().droppedObservations()).isEqualTo(1);
    assertThat(doc.properties())
        .containsEntry("cryptocensus:policy", Policy.ID + "/" + Policy.VERSION);
  }

  @Test
  void ignoresComponentsFromOtherTools() {
    String foreign =
        "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":["
            + "{\"type\":\"library\",\"name\":\"commons-lang3\"}]}";
    assertThat(Cbom.parse(foreign).inventory().isEmpty()).isTrue();
  }

  @Test
  void keepsRecognizedComponentsThatLostTheirOccurrences() {
    String stripped =
        "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":[{"
            + "\"type\":\"cryptographic-asset\",\"name\":\"MD5\",\"properties\":["
            + "{\"name\":\"cryptocensus:primitive\",\"value\":\"MESSAGE_DIGEST\"},"
            + "{\"name\":\"cryptocensus:algorithm\",\"value\":\"MD5\"},"
            + "{\"name\":\"cryptocensus:count\",\"value\":\"3\"}]}]}";
    List<Inventory.Entry> entries = Cbom.parse(stripped).inventory().entries();
    assertThat(entries)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.assessment().risk()).isEqualTo(Risk.BROKEN);
              assertThat(e.occurrences()).containsEntry(Occurrence.UNKNOWN, 3L);
            });
  }

  @Test
  void rejectsMalformedOrForeignDocuments() {
    assertThatThrownBy(() -> Cbom.parse("{\"bomFormat\":\"SPDX\"}"))
        .hasMessageContaining("CycloneDX");
    assertThatThrownBy(() -> Cbom.parse("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.4\"}"))
        .hasMessageContaining("specVersion");
    String negative =
        "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":[{\"properties\":["
            + "{\"name\":\"cryptocensus:primitive\",\"value\":\"MESSAGE_DIGEST\"},"
            + "{\"name\":\"cryptocensus:algorithm\",\"value\":\"MD5\"}],"
            + "\"evidence\":{\"occurrences\":[{\"location\":\"a\","
            + "\"additionalContext\":\"count=-5\"}]}}]}";
    assertThatThrownBy(() -> Cbom.parse(negative)).hasMessageContaining("Negative count");
    String duplicate =
        "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":[],\"components\":[]}";
    assertThatThrownBy(() -> Cbom.parse(duplicate)).hasMessageContaining("Duplicate key");
  }

  @Test
  void maximalAgentCbomStaysWithinTheParserBudget() {
    // the agent's default caps (500 assets x 50 sites) must produce documents the plugin accepts
    Inventory full = Inventory.bounded(500, 50);
    for (int a = 0; a < 500; a++) {
      CryptoAsset asset = CryptoAsset.of(Primitive.MESSAGE_DIGEST, "ALG-" + a, 0, "SomeProvider");
      for (int s = 0; s < 50; s++) {
        full.record(
            asset,
            new Occurrence(
                "com.example.application.pkg.SomeFairlyLongClassName" + s,
                "someMethodName",
                1234,
                "jsse"));
      }
    }
    Cbom.Document doc = Cbom.parse(Cbom.toJson(full, META));
    assertThat(doc.inventory().entries()).hasSize(500);
  }

  @Test
  void writeNeverDeletesAnEntryItDidNotCreate(@TempDir Path dir) throws Exception {
    // regression: when CREATE_NEW lost a name collision, cleanup deleted the other entry
    Path existing = dir.resolve(".cbom-collision.tmp");
    Files.writeString(existing, "someone else's file");
    assertThatThrownBy(() -> Cbom.writeVia(existing, dir.resolve("cbom.json"), new byte[] {1}))
        .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    assertThat(Files.readString(existing)).isEqualTo("someone else's file");
  }

  @Test
  @SuppressWarnings("unchecked")
  void agentDocumentsAreValidatedStrictly() {
    java.util.Map<String, String> props = new java.util.LinkedHashMap<>();
    props.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    props.put("cryptocensus:transformErrors", "0");
    props.put("cryptocensus:recordingFailures", "0");
    props.put("cryptocensus:drainTimedOut", "0");
    props.put("cryptocensus:untrackedTypes", "0");
    String good = Cbom.toJson(sample(), new Cbom.Metadata("x", props));
    assertThat(Cbom.parse(good).hasAgentHealth()).isTrue();

    // a malformed counter is not "present"
    assertThat(
            Cbom.parse(withMetadata(good, "cryptocensus:drainTimedOut", "not-a-number"))
                .hasAgentHealth())
        .isFalse();
    // a declared overflow that the occurrences do not back up is inconsistent
    assertThat(Cbom.parse(withMetadata(good, "cryptocensus:overflowedSites", "9")).hasAgentHealth())
        .isFalse();

    assertThat(
            Cbom.parse(withMetadata(good, "cryptocensus:untrackedTypes", "lots")).hasAgentHealth())
        .isFalse();

    // standard fields other tools read must agree with the properties we enforce on (round 4)
    java.util.Map<String, Object> forged = (java.util.Map<String, Object>) Json.parse(good);
    for (Object o : (java.util.List<Object>) forged.get("components")) {
      java.util.Map<String, Object> crypto =
          (java.util.Map<String, Object>)
              ((java.util.Map<String, Object>) o).get("cryptoProperties");
      Object alg = crypto.get("algorithmProperties");
      if (alg != null) {
        ((java.util.Map<String, Object>) alg).put("parameterSetIdentifier", "512");
      }
    }
    assertThatThrownBy(() -> Cbom.parse(Json.write(forged))).hasMessageContaining("contradict");

    // a component stripped of its properties must not silently vanish from agent evidence
    java.util.Map<String, Object> bom = (java.util.Map<String, Object>) Json.parse(good);
    java.util.List<java.util.Map<String, Object>> components =
        (java.util.List<java.util.Map<String, Object>>) bom.get("components");
    components.get(0).put("properties", java.util.List.of());
    assertThatThrownBy(() -> Cbom.parse(Json.write(bom)))
        .hasMessageContaining("lacks cryptocensus properties");
  }

  @SuppressWarnings("unchecked")
  private static String withMetadata(String json, String name, String value) {
    java.util.Map<String, Object> bom = (java.util.Map<String, Object>) Json.parse(json);
    java.util.Map<String, Object> meta = (java.util.Map<String, Object>) bom.get("metadata");
    for (Object p : (java.util.List<Object>) meta.get("properties")) {
      java.util.Map<String, Object> pm = (java.util.Map<String, Object>) p;
      if (name.equals(pm.get("name"))) {
        pm.put("value", value);
      }
    }
    return Json.write(bom);
  }

  @Test
  void singleHugeStringFailsBeforeItIsBuilt() {
    // round 4: a 40 MB string in an ignored field filled a 128 MB heap before the budget was
    // charged
    String huge =
        "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"x\":\""
            + "a".repeat(40_000_000)
            + "\"}";
    assertThatThrownBy(() -> Cbom.parse(huge)).hasMessageContaining("retain more than");
    assertThat(Cbom.MAX_INPUT_BYTES)
        .isLessThan(40_000_000); // and such a file is refused before parsing anyway
  }

  @Test
  void unpairedSurrogatesCannotCollideWithLiteralCharacters() {
    CryptoAsset lone = CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, "A\uD800");
    CryptoAsset question = CryptoAsset.of(Primitive.MESSAGE_DIGEST, "SHA-256", 0, "A?");
    assertThat(lone.ref()).isNotEqualTo(question.ref());
  }

  @Test
  void healthEnvelopeIsRequiredAndNeverDefaultsToHealthy() {
    // regression: a header-only document parsed as an empty, apparently healthy input
    Cbom.Document headerOnly = Cbom.parse("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\"}");
    assertThat(headerOnly.hasAgentHealth()).isFalse();
    assertThatThrownBy(() -> headerOnly.health("cryptocensus:transformErrors"))
        .hasMessageContaining("Missing");

    java.util.Map<String, String> props = new java.util.LinkedHashMap<>(META.properties());
    props.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    props.put("cryptocensus:transformErrors", "0");
    props.put("cryptocensus:recordingFailures", "0");
    props.put("cryptocensus:drainTimedOut", "0");
    props.put("cryptocensus:untrackedTypes", "0");
    Cbom.Document agent = Cbom.parse(Cbom.toJson(sample(), new Cbom.Metadata("x", props)));
    assertThat(agent.hasAgentHealth()).isTrue();
    assertThat(agent.health("cryptocensus:overflowedSites")).isZero();
  }

  @Test
  void duplicateMetadataPropertiesAreRejected() {
    // regression: a later dropped=0 silently overwrote an earlier dropped=9
    String doc =
        "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"metadata\":{\"properties\":["
            + "{\"name\":\"cryptocensus:dropped\",\"value\":\"9\"},"
            + "{\"name\":\"cryptocensus:dropped\",\"value\":\"0\"}]}}";
    assertThatThrownBy(() -> Cbom.parse(doc)).hasMessageContaining("Duplicate property");
  }

  @Test
  void componentNameMustAgreeWithEnforcedProperties() {
    // regression: a component named MD5 could be assessed via a contradictory SHA-256 property
    String doc =
        Cbom.toJson(sample(), META).replace("\"name\": \"ML-KEM-768\"", "\"name\": \"MD5\"");
    assertThatThrownBy(() -> Cbom.parse(doc)).hasMessageContaining("contradicts");
  }

  @Test
  void readStopsAtTheByteLimitWhateverTheFileSystemSaid(@TempDir Path dir) throws Exception {
    Path big = dir.resolve("big.json");
    try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(big.toFile(), "rw")) {
      f.setLength(Cbom.MAX_INPUT_BYTES + 1); // sparse: fast to create
    }
    assertThatThrownBy(() -> Cbom.readBytes(big)).hasMessageContaining("exceeds");
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void writeReplacesSymlinkInsteadOfFollowingIt(@TempDir Path dir) throws Exception {
    Path victim = dir.resolve("victim.txt");
    Files.writeString(victim, "precious");
    Path link = dir.resolve("cbom.json");
    Files.createSymbolicLink(link, victim);

    Cbom.write(sample(), META, link);

    assertThat(Files.readString(victim)).isEqualTo("precious");
    assertThat(Files.isSymbolicLink(link)).isFalse();
    assertThat(Files.readString(link)).contains("CycloneDX");
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(link)))
        .isEqualTo("rw-------");
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void readRefusesSymlinks(@TempDir Path dir) throws Exception {
    Path real = dir.resolve("real.json");
    Files.writeString(real, Cbom.toJson(sample(), META));
    Path link = dir.resolve("link.json");
    Files.createSymbolicLink(link, real);
    assertThatThrownBy(() -> Cbom.read(link)).hasMessageContaining("not a regular file");
    assertThat(Cbom.read(real).inventory().entries()).hasSize(4);
  }
}
