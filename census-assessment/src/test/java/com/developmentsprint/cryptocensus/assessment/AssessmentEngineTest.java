package com.developmentsprint.cryptocensus.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.EvidenceCodec;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Occurrence;
import com.developmentsprint.cryptocensus.core.Operation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssessmentEngineTest {
  private static final ContextModel.Subject SUBJECT = new ContextModel.Subject("app", "archive");
  private static final ContextModel.Binding FRAME =
      new ContextModel.Binding(
          "example.Archive", "save", "()V", "example:archive", ContextModel.Origin.MAIN);
  private static final ContextDocument EMPTY =
      new ContextDocument(1, List.of(), List.of(), List.of(), List.of());

  private static ContextModel.Declaration declaration(ContextModel.Field field, String value) {
    return new ContextModel.Declaration(
        SUBJECT,
        field,
        value,
        new ContextModel.Source(
            ContextModel.SourceKind.ANNOTATION, "example.Archive#save", "a".repeat(64), "", ""));
  }

  private static AssessmentInputs.Input input(String nonce, String identity, String years) {
    return input(nonce, identity, years, value -> value);
  }

  private static AssessmentInputs.Input input(
      String nonce,
      String identity,
      String years,
      java.util.function.UnaryOperator<EvidenceDocument> edit) {
    CryptoAsset asset =
        CryptoAsset.cipher(
            "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", 2048, "SunJCE", Operation.ENCRYPT);
    Occurrence site = new Occurrence("example.Archive", "save", 10, null);
    Inventory inventory = new Inventory();
    inventory.add(asset, site, 3);
    Map<String, String> properties = new TreeMap<>();
    properties.put(EvidenceDocument.RUN_PROPERTY, nonce);
    properties.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    Cbom.HEALTH_PROPERTIES.forEach(key -> properties.put(key, "0"));
    byte[] cbom =
        Cbom.toJson(inventory, new Cbom.Metadata("display", properties))
            .getBytes(StandardCharsets.UTF_8);
    List<ContextModel.Declaration> declarations =
        years == null
            ? List.of()
            : List.of(
                declaration(ContextModel.Field.CLASSIFICATION, "sensitive"),
                declaration(ContextModel.Field.CONFIDENTIALITY_YEARS, years));
    EvidenceDocument.ScopeContext scope =
        new EvidenceDocument.ScopeContext(
            identity,
            List.of(new EvidenceDocument.ScopeRef(identity, SUBJECT)),
            EvidenceDocument.Association.SYNCHRONOUS_SCOPE);
    EvidenceDocument evidence =
        new EvidenceDocument(
            1,
            nonce,
            CanonicalJson.hash(cbom),
            List.of(
                new EvidenceDocument.DiscoveredFlow(
                    identity,
                    new ContextModel.FlowDefinition(
                        SUBJECT, "Archive", declarations, List.of(FRAME)))),
            List.of(new EvidenceDocument.Observation(asset.ref(), site, scope, 3, FRAME)),
            List.of(),
            List.of(
                new EvidenceDocument.IdentityMapping(
                    identity,
                    "deployment-" + identity,
                    "loader-" + identity,
                    "example:archive",
                    "app",
                    EvidenceDocument.IdentityStatus.RESOLVED,
                    "source-catalog:EXPLICIT_DEPLOYMENT")),
            List.of(
                new EvidenceDocument.Capability(
                    "annotation-scopes",
                    EvidenceDocument.CapabilityStatus.SUPPORTED,
                    "synchronous")),
            EvidenceDocument.zeroHealth());
    return new AssessmentInputs.Input(
        cbom, EvidenceCodec.encode(edit.apply(evidence)), "test", "test");
  }

  private static List<Map<String, Object>> objects(Object value) {
    return CanonicalJson.array(Map.of("items", value), "items").stream()
        .map(CanonicalJson::object)
        .toList();
  }

  @Test
  void annotationHealthDoesNotOverrideTlsButRealTransformAndRecordingFailuresStillDo() {
    for (var counter :
        List.of(
            EvidenceDocument.Counter.ANNOTATION_ERRORS,
            EvidenceDocument.Counter.TRANSFORM_ERRORS,
            EvidenceDocument.Counter.RECORDING_FAILURES)) {
      var input =
          input(
              "12345678-1234-1234-1234-123456789abc",
              "one",
              "20",
              value -> {
                var tls = withTls(value, true, false);
                var health = new java.util.EnumMap<>(tls.health());
                health.put(counter, 1L);
                return new EvidenceDocument(
                    1,
                    tls.runId(),
                    tls.cbomHash(),
                    tls.flows(),
                    tls.observations(),
                    tls.handshakes(),
                    tls.identities(),
                    tls.capabilities(),
                    health);
              });
      var findings =
          objects(
              AssessmentEngine.assess(List.of(input), EMPTY, PolicyProfile.defaults())
                  .data()
                  .get("findings"));
      assertThat(findings)
          .filteredOn(finding -> "TLS_CONFIDENTIALITY".equals(finding.get("kind")))
          .singleElement()
          .satisfies(
              finding ->
                  assertThat(CanonicalJson.object(finding.get("technical")))
                      .containsEntry(
                          "quantumExposure",
                          counter == EvidenceDocument.Counter.ANNOTATION_ERRORS
                              ? "RESISTANT"
                              : "NOT_ESTABLISHED"));
    }
  }

  @Test
  void ambiguousTlsRetainsNegotiationButRequiresOwnershipContext() {
    var input =
        input(
            "12345678-1234-1234-1234-123456789abc",
            "one",
            "20",
            value ->
                twoDeployments(withTls(value, true, false), "source-catalog", "source-catalog"));
    var report = AssessmentEngine.assess(List.of(input), EMPTY, PolicyProfile.defaults()).data();
    assertThat(objects(report.get("findings")))
        .filteredOn(f -> !"PRIMITIVE".equals(f.get("kind")))
        .hasSize(3)
        .allSatisfy(
            finding -> {
              assertThat(
                      CanonicalJson.array(finding, "gaps").stream().map(Object::toString).toList())
                  .contains(DeploymentOwnership.GAP);
              assertThat(CanonicalJson.object(finding.get("decision")))
                  .containsEntry("displayPriority", "NEEDS_CONTEXT");
              if ("TLS_CONFIDENTIALITY".equals(finding.get("kind"))) {
                assertThat(CanonicalJson.object(finding.get("technical")))
                    .containsEntry("quantumExposure", "RESISTANT");
              }
            });
  }

  @Test
  void repeatedUnqualifiedIdentityAcrossRunsIsNotDeploymentAmbiguity() {
    var first =
        input(
            "12345678-1234-1234-1234-123456789abc",
            "one",
            "20",
            value -> provenance(value, "source-catalog"));
    var second =
        input(
            "22345678-1234-1234-1234-123456789abc",
            "two",
            "20",
            value -> provenance(value, "source-catalog"));
    var report =
        AssessmentEngine.assess(List.of(first, second), EMPTY, PolicyProfile.defaults()).data();
    assertThat(CanonicalJson.array(report, "gaps").stream().map(Object::toString).toList())
        .doesNotContain(DeploymentOwnership.GAP);
    assertThat(objects(report.get("findings")))
        .allSatisfy(
            finding ->
                assertThat(CanonicalJson.object(finding.get("decision")))
                    .containsEntry("displayPriority", "URGENT_PLANNING"));
  }

  private static EvidenceDocument provenance(EvidenceDocument value, String provenance) {
    var identity = value.identities().get(0);
    return new EvidenceDocument(
        1,
        value.runId(),
        value.cbomHash(),
        value.flows(),
        value.observations(),
        value.handshakes(),
        List.of(
            new EvidenceDocument.IdentityMapping(
                identity.id(),
                identity.deploymentId(),
                identity.loaderId(),
                identity.sourceArtifact(),
                identity.applicationId(),
                identity.status(),
                provenance)),
        value.capabilities(),
        value.health());
  }

  @Test
  void unqualifiedLoadersStaySeparateAndCannotBorrowSavedContext() {
    for (String provenance :
        List.of(
            "source-catalog",
            "source-catalog:GENERATED_BUILD",
            "source-catalog:UNSPECIFIED",
            "explicit-application-id")) {
      var input =
          input(
              "12345678-1234-1234-1234-123456789abc",
              "one",
              "20",
              value -> twoDeployments(value, provenance, provenance));
      var saved =
          new ContextDocument(
              1,
              List.of(),
              List.of(
                  declaration(ContextModel.Field.CLASSIFICATION, "restricted"),
                  declaration(ContextModel.Field.CONFIDENTIALITY_YEARS, "40")),
              List.of(),
              List.of());
      var report = AssessmentEngine.assess(List.of(input), saved, PolicyProfile.defaults()).data();
      assertThat(CanonicalJson.array(report, "gaps").stream().map(Object::toString).toList())
          .contains(DeploymentOwnership.GAP);
      assertThat(objects(report.get("findings")))
          .hasSize(2)
          .allSatisfy(
              finding -> {
                assertThat(CanonicalJson.object(finding.get("decision")))
                    .containsEntry("displayPriority", "NEEDS_CONTEXT");
                assertThat(CanonicalJson.object(finding.get("technical")))
                    .containsEntry("quantumExposure", "VULNERABLE");
              });
      var flow = objects(report.get("flows")).get(0);
      assertThat(flow).containsEntry("inclusiveCount", 3L);
      assertThat(objects(flow.get("instances")))
          .hasSize(2)
          .allSatisfy(
              instance -> {
                assertThat(objects(instance.get("fields"))).isEmpty();
                assertThat(
                        CanonicalJson.array(instance, "gaps").stream()
                            .map(Object::toString)
                            .toList())
                    .contains(DeploymentOwnership.GAP);
              });
      assertThat(objects(objects(report.get("runs")).get(0).get("flows"))).hasSize(2);
    }
  }

  @Test
  void explicitDeploymentKeepsItsOwnPriorityBesideAnUnqualifiedPeer() {
    var input =
        input(
            "12345678-1234-1234-1234-123456789abc",
            "one",
            "20",
            value ->
                twoDeployments(
                    value, "source-catalog:EXPLICIT_DEPLOYMENT", "source-catalog:GENERATED_BUILD"));
    var findings =
        objects(
            AssessmentEngine.assess(List.of(input), EMPTY, PolicyProfile.defaults())
                .data()
                .get("findings"));
    assertThat(findings)
        .allSatisfy(
            finding -> {
              assertThat(CanonicalJson.object(finding.get("decision")))
                  .containsEntry(
                      "displayPriority",
                      "one".equals(finding.get("identityId"))
                          ? "URGENT_PLANNING"
                          : "NEEDS_CONTEXT");
              assertThat(CanonicalJson.array(finding, "gaps").contains(DeploymentOwnership.GAP))
                  .isEqualTo(!"one".equals(finding.get("identityId")));
            });
  }

  @Test
  void explicitDeploymentsWithSameSubjectRetainIndependentDeclarations() {
    var input =
        input(
            "12345678-1234-1234-1234-123456789abc",
            "one",
            "20",
            value ->
                twoDeployments(
                    value,
                    "source-catalog:EXPLICIT_DEPLOYMENT",
                    "source-catalog:EXPLICIT_DEPLOYMENT"));
    var report = AssessmentEngine.assess(List.of(input), EMPTY, PolicyProfile.defaults()).data();
    assertThat(CanonicalJson.array(report, "gaps").stream().map(Object::toString).toList())
        .doesNotContain(DeploymentOwnership.GAP);
    assertThat(objects(objects(report.get("flows")).get(0).get("instances")))
        .hasSize(2)
        .allSatisfy(instance -> assertThat(objects(instance.get("fields"))).isNotEmpty());
  }

  private static EvidenceDocument twoDeployments(
      EvidenceDocument base, String first, String second) {
    var original = base.observations().get(0);
    var scope =
        new EvidenceDocument.ScopeContext(
            "two",
            List.of(new EvidenceDocument.ScopeRef("two", SUBJECT)),
            EvidenceDocument.Association.SYNCHRONOUS_SCOPE);
    return new EvidenceDocument(
        1,
        base.runId(),
        base.cbomHash(),
        List.of(
            base.flows().get(0),
            new EvidenceDocument.DiscoveredFlow(
                "two",
                new ContextModel.FlowDefinition(
                    SUBJECT,
                    "Archive",
                    List.of(declaration(ContextModel.Field.CLASSIFICATION, "public")),
                    List.of(FRAME)))),
        List.of(
            new EvidenceDocument.Observation(
                original.assetRef(), original.occurrence(), original.context(), 1, FRAME),
            new EvidenceDocument.Observation(
                original.assetRef(), original.occurrence(), scope, 2, FRAME)),
        base.handshakes(),
        List.of(
            new EvidenceDocument.IdentityMapping(
                "one",
                "maven:example:archive",
                "loader-one",
                "example:archive",
                "app",
                EvidenceDocument.IdentityStatus.RESOLVED,
                first),
            new EvidenceDocument.IdentityMapping(
                "two",
                "maven:example:archive",
                "loader-two",
                "example:archive",
                "app",
                EvidenceDocument.IdentityStatus.RESOLVED,
                second)),
        base.capabilities(),
        base.health());
  }

  @Test
  void deploymentContextNeverBorrowsObservedDeclarationsFromAnotherRun() {
    AssessmentInputs.Input first = input("12345678-1234-1234-1234-123456789abc", "one", "20");
    AssessmentInputs.Input second = input("22345678-1234-1234-1234-123456789abc", "two", null);
    AssessmentReport report =
        AssessmentEngine.assess(List.of(first, second), EMPTY, PolicyProfile.defaults());
    List<Map<String, Object>> findings = objects(report.data().get("findings"));
    assertThat(findings).hasSize(2);
    Map<String, String> priorities = new TreeMap<>();
    findings.forEach(
        finding ->
            priorities.put(
                (String) finding.get("identityId"),
                (String) CanonicalJson.object(finding.get("decision")).get("displayPriority")));
    assertThat(priorities)
        .containsEntry("one", "URGENT_PLANNING")
        .containsEntry("two", "NEEDS_CONTEXT");
    Map<String, Object> flow = objects(report.data().get("flows")).get(0);
    assertThat(flow)
        .containsEntry("inclusiveCount", 6L)
        .containsEntry("directCount", 6L)
        .containsEntry("priority", "URGENT_PLANNING");
    assertThat(objects(flow.get("instances"))).hasSize(2);
    assertThat(report.bytes())
        .isEqualTo(
            AssessmentEngine.assess(List.of(second, first, first), EMPTY, PolicyProfile.defaults())
                .bytes());
  }

  @Test
  void differentDeploymentRequirementsDoNotCreateFalseConflict() {
    AssessmentInputs.Input first = input("12345678-1234-1234-1234-123456789abc", "one", "20");
    AssessmentInputs.Input second = input("22345678-1234-1234-1234-123456789abc", "two", "1");
    List<Map<String, Object>> findings =
        objects(
            AssessmentEngine.assess(List.of(first, second), EMPTY, PolicyProfile.defaults())
                .data()
                .get("findings"));
    assertThat(findings)
        .allSatisfy(
            finding ->
                assertThat(CanonicalJson.object(finding.get("decision")).get("displayPriority"))
                    .isIn("URGENT_PLANNING", "PLANNED_MIGRATION"));
  }

  @Test
  void unverifiedReadinessRemainsUnassessedAndIncomplete() {
    AssessmentInputs.Input input = input("12345678-1234-1234-1234-123456789abc", "one", "20");
    ContextDocument context =
        new ContextDocument(
            1,
            List.of(),
            List.of(),
            List.of(),
            List.of(
                new ContextDocument.Readiness(
                    SUBJECT,
                    ContextDocument.ReadinessState.VALIDATED_IN_TESTED_SCOPE,
                    "Claim only",
                    new ContextModel.Source(
                        ContextModel.SourceKind.USER,
                        "review",
                        "b".repeat(64),
                        "reviewer",
                        "2026-10-26T00:00:00Z"),
                    List.of("unverified-reference"))));
    AssessmentReport report =
        AssessmentEngine.assess(List.of(input), context, PolicyProfile.defaults());
    Map<String, Object> flow = objects(report.data().get("flows")).get(0);
    assertThat(flow).containsEntry("readinessIncomplete", true).containsEntry("incomplete", true);
    assertThat(CanonicalJson.object(flow.get("readiness")))
        .containsEntry("effectiveState", "UNASSESSED");
    assertThat(report.data()).containsEntry("incomplete", true);
  }

  private static EvidenceDocument withTls(
      EvidenceDocument base, boolean supported, boolean failed) {
    EvidenceDocument.Handshake handshake =
        new EvidenceDocument.Handshake(
            "connection",
            1,
            EvidenceDocument.Transport.SOCKET,
            EvidenceDocument.PeerRole.CLIENT,
            failed ? EvidenceDocument.Outcome.FAILED : EvidenceDocument.Outcome.COMPLETED,
            new EvidenceDocument.Negotiation(
                "TLSv1.3",
                "TLS_AES_256_GCM_SHA384",
                4588,
                "X25519MLKEM768",
                "",
                "ecdsa_secp256r1_sha256"),
            new EvidenceDocument.Resumption(
                EvidenceDocument.Flag.NO, EvidenceDocument.Flag.YES, null),
            List.of(),
            "sunjsse-structural-v1",
            List.of(),
            base.observations().get(0).context());
    java.util.ArrayList<EvidenceDocument.Capability> capabilities =
        new java.util.ArrayList<>(base.capabilities());
    capabilities.add(
        new EvidenceDocument.Capability(
            "tls-sunjsse-1.3-correlation",
            supported
                ? EvidenceDocument.CapabilityStatus.SUPPORTED
                : EvidenceDocument.CapabilityStatus.PARTIAL,
            "test"));
    return new EvidenceDocument(
        1,
        base.runId(),
        base.cbomHash(),
        base.flows(),
        base.observations(),
        List.of(handshake),
        base.identities(),
        capabilities,
        base.health());
  }

  @Test
  void hybridTlsKeepsAuthenticationSeparateAndDoesNotInflateOperationCounts() {
    AssessmentInputs.Input input =
        input(
            "12345678-1234-1234-1234-123456789abc",
            "one",
            "20",
            value -> withTls(value, true, false));
    AssessmentReport report =
        AssessmentEngine.assess(List.of(input), EMPTY, PolicyProfile.defaults());
    List<Map<String, Object>> findings = objects(report.data().get("findings"));
    assertThat(findings).hasSize(4);
    Map<String, String> exposures = new TreeMap<>();
    findings.stream()
        .filter(value -> !"PRIMITIVE".equals(value.get("kind")))
        .forEach(
            value ->
                exposures.put(
                    (String) value.get("kind"),
                    (String) CanonicalJson.object(value.get("technical")).get("quantumExposure")));
    assertThat(exposures)
        .containsEntry("TLS_CONFIDENTIALITY", "RESISTANT")
        .containsEntry("TLS_PEER_AUTHENTICATION", "VULNERABLE")
        .containsEntry("TLS_LOCAL_AUTHENTICATION", "NOT_ESTABLISHED");
    Map<String, Object> flow = objects(report.data().get("flows")).get(0);
    assertThat(flow)
        .containsEntry("inclusiveCount", 3L)
        .containsEntry("directCount", 3L)
        .containsEntry("handshakeAttempts", 1L);
  }

  @Test
  void incompleteTlsCapabilityOrFailedHandshakeNeverClaimsResistance() {
    for (boolean failed : List.of(false, true)) {
      AssessmentInputs.Input input =
          input(
              "12345678-1234-1234-1234-123456789abc",
              "one",
              "20",
              value -> withTls(value, failed, failed));
      AssessmentReport report =
          AssessmentEngine.assess(List.of(input), EMPTY, PolicyProfile.defaults());
      objects(report.data().get("findings")).stream()
          .filter(value -> !"PRIMITIVE".equals(value.get("kind")))
          .forEach(
              value ->
                  assertThat(CanonicalJson.object(value.get("technical")))
                      .containsEntry("quantumExposure", "NOT_ESTABLISHED"));
      assertThat(report.data()).containsEntry("incomplete", true);
    }
  }

  @Test
  void unobservedDeploymentRetainsItsDeclaredRequirements() {
    AssessmentInputs.Input input =
        input(
            "12345678-1234-1234-1234-123456789abc",
            "one",
            "20",
            base -> {
              List<EvidenceDocument.DiscoveredFlow> flows = new java.util.ArrayList<>(base.flows());
              flows.add(
                  new EvidenceDocument.DiscoveredFlow("two", base.flows().get(0).definition()));
              List<EvidenceDocument.IdentityMapping> identities =
                  new java.util.ArrayList<>(base.identities());
              identities.add(
                  new EvidenceDocument.IdentityMapping(
                      "two",
                      "deployment-two",
                      "loader-two",
                      "example:archive",
                      "app",
                      EvidenceDocument.IdentityStatus.RESOLVED,
                      "source-catalog:EXPLICIT_DEPLOYMENT"));
              return new EvidenceDocument(
                  1,
                  base.runId(),
                  base.cbomHash(),
                  flows,
                  base.observations(),
                  base.handshakes(),
                  identities,
                  base.capabilities(),
                  base.health());
            });
    Map<String, Object> flow =
        objects(
                AssessmentEngine.assess(List.of(input), EMPTY, PolicyProfile.defaults())
                    .data()
                    .get("flows"))
            .get(0);
    Map<String, Object> unobserved =
        objects(flow.get("instances")).stream()
            .filter(value -> "two".equals(value.get("identityId")))
            .findFirst()
            .orElseThrow();
    assertThat(unobserved)
        .containsEntry("observed", false)
        .containsEntry("priority", "NEEDS_CONTEXT")
        .containsEntry("coverageIncomplete", true)
        .containsEntry("inclusiveCount", 0L);
    assertThat(objects(unobserved.get("fields")))
        .anySatisfy(value -> assertThat(value).containsEntry("value", "20"));
  }

  @Test
  void emptyReportIsExplicitlyIncompleteAndArtifactOwnsItsBytes(@TempDir Path directory)
      throws Exception {
    AssessmentReport report = AssessmentEngine.assess(List.of(), EMPTY, PolicyProfile.defaults());
    assertThat(report.data()).containsEntry("incomplete", true);
    assertThat(CanonicalJson.array(report.data(), "gaps").stream().map(String.class::cast).toList())
        .contains("NO_INPUT_RUNS", "NO_CRYPTO_OBSERVATION");
    byte[] copy = report.bytes();
    copy[0] = '!';
    assertThat(report.bytes()[0]).isEqualTo((byte) '{');
    assertThatThrownBy(() -> report.data().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    Path output = directory.resolve("assessment.json");
    report.write(output);
    assertThat(Files.readAllBytes(output)).isEqualTo(report.bytes());
    assertThat(report.revision()).isEqualTo(CanonicalJson.hash(report.bytes()));
  }
}
