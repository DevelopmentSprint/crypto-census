package com.developmentsprint.cryptocensus.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Bounded source-artifact ownership map supplied by the build or explicitly by an operator. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "ContextModel.bounded snapshots lists with List.copyOf; all elements are immutable values")
public record SourceCatalog(int schemaVersion, List<Entry> entries) {
  public static final int VERSION = 1;
  public static final int MAX_ENTRIES = 4096;

  /** Snapshots entries and rejects duplicate locations with conflicting ownership. */
  public SourceCatalog {
    if (schemaVersion != VERSION) {
      throw new IllegalArgumentException("Unsupported source catalog schema");
    }
    entries = ContextModel.bounded(entries, MAX_ENTRIES, "source entries");
    Set<String> locations = new HashSet<>();
    for (Entry entry : entries) {
      if (!locations.add(entry.locationUri())) {
        throw new IllegalArgumentException("Duplicate source location");
      }
    }
  }

  /** Directory prefixes end in a slash; packaged artifacts match exactly, never by filename. */
  public record Entry(
      String locationUri,
      String artifactId,
      String applicationId,
      ContextModel.Origin origin,
      String deploymentId) {
    /** Validates stable metadata and normalizes a local file URI without following the file. */
    public Entry {
      locationUri = normalize(locationUri);
      artifactId = ContextModel.text(artifactId, "artifactId", 256, false);
      applicationId = ContextModel.text(applicationId, "applicationId", 256, false);
      Objects.requireNonNull(origin, "origin");
      deploymentId = ContextModel.text(deploymentId, "deploymentId", 128, false);
    }
  }

  /** Returns the most-specific declared ownership; unsupported or unmapped URLs remain unknown. */
  public Optional<Entry> resolve(String codeSourceUri) {
    if (codeSourceUri == null) {
      return Optional.empty();
    }
    final String normalized;
    try {
      normalized = normalize(codeSourceUri);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    Entry selected = null;
    for (Entry entry : entries) {
      String location = entry.locationUri();
      if ((location.equals(normalized)
              || (location.endsWith("/") && normalized.startsWith(location)))
          && (selected == null || location.length() > selected.locationUri().length())) {
        selected = entry;
      }
    }
    return Optional.ofNullable(selected);
  }

  /** Canonical schema v1 encoding; input ordering does not change the content revision. */
  public byte[] encode() {
    List<Map<String, Object>> values =
        entries.stream()
            .map(
                entry ->
                    Map.<String, Object>of(
                        "locationUri",
                        entry.locationUri(),
                        "artifactId",
                        entry.artifactId(),
                        "applicationId",
                        entry.applicationId(),
                        "origin",
                        entry.origin().name(),
                        "deploymentId",
                        entry.deploymentId()))
            .toList();
    return CanonicalJson.encode(
        Map.of("schemaVersion", VERSION, "entries", ContextJson.ordered(values)));
  }

  /** Decodes a source catalog without opening or fetching any named artifact. */
  public static SourceCatalog parse(byte[] bytes) {
    Map<String, Object> value = CanonicalJson.object(CanonicalJson.decode(bytes));
    CanonicalJson.requireKeys(value, "schemaVersion", "entries");
    if (CanonicalJson.integer(value, "schemaVersion") != VERSION) {
      throw new IllegalArgumentException("Unsupported source catalog schema");
    }
    return new SourceCatalog(
        VERSION, CanonicalJson.array(value, "entries").stream().map(SourceCatalog::entry).toList());
  }

  private static Entry entry(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(
        object, "locationUri", "artifactId", "applicationId", "origin", "deploymentId");
    return new Entry(
        CanonicalJson.text(object, "locationUri"),
        CanonicalJson.text(object, "artifactId"),
        CanonicalJson.text(object, "applicationId"),
        ContextModel.Origin.valueOf(CanonicalJson.text(object, "origin")),
        CanonicalJson.text(object, "deploymentId"));
  }

  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "URI schemes are ASCII; lexical file path normalization does not establish authorization"
              + " or Unicode identity")
  private static String normalize(String value) {
    ContextModel.text(value, "source URI", 4096, false);
    URI uri = URI.create(value);
    if (uri.isOpaque()
        || uri.getPath() == null
        || !"file".equalsIgnoreCase(uri.getScheme())
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException("Source location must be a local file URI");
    }
    boolean directory = uri.getPath().endsWith("/");
    String normalized = Path.of(uri).toAbsolutePath().normalize().toUri().toASCIIString();
    if (directory) {
      return normalized.endsWith("/") ? normalized : normalized + "/";
    }
    return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }
}
