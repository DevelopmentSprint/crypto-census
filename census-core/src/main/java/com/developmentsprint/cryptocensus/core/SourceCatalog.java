package com.developmentsprint.cryptocensus.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

  /** Snapshots entries and rejects duplicate location and loader-selector pairs. */
  public SourceCatalog {
    if (schemaVersion != VERSION) {
      throw new IllegalArgumentException("Unsupported source catalog schema");
    }
    entries = ContextModel.bounded(entries, MAX_ENTRIES, "source entries");
    Set<List<String>> locations = new HashSet<>();
    for (Entry entry : entries) {
      if (!locations.add(List.of(entry.locationUri(), entry.loaderName()))) {
        throw new IllegalArgumentException("Duplicate source location and loader selector");
      }
    }
  }

  /** Directory prefixes end in a slash; packaged artifacts match exactly, never by filename. */
  public record Entry(
      String locationUri,
      String artifactId,
      String applicationId,
      ContextModel.Origin origin,
      String deploymentId,
      String loaderName) {
    /** Compatibility constructor: omitted loader selector matches any loader. */
    public Entry(
        String locationUri,
        String artifactId,
        String applicationId,
        ContextModel.Origin origin,
        String deploymentId) {
      this(locationUri, artifactId, applicationId, origin, deploymentId, "");
    }

    /** Validates stable metadata and normalizes a local resource URI without opening it. */
    public Entry {
      locationUri = normalize(locationUri);
      artifactId = ContextModel.text(artifactId, "artifactId", 256, false);
      applicationId = ContextModel.text(applicationId, "applicationId", 256, false);
      Objects.requireNonNull(origin, "origin");
      deploymentId = ContextModel.text(deploymentId, "deploymentId", 128, false);
      loaderName = ContextModel.text(loaderName == null ? "" : loaderName, "loaderName", 256, true);
    }
  }

  /** Returns the most-specific declared ownership; unsupported or unmapped URLs remain unknown. */
  public Optional<Entry> resolve(String codeSourceUri) {
    return resolve(codeSourceUri, null);
  }

  /** Exact named-loader selectors precede wildcard mappings; unknown loaders use wildcards only. */
  public Optional<Entry> resolve(String codeSourceUri, String loaderName) {
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
      boolean matchesLoader = entry.loaderName().isEmpty() || entry.loaderName().equals(loaderName);
      boolean matchesLocation =
          location.equals(normalized)
              || (location.endsWith("/") && normalized.startsWith(location));
      if (matchesLoader && matchesLocation && preferred(entry, selected)) {
        selected = entry;
      }
    }
    return Optional.ofNullable(selected);
  }

  /** Canonical schema v1 encoding; input ordering does not change the content revision. */
  public byte[] encode() {
    List<Map<String, Object>> values = entries.stream().map(SourceCatalog::entryValue).toList();
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

  private static boolean preferred(Entry candidate, Entry selected) {
    if (selected == null) {
      return true;
    }
    boolean candidateExact = !candidate.loaderName().isEmpty();
    boolean selectedExact = !selected.loaderName().isEmpty();
    return candidateExact != selectedExact
        ? candidateExact
        : candidate.locationUri().length() > selected.locationUri().length();
  }

  private static Map<String, Object> entryValue(Entry entry) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("locationUri", entry.locationUri());
    value.put("artifactId", entry.artifactId());
    value.put("applicationId", entry.applicationId());
    value.put("origin", entry.origin().name());
    value.put("deploymentId", entry.deploymentId());
    if (!entry.loaderName().isEmpty()) {
      value.put("loaderName", entry.loaderName());
    }
    return value;
  }

  private static Entry entry(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    List<String> keys =
        new ArrayList<>(
            List.of("locationUri", "artifactId", "applicationId", "origin", "deploymentId"));
    if (object.containsKey("loaderName")) {
      keys.add("loaderName");
    }
    CanonicalJson.requireKeys(object, keys.toArray(String[]::new));
    return new Entry(
        CanonicalJson.text(object, "locationUri"),
        CanonicalJson.text(object, "artifactId"),
        CanonicalJson.text(object, "applicationId"),
        ContextModel.Origin.valueOf(CanonicalJson.text(object, "origin")),
        CanonicalJson.text(object, "deploymentId"),
        object.containsKey("loaderName") ? CanonicalJson.text(object, "loaderName") : "");
  }

  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "URI schemes are ASCII; lexical file path normalization does not establish authorization"
              + " or Unicode identity")
  private static String normalize(String value) {
    ContextModel.text(value, "source URI", 4096, false);
    URI uri = URI.create(value);
    if ("wsjar".equalsIgnoreCase(uri.getScheme())) {
      return normalizeArchive(uri);
    }
    if (uri.isOpaque()
        || uri.getPath() == null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getAuthority() != null) {
      throw new IllegalArgumentException("Source location must identify a local resource");
    }
    if ("vfs".equalsIgnoreCase(uri.getScheme())) {
      return "vfs:" + localPath(uri);
    }
    if (!"file".equalsIgnoreCase(uri.getScheme())) {
      throw new IllegalArgumentException("Unsupported local source scheme");
    }
    boolean directory = uri.getPath().endsWith("/");
    String normalized = Path.of(uri).toAbsolutePath().normalize().toUri().toASCIIString();
    if (directory) {
      return normalized.endsWith("/") ? normalized : normalized + "/";
    }
    return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
  }

  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Lexical URI normalization does not establish authorization or Unicode identity")
  private static String normalizeArchive(URI uri) {
    if (uri.getFragment() != null) {
      throw new IllegalArgumentException("Archive source has a fragment");
    }
    String nested = uri.getRawSchemeSpecificPart();
    int separator = nested.indexOf("!/");
    if (separator < 0) {
      throw new IllegalArgumentException("Archive source lacks an entry separator");
    }
    URI archive = URI.create(nested.substring(0, separator));
    if (!"file".equals(archive.getScheme())) {
      throw new IllegalArgumentException("Archive source must wrap a local file URI");
    }
    URI entry = URI.create("vfs:" + nested.substring(separator + 1));
    return "wsjar:" + normalize(archive.toASCIIString()) + "!" + localPath(entry);
  }

  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Lexical URI normalization does not establish authorization or Unicode identity")
  private static String localPath(URI uri) {
    if (uri.isOpaque()
        || uri.getAuthority() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getPath() == null
        || !uri.getPath().startsWith("/")
        || uri.getPath().startsWith("//")) {
      throw new IllegalArgumentException("Vendor source must use an absolute local path");
    }
    try {
      URI path = new URI(null, null, uri.getPath(), null).normalize();
      if (path.getPath().contains("/../") || path.getPath().endsWith("/..")) {
        throw new IllegalArgumentException("Vendor source escapes its root");
      }
      return path.toASCIIString();
    } catch (URISyntaxException invalid) {
      throw new IllegalArgumentException("Invalid local vendor path", invalid);
    }
  }
}
