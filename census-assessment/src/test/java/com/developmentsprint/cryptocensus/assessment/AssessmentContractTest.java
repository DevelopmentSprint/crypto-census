package com.developmentsprint.cryptocensus.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Operation;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Synthetic public examples and independent assertions for the deterministic report contract. */
class AssessmentContractTest {
  private static final String RUN = "12345678-1234-1234-1234-123456789abc";
  private static final String LEGACY_RUN = "22345678-1234-1234-1234-123456789abc";
  private static final String IDENTITY = "synthetic-deployment";
  private static final String APP = "example:records";
  private static final ContextModel.Binding FRAME = frame("encrypt");
  private static final CryptoAsset RSA_2048 = rsa(2048);
  private static final CryptoAsset RSA_1024 = rsa(1024);

  private record Scenario(
      String cbomJson, String evidenceJson, String legacyJson, ContextDocument context) {
    byte[] cbom() {
      return cbomJson.getBytes(StandardCharsets.UTF_8);
    }

    byte[] evidence() {
      return evidenceJson.getBytes(StandardCharsets.UTF_8);
    }

    byte[] legacy() {
      return legacyJson.getBytes(StandardCharsets.UTF_8);
    }

    List<AssessmentInputs.Input> inputs() {
      return List.of(
          new AssessmentInputs.Input(cbom(), evidence(), "synthetic-annotated", "fixture"),
          new AssessmentInputs.Input(legacy(), null, "synthetic-unannotated", "fixture"));
    }

    AssessmentReport report() {
      return AssessmentEngine.assess(inputs(), context, PolicyProfile.defaults());
    }
  }

  @Test
  void representativeArtifactAndReasonActionsMatchReviewedGoldenFiles() throws Exception {
    Scenario fixture = scenario();
    AssessmentReport report = fixture.report();
    Path output = Files.createDirectories(Path.of("target", "assessment-contract"));
    Files.write(output.resolve("sample-cbom.json"), fixture.cbom());
    Files.write(output.resolve("sample-evidence.json"), fixture.evidence());
    Files.write(output.resolve("legacy-cbom.json"), fixture.legacy());
    Files.write(output.resolve("context.json"), ContextCodec.encode(fixture.context()));
    Files.write(output.resolve("assessment-v1.json"), report.bytes());
    byte[] reasons = CanonicalJson.encode(reasonRows(report));
    Files.write(output.resolve("reasons-v1.json"), reasons);
    for (String artifact :
        List.of(
            "sample-cbom.json",
            "sample-evidence.json",
            "legacy-cbom.json",
            "context.json",
            "assessment-v1.json",
            "reasons-v1.json")) {
      assertThat(
              CanonicalJson.encode(
                  CanonicalJson.decode(Files.readAllBytes(output.resolve(artifact)))))
          .as(artifact)
          .isEqualTo(CanonicalJson.encode(CanonicalJson.decode(resource(artifact))));
    }
    List<AssessmentInputs.Input> shuffled = new ArrayList<>(fixture.inputs());
    Collections.reverse(shuffled);
    shuffled.add(fixture.inputs().get(0));
    assertThat(
            AssessmentEngine.assess(shuffled, fixture.context(), PolicyProfile.defaults()).bytes())
        .isEqualTo(report.bytes());
  }

  @Test
  void savedInputsReplayTheExactPairedReport() throws Exception {
    byte[] cbom = resource("sample-cbom.json");
    byte[] evidence = resource("sample-evidence.json");
    EvidenceCodec.validatePair(EvidenceCodec.parse(evidence), cbom);
    AssessmentReport replay =
        AssessmentEngine.assess(
            List.of(
                new AssessmentInputs.Input(cbom, evidence, "synthetic-annotated", "fixture"),
                new AssessmentInputs.Input(
                    resource("legacy-cbom.json"), null, "synthetic-unannotated", "fixture")),
            ContextCodec.parse(resource("context.json")),
            PolicyProfile.defaults());
    assertThat(replay.bytes()).isEqualTo(resource("assessment-v1.json"));
  }

