package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.CryptoAsset;
import com.developmentsprint.cryptocensus.core.DocumentFiles;
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

/** Synthetic executable browser fixture; never consumes application or tracker data. */
public final class GuidedReportFixture {
  static final String HOSTILE =
      "</script><img src=https://invalid.example/x onerror=alert(1)> & \u2028";
  private static final String NONCE = "12345678-1234-1234-1234-123456789abc";
  private static final ContextModel.Subject SUBJECT = new ContextModel.Subject("demo", "annotated");

  private GuidedReportFixture() {}

  /** Generates a fixture, or applies a browser export and regenerates using the same inputs. */
  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    Files.createDirectories(directory);
    ContextDocument context = context();
    if (args.length > 1 && !args[1].equals("-")) {
      ContextChanges.ChangeSet changes = ContextChanges.parse(DocumentFiles.read(Path.of(args[1])));
      ContextChanges.Merge merged = ContextChanges.merge(context, changes);
      if (!merged.conflicts().isEmpty()) {
        throw new IllegalArgumentException("Fixture changes conflict");
      }
      context = merged.document();
    }
    AssessmentReport report =
        AssessmentEngine.assess(
            List.of(
                input(
                    args.length > 2 && args[2].equals("unresolved"),
                    args.length > 2 && args[2].equals("lost"),
                    args.length > 2
                        && (args[2].equals("ambiguous-deployment")
                            || args[2].equals("mixed-deployment")),
                    args.length > 2 && args[2].equals("mixed-deployment"))),
            context,
            PolicyProfile.defaults());
    report.write(directory.resolve("assessment.json"));
    GuidedReport.write(report, directory.resolve("assessment.html"));
    DocumentFiles.write(directory.resolve("context.json"), ContextCodec.encode(context));
  }

  static ContextDocument context() {
    ContextModel.Declaration declaration =
        new ContextModel.Declaration(
            SUBJECT,
            ContextModel.Field.CONFIDENTIALITY_YEARS,
            "5",
            new ContextModel.Source(
                ContextModel.SourceKind.USER,
                "prior-review",
                "b".repeat(64),
                "reviewer",
                "2026-10-26T00:00:00Z"));
    return new ContextDocument(1, List.of(), List.of(declaration), List.of(), List.of());
  }

  static AssessmentInputs.Input input() {
    return input(false, false, false, false);
  }

  private static AssessmentInputs.Input input(
      boolean unresolved, boolean lost, boolean ambiguous, boolean explicitPeer) {
    CryptoAsset asset =
        CryptoAsset.cipher(
            "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", 2048, "SunJCE", Operation.ENCRYPT);
    Occurrence site = new Occurrence("example.Archive", "save", 10, null);
    final ContextModel.Binding binding =
        new ContextModel.Binding(
            "example.Archive", "save", "()V", "example:archive", ContextModel.Origin.MAIN);
    Inventory inventory = new Inventory();
    inventory.add(asset, site, 3);
    Map<String, String> properties = new TreeMap<>();
    properties.put(EvidenceDocument.RUN_PROPERTY, NONCE);
    properties.put(Cbom.SCHEMA_PROPERTY, Cbom.AGENT_SCHEMA);
    Cbom.HEALTH_PROPERTIES.forEach(key -> properties.put(key, "0"));
    byte[] cbom =
        Cbom.toJson(inventory, new Cbom.Metadata("synthetic", properties))
            .getBytes(StandardCharsets.UTF_8);
    Map<String, Object> deterministic =
        new TreeMap<>(CanonicalJson.object(CanonicalJson.decode(cbom)));
    deterministic.put("serialNumber", "urn:uuid:" + NONCE);
    Map<String, Object> metadata =
        new TreeMap<>(CanonicalJson.object(deterministic.get("metadata")));
    metadata.put("timestamp", "2026-10-26T00:00:00Z");
    deterministic.put("metadata", metadata);
    cbom = CanonicalJson.encode(deterministic);
    ContextModel.Source source =
        new ContextModel.Source(
            ContextModel.SourceKind.ANNOTATION, "example.Annotated#save", "a".repeat(64), "", "");
    ContextModel.FlowDefinition flow =
        new ContextModel.FlowDefinition(
            SUBJECT,
            HOSTILE,
            List.of(
                new ContextModel.Declaration(
                    SUBJECT, ContextModel.Field.CONFIDENTIALITY_YEARS, "20", source),
                new ContextModel.Declaration(
                    SUBJECT, ContextModel.Field.CLASSIFICATION, "sensitive", source)),
            List.of());
    EvidenceDocument.Observation first =
        new EvidenceDocument.Observation(
            asset.ref(),
            site,
            new EvidenceDocument.ScopeContext(
                "identity",
                List.of(),
                lost
                    ? EvidenceDocument.Association.SCOPE_LOST
                    : EvidenceDocument.Association.NO_SCOPE),
            ambiguous ? 1 : 3,
            binding);
    EvidenceDocument.IdentityMapping identity =
        new EvidenceDocument.IdentityMapping(
            "identity",
            "deployment",
            "loader",
            "example:archive",
            unresolved ? "" : "demo",
            unresolved
                ? EvidenceDocument.IdentityStatus.UNRESOLVED
                : EvidenceDocument.IdentityStatus.RESOLVED,
            "source-catalog:GENERATED_BUILD");
    List<EvidenceDocument.Observation> observations =
        ambiguous
            ? List.of(
                first,
                new EvidenceDocument.Observation(
                    asset.ref(),
                    site,
                    new EvidenceDocument.ScopeContext(
                        "second", List.of(), EvidenceDocument.Association.NO_SCOPE),
                    2,
                    binding))
            : List.of(first);
    List<EvidenceDocument.IdentityMapping> identities =
        ambiguous
            ? List.of(
                identity,
                new EvidenceDocument.IdentityMapping(
                    "second",
                    "second-deployment",
                    "second-loader",
                    "example:archive",
                    "demo",
                    EvidenceDocument.IdentityStatus.RESOLVED,
                    explicitPeer
                        ? "source-catalog:EXPLICIT_DEPLOYMENT"
                        : "source-catalog:GENERATED_BUILD"))
            : List.of(identity);
    EvidenceDocument evidence =
        new EvidenceDocument(
            1,
            NONCE,
            CanonicalJson.hash(cbom),
            unresolved ? List.of() : List.of(new EvidenceDocument.DiscoveredFlow("identity", flow)),
            observations,
            List.of(),
            identities,
            List.of(
                new EvidenceDocument.Capability(
                    "annotation-scopes",
                    EvidenceDocument.CapabilityStatus.SUPPORTED,
                    "synchronous")),
            EvidenceDocument.zeroHealth());
    return new AssessmentInputs.Input(cbom, EvidenceCodec.encode(evidence), "synthetic", "test");
  }
}
