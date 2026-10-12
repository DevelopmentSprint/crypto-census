package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.delivery.TransportConfiguration;
import com.developmentsprint.cryptocensus.delivery.preparation.ArtifactPreparer;
import com.developmentsprint.cryptocensus.delivery.preparation.PreparedArtifact;
import com.developmentsprint.cryptocensus.delivery.preparation.PreparedBundle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local planning never resolves credentials or opens sockets. */
public final class ProfilePlanner {
  private final ProfileReader reader;

  /** Uses an explicit adapter registry shared by both entry points. */
  public ProfilePlanner(ProfileReader reader) {
    this.reader = reader;
  }

  /** Prepares immutable artifacts and exclusively creates their sanitized plan. */
  public PlannedDelivery plan(Path profilePath) throws IOException {
    DeliveryProfile profile = reader.read(profilePath);
    if (Files.exists(profile.planPath())) {
      throw new IllegalArgumentException(
          "Plan already exists; publish it or select new output paths");
    }
    PreparedBundle bundle = new ArtifactPreparer().prepare(profile.preparation());
    PlannedDelivery plan = build(profile, bundle);
    ProfileFiles.create(
        profile.planPath(),
        JsonSupport.write(Map.of("planSha256", plan.planSha256(), "plan", plan.sanitizedPlan())));
    return plan;
  }

  /** Loads a sealed preparation and rejects changed profile, sources, capabilities or plan data. */
  public PlannedDelivery load(Path profilePath, String expectedPlanSha256) throws IOException {
    if (expectedPlanSha256 == null || !expectedPlanSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Expected plan SHA-256 is required");
    }
    DeliveryProfile profile = reader.read(profilePath);
    Map<String, Object> stored =
        JsonSupport.object(JsonSupport.parse(ProfileFiles.read(profile.planPath(), 65536)));
    JsonSupport.fields(stored, "planSha256", "plan");
    Map<String, Object> content = JsonSupport.object(stored.get("plan"));
    if (!ProfileFiles.sameHash(expectedPlanSha256, JsonSupport.string(stored, "planSha256"))
        || !ProfileFiles.sameHash(
            expectedPlanSha256, ProfileFiles.hash(JsonSupport.write(content)))) {
      throw new IllegalArgumentException("Plan digest mismatch");
    }
    if (!profile.sourceSha256().equals(JsonSupport.string(content, "profileSha256"))) {
      throw new IllegalArgumentException("Profile changed since planning");
    }
    Path manifest = Path.of(JsonSupport.string(content, "manifestPath"));
    if (!manifest
        .toAbsolutePath()
        .normalize()
        .startsWith(profile.preparation().outputDirectory().toAbsolutePath().normalize())) {
      throw new IllegalArgumentException("Plan manifest is outside its preparation directory");
    }
    PreparedBundle bundle =
        new ArtifactPreparer().load(manifest, JsonSupport.string(content, "manifestSha256"));
    PlannedDelivery result = build(profile, bundle);
    if (!ProfileFiles.sameHash(expectedPlanSha256, result.planSha256())) {
      throw new IllegalArgumentException("Evidence or capabilities changed since planning");
    }
    return result;
  }