  @Test
  void outputSchemaValidatesAllVariantsAndRejectsMalformedStructure() throws Exception {
    Schema schema = schema();
    var report = scenario().report();
    assertThat(
            schema.validate(new String(report.bytes(), StandardCharsets.UTF_8), InputFormat.JSON))
        .isEmpty();
    assertThat(
            schema.validate(
                new String(
                    AssessmentEngine.assess(
                            List.of(), ContextDocument.empty(), PolicyProfile.defaults())
                        .bytes(),
                    StandardCharsets.UTF_8),
                InputFormat.JSON))
        .isEmpty();
    var wrongVersion = new TreeMap<>(report.data());
    wrongVersion.put("schemaVersion", 2);
    assertInvalid(schema, wrongVersion);
    var extraField = new TreeMap<>(report.data());
    extraField.put("unrecognized", true);
    assertInvalid(schema, extraField);
    var missing = new TreeMap<>(report.data());
    missing.remove("profileHash");
    assertInvalid(schema, missing);
    List<Map<String, Object>> findings = objects(report.data().get("findings"));
    var negativeCount = new TreeMap<>(findings.get(0));
    negativeCount.put("count", -1);
    assertInvalid(schema, changedFinding(report, negativeCount));
    var wrongPriority = new TreeMap<>(findings.get(0));
    var decision = new TreeMap<>(CanonicalJson.object(wrongPriority.get("decision")));
    decision.put("displayPriority", "SAFE_EVERYWHERE");
    wrongPriority.put("decision", decision);
    assertInvalid(schema, changedFinding(report, wrongPriority));
    var tlsCount =
        new TreeMap<>(
            findings.stream()
                .filter(row -> "TLS_CONFIDENTIALITY".equals(row.get("kind")))
                .findFirst()
                .orElseThrow());
    tlsCount.put("count", 1);
    assertInvalid(schema, changedFinding(report, tlsCount));
    var nullableCount = new TreeMap<>(findings.get(0));
    nullableCount.put("count", null);
    assertThat(schema.validate(json(changedFinding(report, nullableCount)), InputFormat.JSON))
        .isEmpty();
  }

  private static Map<String, Object> changedFinding(
      AssessmentReport report, Map<String, Object> row) {
    var changed = new TreeMap<>(report.data());
    changed.put("findings", List.of(row));
    return changed;
  }

  private static void assertInvalid(Schema schema, Map<String, Object> value) {
    assertThat(schema.validate(json(value), InputFormat.JSON)).isNotEmpty();
  }

  private static String json(Object value) {
    return new String(CanonicalJson.encode(value), StandardCharsets.UTF_8);
  }

  private static Schema schema() throws IOException {
    String base = "https://schemas.example.invalid/";
    Map<String, String> documents = new TreeMap<>();
    for (String name : List.of("assessment", "context", "evidence")) {
      String file = name + "-v1.schema.json";
      documents.put(base + file, Files.readString(Path.of("..", "docs", "schemas", file)));
    }
    var registry =
        SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder ->
                builder.resourceLoaders(
                    loaders ->
                        loaders.values(
                            values -> {
                              values.clear();
                              values.add(
                                  iri -> {
                                    String body = documents.get(iri.toString());
                                    if (body == null) {
                                      throw new IllegalArgumentException(
                                          "Schema fixture attempted external loading: " + iri);
                                    }
                                    return () ->
                                        new ByteArrayInputStream(
                                            body.getBytes(StandardCharsets.UTF_8));
                                  });
                            })));
    return registry.getSchema(SchemaLocation.of(base + "assessment-v1.schema.json"));
  }

  @Test
  void technicalPriorityNestedRequirementsAndOrderingRemainIndependent() {
    var findings = objects(scenario().report().data().get("findings"));
    var weak = primitive(findings, "archive", 1024).get(0);
    var classical = primitive(findings, "archive", 2048).get(0);
    assertThat(CanonicalJson.object(weak.get("decision")))
        .containsEntry("displayPriority", "TECHNICAL_REMEDIATION_FIRST");
    assertThat(CanonicalJson.object(classical.get("decision")))
        .containsEntry("displayPriority", "URGENT_PLANNING");
    assertThat(findings.indexOf(weak)).isLessThan(findings.indexOf(classical));
    var helper = primitive(findings, "helper", 2048);
    assertThat(helper)
        .hasSize(3)
        .allSatisfy(
            row ->
                assertThat(CanonicalJson.object(row.get("decision")))
                    .containsEntry("displayPriority", "PLANNED_MIGRATION"));
    assertThat(
            helper.stream()
                .map(
                    row ->
                        path(row) + "@" + CanonicalJson.object(row.get("occurrence")).get("line")))
        .containsExactly("archive/helper@10", "archive/helper@11", "gateway/helper@10");
    assertThat(CanonicalJson.object(primitive(findings, "conflict", 2048).get(0).get("decision")))
        .containsEntry("ruleId", "declaration-review");
    assertThat(CanonicalJson.object(primitive(findings, "broad", 2048).get(0).get("decision")))
        .containsEntry("ruleId", "context-required");
    assertThat(CanonicalJson.object(primitive(findings, "imported", 2048).get(0).get("decision")))
        .containsEntry("ruleId", "planned-classical-migration");
  }

  @Test
  void gapsUnobservedBindingsAndTlsAxesNeverBecomeBlanketReadiness() {
    var data = scenario().report().data();
    var findings = objects(data.get("findings"));
    assertThat(data).containsEntry("incomplete", true);
    assertThat(objects(data.get("flows")))
        .anySatisfy(
            flow -> {
              assertThat(CanonicalJson.object(flow.get("subject")))
                  .containsEntry("flowId", "dormant");
              assertThat(flow).containsEntry("observed", false).containsEntry("inclusiveCount", 0L);
            })
        .anySatisfy(
            flow -> {
              assertThat(CanonicalJson.object(flow.get("subject")))
                  .containsEntry("flowId", "stale");
              assertThat(objects(flow.get("bindings")))
                  .isNotEmpty()
                  .allSatisfy(
                      binding -> assertThat(binding).containsEntry("status", "NOT_OBSERVED"));
            });
    assertThat(findings)
        .anySatisfy(
            row -> {
              assertThat(row.get("subject")).isNull();
              assertThat(CanonicalJson.object(row.get("occurrence")))
                  .containsEntry("location", "<overflow>");
            });
    assertThat(findings)
        .anySatisfy(
            row -> {
              assertThat(row).containsEntry("kind", "TLS_CONFIDENTIALITY");
              assertThat(CanonicalJson.object(row.get("handshakeRef")))
                  .containsEntry("connectionId", "hybrid");
              assertThat(CanonicalJson.object(row.get("technical")))
                  .containsEntry("quantumExposure", "RESISTANT");
            })
        .anySatisfy(
            row -> {
              assertThat(row).containsEntry("kind", "TLS_PEER_AUTHENTICATION");
              assertThat(CanonicalJson.object(row.get("handshakeRef")))
                  .containsEntry("connectionId", "hybrid");
              assertThat(CanonicalJson.object(row.get("technical")))
                  .containsEntry("quantumExposure", "VULNERABLE");
            })
        .anySatisfy(
            row -> {
              assertThat(row)
                  .containsEntry("kind", "TLS_CONFIDENTIALITY")
                  .containsEntry("selectedKeyExchangeExposure", "RESISTANT")
                  .containsEntry("pskOriginExposure", "VULNERABLE");
              assertThat(CanonicalJson.object(row.get("handshakeRef")))
                  .containsEntry("connectionId", "resumed");
              assertThat(CanonicalJson.object(row.get("technical")))
                  .containsEntry("quantumExposure", "NOT_ESTABLISHED");
            })
        .anySatisfy(
            row -> {
              assertThat(row).containsEntry("kind", "TLS_PEER_AUTHENTICATION");
              assertThat(CanonicalJson.object(row.get("handshakeRef")))
                  .containsEntry("connectionId", "resumed");
              assertThat(CanonicalJson.object(row.get("technical")))
                  .containsEntry("quantumExposure", "NOT_ESTABLISHED");
            });
  }

  private static Scenario scenario() {
    List<EvidenceDocument.Observation> observations = new ArrayList<>();
    observations.add(observation(RSA_1024, 10, 2, "archive"));
    observations.add(observation(RSA_2048, 10, 3, "archive"));
    observations.add(observation(RSA_2048, 10, 1, "archive", "helper"));
    observations.add(observation(RSA_2048, 11, 1, "archive", "helper"));
    observations.add(observation(RSA_2048, 10, 1, "gateway", "helper"));
    for (String flow : List.of("conflict", "broad", "imported")) {
      observations.add(observation(RSA_2048, 20, 1, flow));
    }
    observations.add(observation(RSA_2048, 30, 1));
    observations.add(
        new EvidenceDocument.Observation(
            RSA_2048.ref(),
            Occurrence.OVERFLOW,
            scope(),
            5,
            new ContextModel.Binding(
                "<overflow>", "unknown", "", "", ContextModel.Origin.UNKNOWN)));
    Inventory inventory = new Inventory();
    for (var observation : observations) {
      inventory.add(
          observation.assetRef().equals(RSA_1024.ref()) ? RSA_1024 : RSA_2048,
          observation.occurrence(),
          observation.count());
    }
    byte[] cbom = cbom(inventory, RUN);
    List<EvidenceDocument.DiscoveredFlow> flows = new ArrayList<>();
    for (String flow :
        List.of("archive", "helper", "gateway", "conflict", "broad", "imported", "dormant")) {
      List<ContextModel.Declaration> declarations =
          switch (flow) {
            case "archive", "dormant", "conflict" ->
                List.of(
                    annotation(flow, ContextModel.Field.CLASSIFICATION, "sensitive"),
                    annotation(flow, ContextModel.Field.CONFIDENTIALITY_YEARS, "20"),
                    annotation(flow, ContextModel.Field.AUTHENTICITY_YEARS, "10"),
                    annotation(flow, ContextModel.Field.IMPACT, "high"));
            case "helper", "gateway" ->
                List.of(
                    annotation(flow, ContextModel.Field.CLASSIFICATION, "internal"),
                    annotation(flow, ContextModel.Field.CONFIDENTIALITY_YEARS, "1"));
            default -> List.of();
          };
      flows.add(
          new EvidenceDocument.DiscoveredFlow(
              IDENTITY,
              new ContextModel.FlowDefinition(
                  subject(flow), flow, declarations, List.of(frame(flow)))));
    }
    var evidence =
        new EvidenceDocument(
            1,
            RUN,
            CanonicalJson.hash(cbom),
            flows,
            observations,
            List.of(
                handshake("hybrid", 4588, false, null),
                handshake("classical", 29, false, null),
                handshake("resumed", 4588, true, new EvidenceDocument.AttemptRef("classical", 1))),
            List.of(
                new EvidenceDocument.IdentityMapping(
                    IDENTITY,
                    "synthetic-release",
                    "loader-1",
                    "example:workload",
                    APP,
                    EvidenceDocument.IdentityStatus.RESOLVED,
                    "source-catalog")),
            List.of(
                new EvidenceDocument.Capability(
                    "annotation-scopes", EvidenceDocument.CapabilityStatus.SUPPORTED, "synthetic"),
                new EvidenceDocument.Capability(
                    "tls-sunjsse-1.3-correlation",
                    EvidenceDocument.CapabilityStatus.SUPPORTED,
                    "synthetic")),
            EvidenceDocument.zeroHealth());
    EvidenceCodec.validatePair(evidence, cbom);
    Inventory legacy = new Inventory();
    legacy.add(
        CryptoAsset.cipher("AES/GCM/NoPadding", 256, "Synthetic", Operation.ENCRYPT),
        new Occurrence("example.Legacy", "encrypt", 1, null),
        1);
    return new Scenario(
        new String(cbom, StandardCharsets.UTF_8),
        new String(EvidenceCodec.encode(evidence), StandardCharsets.UTF_8),
        new String(cbom(legacy, LEGACY_RUN), StandardCharsets.UTF_8),
        context());
  }

  private static ContextDocument context() {
    List<ContextModel.Declaration> declarations =
        List.of(
            declared(
                "conflict",
                ContextModel.Field.CLASSIFICATION,
                "public",
                ContextModel.SourceKind.USER),
            declared(
                "imported",
                ContextModel.Field.CLASSIFICATION,
                "internal",
                ContextModel.SourceKind.CATALOG),
            declared(
                "imported",
                ContextModel.Field.CONFIDENTIALITY_YEARS,
                "1",
                ContextModel.SourceKind.CATALOG),
            declared(
                "",
                ContextModel.Field.CLASSIFICATION,
                "sensitive",
                ContextModel.SourceKind.APPLICATION_DEFAULT),
            declared(
                "",
                ContextModel.Field.CONFIDENTIALITY_YEARS,
                "20",
                ContextModel.SourceKind.APPLICATION_DEFAULT));
    return new ContextDocument(
        1,
        List.of(
            new ContextDocument.Flow(
                subject("stale"),
                "Stale saved method",
                ContextDocument.State.RETIRED,
                List.of(frame("removedMethod")))),
        declarations,
        List.of(),
        List.of());
  }

  private static EvidenceDocument.Handshake handshake(
      String connection, int group, boolean resumed, EvidenceDocument.AttemptRef origin) {
    return new EvidenceDocument.Handshake(
        connection,
        1,
        EvidenceDocument.Transport.SOCKET,
        EvidenceDocument.PeerRole.CLIENT,
        EvidenceDocument.Outcome.COMPLETED,
        new EvidenceDocument.Negotiation(
            "TLSv1.3",
            "TLS_AES_256_GCM_SHA384",
            group,
            group == 4588 ? "X25519MLKEM768" : "x25519",
            "",
            resumed ? "" : "ecdsa_secp256r1_sha256"),
        new EvidenceDocument.Resumption(
            resumed ? EvidenceDocument.Flag.YES : EvidenceDocument.Flag.NO,
            EvidenceDocument.Flag.YES,
            origin),
        List.of(),
        "synthetic-adapter",
        List.of(),
        scope("archive"));
  }

  private static EvidenceDocument.Observation observation(
      CryptoAsset asset, int line, long count, String... path) {
    return new EvidenceDocument.Observation(
        asset.ref(),
        new Occurrence(FRAME.className(), FRAME.method(), line, null),
        scope(path),
        count,
        FRAME);
  }

  private static EvidenceDocument.ScopeContext scope(String... path) {
    return new EvidenceDocument.ScopeContext(
        IDENTITY,
        java.util.Arrays.stream(path)
            .map(flow -> new EvidenceDocument.ScopeRef(IDENTITY, subject(flow)))
            .toList(),
        path.length == 0
            ? EvidenceDocument.Association.NO_SCOPE
            : EvidenceDocument.Association.SYNCHRONOUS_SCOPE);
  }

  private static CryptoAsset rsa(int bits) {
    return CryptoAsset.cipher(
        "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", bits, "Synthetic", Operation.ENCRYPT);
  }

  private static ContextModel.Subject subject(String flow) {
    return new ContextModel.Subject(APP, flow);
  }

  private static ContextModel.Binding frame(String method) {
    return new ContextModel.Binding(
        "example.Workload", method, "()V", "example:workload", ContextModel.Origin.MAIN);
  }

  private static ContextModel.Declaration annotation(
      String flow, ContextModel.Field field, String value) {
    return declared(flow, field, value, ContextModel.SourceKind.ANNOTATION);
  }

  private static ContextModel.Declaration declared(
      String flow, ContextModel.Field field, String value, ContextModel.SourceKind kind) {
    String id = kind.name() + ":" + flow + ":" + field.name();
    return new ContextModel.Declaration(
        subject(flow),
        field,
        value,
        new ContextModel.Source(
            kind, id, CanonicalJson.hash(id.getBytes(StandardCharsets.UTF_8)), "", ""));
  }

  private static byte[] cbom(Inventory inventory, String nonce) {
    Map<String, String> properties = new TreeMap<>();
    properties.put(EvidenceDocument.RUN_PROPERTY, nonce);
    properties.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    Cbom.HEALTH_PROPERTIES.forEach(key -> properties.put(key, "0"));
    var document =
        new TreeMap<>(
            CanonicalJson.object(
                CanonicalJson.decode(
                    Cbom.toJson(
                            inventory, new Cbom.Metadata("Synthetic contract fixture", properties))
                        .getBytes(StandardCharsets.UTF_8))));
    document.put("serialNumber", "urn:uuid:" + nonce);
    var metadata = new TreeMap<>(CanonicalJson.object(document.get("metadata")));
    metadata.put("timestamp", "2026-01-01T00:00:00Z");
    document.put("metadata", metadata);
    return CanonicalJson.encode(document);
  }

  private static List<Map<String, Object>> primitive(
      List<Map<String, Object>> findings, String flow, int bits) {
    return findings.stream()
        .filter(
            row ->
                "PRIMITIVE".equals(row.get("kind"))
                    && row.get("subject") != null
                    && flow.equals(CanonicalJson.object(row.get("subject")).get("flowId"))
                    && Long.valueOf(bits)
                        .equals(CanonicalJson.object(row.get("asset")).get("keySize")))
        .toList();
  }

  private static String path(Map<String, Object> finding) {
    return objects(finding.get("path")).stream()
        .map(scope -> (String) CanonicalJson.object(scope.get("subject")).get("flowId"))
        .collect(java.util.stream.Collectors.joining("/"));
  }

  private static List<Map<String, Object>> reasonRows(AssessmentReport report) {
    return objects(report.data().get("findings")).stream()
        .map(
            row -> {
              Map<String, Object> projection = new TreeMap<>();
              for (String field :
                  List.of(
                      "kind",
                      "subject",
                      "asset",
                      "occurrence",
                      "path",
                      "handshakeRef",
                      "decision",
                      "technical")) {
                if (row.containsKey(field)) {
                  projection.put(field, row.get(field));
                }
              }
              return projection;
            })
        .toList();
  }

  private static List<Map<String, Object>> objects(Object value) {
    return CanonicalJson.array(Map.of("items", value), "items").stream()
        .map(CanonicalJson::object)
        .toList();
  }

  private static byte[] resource(String name) throws IOException {
    try (InputStream input =
        AssessmentContractTest.class.getResourceAsStream("/assessment/" + name)) {
      assertThat(input).as(name).isNotNull();
      return input.readAllBytes();
    }
  }
}