  /** Rechecks all bytes bound into the plan immediately before transport. */
  public PlannedDelivery revalidate(PlannedDelivery plan) throws IOException {
    DeliveryProfile current = reader.read(plan.profile().source());
    if (!current.sourceSha256().equals(plan.profile().sourceSha256())) {
      throw new IllegalArgumentException("Profile changed since planning");
    }
    plan.bundle().verify();
    PlannedDelivery result = build(current, plan.bundle());
    if (!result.planSha256().equals(plan.planSha256())) {
      throw new IllegalArgumentException("Plan changed before execution");
    }
    return result;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "URI.toASCIIString defines the exact endpoint identity; no text normalization is"
              + " intended")
  private static PlannedDelivery build(DeliveryProfile profile, PreparedBundle bundle)
      throws IOException {
    CapabilityDeclaration capability = profile.adapter().capabilities();
    if (!capability.evidenceRevision().equals(profile.policy().capabilityRevision())) {
      throw new IllegalArgumentException("Capability revision requires explicit acceptance");
    }
    if (!capability.inputKinds().contains(bundle.publication().kind())
        || !capability.schemaVersions().contains("1.6")) {
      throw new IllegalArgumentException("Input kind or schema unsupported by adapter");
    }
    Set<String> losses = new LinkedHashSet<>(capability.lossCodes());
    losses.addAll(bundle.lossCodes());
    if (!profile.policy().acceptedLossCodes().equals(losses)) {
      throw new IllegalArgumentException(
          "Accepted loss codes must match the exact preparation and adapter losses");
    }
    for (String field : profile.policy().requiredFields()) {
      if (capability.semanticFields().get(field) != CapabilityDeclaration.FieldSupport.PRESERVED) {
        throw new IllegalArgumentException("Required semantic field is not preserved");
      }
    }
    if (profile.policy().requireRemoteRetention() && profile.retention() == null) {
      throw new IllegalArgumentException("Policy requires verified remote original retrieval");
    }
    if (profile.retention() != null
        && bundle.publication().kind()
            == com.developmentsprint.cryptocensus.delivery.preparation.InputKind
                .DERIVED_COMBINED_BOM) {
      throw new IllegalArgumentException(
          "Combined document requires separate original-retention configuration, not publication"
              + " readback");
    }
    if (profile.adapter().requiresOriginalRetention() && profile.retention() == null) {
      throw new IllegalArgumentException(
          "Vendor runtime view requires exact original HTTPS readback");
    }
    if (profile.options().authenticationScheme() == AdapterOptions.AuthenticationScheme.API_KEY) {
      if (profile.authentication().kind() == SecretReference.Kind.NONE) {
        throw new IllegalArgumentException("Vendor adapter requires authentication");
      }
    }
    if (!bundle.correlation().equals(profile.preparation().inputs().get(0).correlation())) {
      throw new IllegalArgumentException("Preparation correlation differs from profile");
    }
    if (bundle.sources().size() != profile.preparation().inputs().size()) {
      throw new IllegalArgumentException("Prepared source set differs from profile");
    }
    List<String> actualSources =
        bundle.sources().stream()
            .map(source -> source.kind() + ":" + source.sha256())
            .sorted()
            .toList();
    List<String> expectedSources =
        profile.preparation().inputs().stream()
            .map(source -> source.kind() + ":" + source.expectedSha256())
            .sorted()
            .toList();
    if (!actualSources.equals(expectedSources)) {
      throw new IllegalArgumentException("Prepared source identity differs from profile");
    }
    for (var input : profile.preparation().inputs()) {
      if (!ProfileFiles.hash(ProfileFiles.read(input.path(), 16 * 1024 * 1024))
          .equals(input.expectedSha256())) {
        throw new IllegalArgumentException("Source artifact changed since planning");
      }
    }
    profile
        .adapter()
        .validate(
            new AdapterContext(
                bundle, profile.endpoint(), profile.options(), profile.policy(), null));
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("schema", "census.delivery-plan/1");
    data.put("profileSha256", profile.sourceSha256());
    data.put(
        "caBundleSha256",
        profile.transport().caBundle() == null
            ? "JVM_DEFAULT_TRUST"
            : ProfileFiles.hash(
                TransportConfiguration.readCaBundle(profile.transport().caBundle())));
    data.put("adapter", profile.adapter().id());
    data.put("capabilityRevision", capability.evidenceRevision());
    data.put("spiVersion", "census.delivery-spi/1");
    data.put("serverVersion", capability.serverVersion());
    data.put(
        "supportedInputKinds", capability.inputKinds().stream().map(Enum::name).sorted().toList());
    data.put("supportedSchemas", capability.schemaVersions().stream().sorted().toList());
    Map<String, Object> fields = new LinkedHashMap<>();
    capability.semanticFields().forEach((name, support) -> fields.put(name, support.name()));
    data.put("semanticFields", fields);
    data.put(
        "endpointSha256",
        ProfileFiles.hash(profile.endpoint().toASCIIString().getBytes(StandardCharsets.UTF_8)));
    data.put("destinationId", profile.destinationId());
    data.put("manifestPath", bundle.manifest().toAbsolutePath().normalize().toString());
    data.put("manifestSha256", bundle.manifestSha256());
    data.put("publicationSha256", bundle.publication().sha256());
    data.put("publicationKind", bundle.publication().kind().name());
    List<Map<String, Object>> sources = new ArrayList<>();
    for (PreparedArtifact source : bundle.sources()) {
      sources.add(
          Map.of("id", source.id(), "kind", source.kind().name(), "sha256", source.sha256()));
    }
    data.put("sources", List.copyOf(sources));
    data.put(
        "mapping",
        Map.of(
            "application",
            bundle.correlation().application(),
            "module",
            bundle.correlation().module(),
            "version",
            bundle.correlation().version(),
            "runId",
            bundle.correlation().runId()));
    data.put("adapterOptions", profile.options().sanitized());
    data.put(
        "transport",
        Map.of(
            "proxyConfigured",
            profile.transport().proxyHost() != null,
            "proxyAuthenticated",
            profile.transport().proxyUsername() != null,
            "connectTimeoutMillis",
            10000,
            "requestTimeoutMillis",
            30000,
            "overallTimeoutMillis",
            60000));
    data.put("mutation", capability.mutation().name());
    data.put("lossCodes", losses.stream().sorted().toList());
    data.put("unresolvedAttribution", bundle.unresolvedCount());
    data.put("completion", profile.policy().completion().name());
    data.put("requiredFields", profile.policy().requiredFields().stream().sorted().toList());
    data.put("requireRemoteRetention", profile.policy().requireRemoteRetention());
    data.put("destinationByteRetention", capability.byteRetention().name());
    data.put("destinationLinkage", capability.linkage().name());
    data.put("nativeIdempotency", capability.nativeIdempotency());
    data.put("asynchronous", capability.asynchronous());
    data.put("remotePreflight", "NOT_PERFORMED");
    data.put(
        "originalRetention",
        profile.retention() == null ? "LOCAL_ONLY" : "REMOTE_READBACK_REQUIRED");
    data.put("connectTimeoutMillis", 10000);
    data.put("requestTimeoutMillis", 30000);
    data.put("overallTimeoutMillis", 60000);
    data.put("maximumExchanges", 64);
    return new PlannedDelivery(profile, bundle, ProfileFiles.hash(JsonSupport.write(data)), data);
  }
}
