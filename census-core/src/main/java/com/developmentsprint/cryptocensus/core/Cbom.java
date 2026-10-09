package com.developmentsprint.cryptocensus.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads and writes inventories as CycloneDX 1.6 Cryptography Bills of Materials.
 *
 * <p>Standard CycloneDX fields carry the information other tools understand ({@code
 * cryptoProperties}, {@code evidence.occurrences}). The full Crypto Census model is also stored as
 * {@code cryptocensus:*} properties so that CBOMs from several JVMs (for example forked test runs)
 * can be merged back into one inventory.
 */
public final class Cbom {

  /** Unguessable names, so nobody can pre-create a file we are about to write. */
  private static final SecureRandom RANDOM = new SecureRandom();

  public static final String SPEC_VERSION = "1.6";
  public static final String TOOL_NAME = "crypto-census";

  /**
   * CBOMs larger than this are refused rather than parsed. A CBOM at the agent's default caps (500
   * assets x 50 call sites) is about 5 MB, so this leaves ample room while keeping the input, its
   * decoded string and the parsed tree well inside a small Maven heap.
   */
  public static final long MAX_INPUT_BYTES = 16L * 1024 * 1024;

  static final String P = "cryptocensus:";

  /**
   * Marks a CBOM written by the agent; documents without it are not accepted as collection
   * evidence.
   */
  public static final String SCHEMA_PROPERTY = P + "schema";

  public static final String AGENT_SCHEMA = "census-agent/1";

  /** Health counters every agent-written CBOM must carry. Missing means unknown, never zero. */
  public static final Set<String> HEALTH_PROPERTIES =
      Set.of(
          P + "transformErrors",
          P + "recordingFailures",
          P + "dropped",
          P + "overflowedSites",
          P + "drainTimedOut",
          P + "untrackedTypes");

  private Cbom() {}

  /**
   * Descriptive metadata for the BOM.
   *
   * @param component name of the application or module being described
   * @param properties extra key/value pairs recorded as metadata properties (JVM version, ...)
   */
  public record Metadata(String component, Map<String, String> properties) {
    /** Copies {@code properties}, keeping their order. */
    public Metadata {
      properties =
          Collections.unmodifiableMap(
              new LinkedHashMap<>(properties == null ? Map.of() : properties));
    }
  }

  /**
   * A parsed CBOM: the reconstructed inventory plus its metadata properties (collection health,
   * policy version, JVM) so callers can judge whether the evidence is complete.
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "the inventory is handed over on purpose: callers merge parsed CBOMs into it")
  public record Document(Inventory inventory, Map<String, String> properties) {

    /** Copies {@code properties}, keeping their order. */
    public Document {
      properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    /**
     * True when this is agent-written evidence whose every health counter is present, a valid
     * non-negative number, and consistent with the inventory it describes.
     */
    public boolean hasAgentHealth() {
      try {
        validateAgentHealth();
        return true;
      } catch (IllegalArgumentException e) {
        return false;
      }
    }

    /**
     * Checks the health envelope: schema marker, every counter present and numeric, and counters
     * consistent with the inventory.
     *
     * @throws IllegalArgumentException describing the first problem with the health envelope
     */
    public void validateAgentHealth() {
      if (!AGENT_SCHEMA.equals(properties.get(SCHEMA_PROPERTY))) {
        throw new IllegalArgumentException(
            "not agent-written evidence ("
                + SCHEMA_PROPERTY
                + " is "
                + properties.get(SCHEMA_PROPERTY)
                + ")");
      }
      for (String p : HEALTH_PROPERTIES) {
        health(p);
      }
      if (health(P + "overflowedSites") != inventory.overflowedSites()) {
        throw new IllegalArgumentException(
            "declared overflowedSites "
                + health(P + "overflowedSites")
                + " does not match the "
                + inventory.overflowedSites()
                + " overflow occurrences present");
      }
      if (health(P + "dropped") != inventory.droppedObservations()) {
        throw new IllegalArgumentException("declared dropped count does not match the inventory");
      }
    }

    /** A health counter; throws if absent or malformed, because absent must not read as healthy. */
    public long health(String property) {
      String v = properties.get(property);
      if (v == null) {
        throw new IllegalArgumentException("Missing health property " + property);
      }
      return nonNegativeLong(v, property);
    }
  }

  // ---------------------------------------------------------------- write

  /**
   * Writes a snapshot of {@code inventory} to {@code file}; see {@link #write(List, long, Metadata,
   * Path)}.
   *
   * @param inventory the inventory to snapshot
   * @param metadata descriptive metadata for the BOM
   * @param file destination
   * @throws IOException if the file cannot be written
   */
  public static void write(Inventory inventory, Metadata metadata, Path file) throws IOException {
    write(inventory.entries(), inventory.droppedObservations(), metadata, file);
  }

  /**
   * Writes a snapshot atomically. The content is serialized first, then written through the single
   * handle that exclusively created a randomly named temporary file ({@code CREATE_NEW}, which
   * refuses to follow or reuse an existing entry, symlinks included), and finally renamed over the
   * destination. The destination is never opened, so a symlink or FIFO there is replaced, not
   * followed. If the file system cannot rename atomically, the write fails rather than degrading.
   *
   * <p>On POSIX file systems the file is created {@code 0600} and new directories {@code 0700}. On
   * other file systems (Windows) the file inherits the directory's ACL.
   */
  public static void write(
      List<Inventory.Entry> entries, long droppedObservations, Metadata metadata, Path file)
      throws IOException {
    Path target = file.toAbsolutePath();
    Path dir = target.getParent();
    if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(target + " is a directory");
    }
    byte[] content =
        toJson(entries, droppedObservations, metadata).getBytes(StandardCharsets.UTF_8);
    boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
    Files.createDirectories(
        dir,
        posix
            ? new FileAttribute<?>[] {
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            }
            : new FileAttribute<?>[0]);

    Path tmp =
        dir.resolve(".cbom-" + Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE) + ".tmp");
    FileAttribute<?>[] fileAttrs =
        posix
            ? new FileAttribute<?>[] {
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
            }
            : new FileAttribute<?>[0];
    writeVia(tmp, target, content, fileAttrs);
  }

  /** Package-private for tests: exclusive create of {@code tmp}, write, atomic rename. */
  static void writeVia(Path tmp, Path target, byte[] content, FileAttribute<?>... fileAttrs)
      throws IOException {
    boolean created = false;
    try {
      try (SeekableByteChannel out =
          Files.newByteChannel(
              tmp,
              EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
              fileAttrs)) {
        created = true; // from here on, tmp is ours to clean up
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
          out.write(buffer);
        }
      }
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      created = false; // renamed away
    } finally {
      // never delete an entry this call did not create (e.g. when CREATE_NEW lost a name collision)
      if (created) {
        Files.deleteIfExists(tmp);
      }
    }
  }

  /**
   * Serializes a snapshot of {@code inventory} as a CycloneDX 1.6 document.
   *
   * @param inventory the inventory to snapshot
   * @param metadata descriptive metadata for the BOM
   * @return the JSON document
   */
  public static String toJson(Inventory inventory, Metadata metadata) {
    return toJson(inventory.entries(), inventory.droppedObservations(), metadata);
  }

  /**
   * Serializes an inventory snapshot as a CycloneDX 1.6 document.
   *
   * @param entries the snapshot, as returned by {@link Inventory#entries()}
   * @param droppedObservations observations dropped by the inventory's caps
   * @param metadata descriptive metadata for the BOM
   * @return the JSON document
   */
  public static String toJson(
      List<Inventory.Entry> entries, long droppedObservations, Metadata metadata) {
    Map<String, Object> bom = new LinkedHashMap<>();
    bom.put("bomFormat", "CycloneDX");
    bom.put("specVersion", SPEC_VERSION);
    bom.put("serialNumber", "urn:uuid:" + UUID.randomUUID());
    bom.put("version", 1);

    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    meta.put(
        "tools",
        Map.of(
            "components",
            List.of(map("type", "application", "name", TOOL_NAME, "version", Version.get()))));
    if (metadata.component() != null) {
      meta.put("component", map("type", "application", "name", metadata.component()));
    }
    Map<String, String> metaProps = new LinkedHashMap<>();
    metaProps.put(P + "policy", Policy.ID + "/" + Policy.VERSION);
    metaProps.put(P + "dropped", String.valueOf(droppedObservations));
    long overflowed = 0;
    for (Inventory.Entry e : entries) {
      Long n = e.occurrences().get(Occurrence.OVERFLOW);
      if (n != null) {
        overflowed = Inventory.saturatingAdd(overflowed, n);
      }
    }
    metaProps.put(P + "overflowedSites", String.valueOf(overflowed));
    if (metadata.properties() != null) {
      metaProps.putAll(metadata.properties());
    }
    List<Object> props = new ArrayList<>();
    metaProps.forEach((k, v) -> props.add(map("name", k, "value", v)));
    meta.put("properties", props);
    bom.put("metadata", meta);

    List<Object> components = new ArrayList<>();
    for (Inventory.Entry entry : entries) {
      components.add(component(entry));
    }
    bom.put("components", components);
    return Json.write(bom);
  }

  private static Map<String, Object> component(Inventory.Entry entry) {
    CryptoAsset a = entry.asset();
    final Assessment verdict = entry.assessment();

    Map<String, Object> c = new LinkedHashMap<>();
    c.put("type", "cryptographic-asset");
    c.put("bom-ref", a.ref());
    c.put("name", a.displayName());

    Map<String, Object> crypto = new LinkedHashMap<>();
    if (a.primitive() == Primitive.TLS) {
      crypto.put("assetType", "protocol");
      Map<String, Object> protocol = new LinkedHashMap<>();
      protocol.put("type", "tls");
      protocol.put("version", tlsVersionNumber(a.protocolVersion()));
      protocol.put("cipherSuites", List.of(map("name", a.algorithm())));
      crypto.put("protocolProperties", protocol);
    } else {
      crypto.put("assetType", "algorithm");
      Map<String, Object> alg = new LinkedHashMap<>();
      alg.put("primitive", cdxPrimitive(a));
      String parameterSet = a.parameterSet();
      if (parameterSet != null) {
        alg.put("parameterSetIdentifier", parameterSet);
      }
      if (CryptoAsset.isExplicitMode(a.mode())) {
        alg.put("mode", cdxMode(a.mode()));
      }
      if (a.padding() != null) {
        alg.put("padding", cdxPadding(a.padding()));
      }
      // executionEnvironment deliberately omitted: we cannot tell software from HSM-backed
      // providers
      alg.put("cryptoFunctions", cdxFunctions(a));
      if (verdict.nistQuantumSecurityLevel() >= 0) {
        alg.put("nistQuantumSecurityLevel", verdict.nistQuantumSecurityLevel());
      }
      crypto.put("algorithmProperties", alg);
    }
    c.put("cryptoProperties", crypto);

    List<Object> occurrences = new ArrayList<>();
    entry
        .occurrences()
        .forEach(
            (o, count) -> {
              Map<String, Object> occ = new LinkedHashMap<>();
              occ.put("location", o.location());
              if (o.line() >= 0) {
                occ.put("line", o.line());
              }
              if (o.symbol() != null) {
                occ.put("symbol", o.symbol());
              }
              occ.put(
                  "additionalContext",
                  "count=" + count + (o.via() != null ? "; via=" + Escapes.encode(o.via()) : ""));
              occurrences.add(occ);
            });
    c.put("evidence", Map.of("occurrences", occurrences));

    List<Object> props = new ArrayList<>();
    prop(props, "risk", verdict.risk().label());
    prop(props, "reason", verdict.reason());
    prop(props, "cnsa2", String.valueOf(verdict.cnsa2()));
    prop(props, "count", String.valueOf(entry.total()));
    prop(props, "primitive", a.primitive().name());
    prop(props, "algorithm", a.algorithm());
    prop(props, "mode", a.mode());
    prop(props, "padding", a.padding());
    if (a.keySize() > 0) {
      prop(props, "keySize", String.valueOf(a.keySize()));
    }
    prop(props, "provider", a.provider());
    prop(props, "protocolVersion", a.protocolVersion());
    if (a.operation() != null) {
      prop(props, "operation", a.operation().name());
    }
    c.put("properties", props);
    return c;
  }

  private static void prop(List<Object> props, String name, String value) {
    if (value != null) {
      props.add(map("name", P + name, "value", value));
    }
  }

  // ---------------------------------------------------------------- read

  /**
   * Reads a CBOM file. Refuses symlinks, non-regular files and files over {@link #MAX_INPUT_BYTES}.
   */
  public static Document read(Path file) throws IOException {
    return parse(readBytes(file));
  }

  /**
   * Reads a file once, refusing symlinks and non-regular files and stopping after {@link
   * #MAX_INPUT_BYTES} regardless of what the file system reported earlier. Callers that hash their
   * input should hash exactly these bytes.
   */
  public static byte[] readBytes(Path file) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(file + " is not a regular file");
    }
    try (SeekableByteChannel in =
        Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
      long total = 0;
      int n;
      while ((n = in.read(buffer)) > 0) {
        total += n;
        if (total > MAX_INPUT_BYTES) {
          throw new IOException(
              file + " exceeds " + MAX_INPUT_BYTES + " bytes; refusing to parse it");
        }
        out.write(buffer.array(), 0, n);
        buffer.clear();
      }
      return out.toByteArray();
    }
  }

  /**
   * Parses UTF-8 CBOM bytes; see {@link #parse(String)}.
   *
   * @param json the document as UTF-8 bytes
   * @return the parsed document
   * @throws IllegalArgumentException on malformed JSON or an invalid Crypto Census component
   */
  public static Document parse(byte[] json) {
    return parse(new String(json, StandardCharsets.UTF_8));
  }

  /**
   * Rebuilds an inventory from a CBOM previously written by Crypto Census. Components without
   * {@code cryptocensus:*} properties (written by other tools) are ignored; a Crypto Census
   * component that is malformed is an error, never silently skipped.
   *
   * @throws IllegalArgumentException on malformed JSON or an invalid Crypto Census component
   */
  public static Document parse(String json) {
    Object root = Json.parse(json);
    Map<String, Object> bom = asMap(root, "document");
    if (!"CycloneDX".equals(bom.get("bomFormat"))) {
      throw new IllegalArgumentException("Not a CycloneDX document");
    }
    if (!SPEC_VERSION.equals(bom.get("specVersion"))) {
      throw new IllegalArgumentException(
          "Unsupported specVersion " + bom.get("specVersion") + " (expected " + SPEC_VERSION + ")");
    }

    Inventory inventory = new Inventory();
    Map<String, String> metaProps =
        properties(asMap(bom.getOrDefault("metadata", Map.of()), "metadata"), true);
    String dropped = metaProps.get(P + "dropped");
    if (dropped != null) {
      inventory.addDropped(nonNegativeLong(dropped, "dropped"));
    }

    // An agent-written document must consist solely of our own, fully described components;
    // a component that lost its properties must not silently disappear from the evidence.
    boolean strict = AGENT_SCHEMA.equals(metaProps.get(SCHEMA_PROPERTY));
    for (Object o : asList(bom.getOrDefault("components", List.of()), "components")) {
      Map<String, Object> c = asMap(o, "component");
      Map<String, String> all = properties(c, true);
      Map<String, String> props = new LinkedHashMap<>();
      all.forEach(
          (k, v) -> {
            if (k.startsWith(P)) {
              props.put(k.substring(P.length()), v);
            }
          });
      String ref = String.valueOf(c.get("bom-ref"));
      if (props.isEmpty()) {
        if (strict) {
          throw new IllegalArgumentException(
              "Component " + ref + " in an agent document lacks cryptocensus properties");
        }
        continue; // another tool's component
      }
      if (strict && !"cryptographic-asset".equals(c.get("type"))) {
        throw new IllegalArgumentException("Component " + ref + " has type " + c.get("type"));
      }
      if (!props.containsKey("primitive") || !props.containsKey("algorithm")) {
        throw new IllegalArgumentException(
            "Component " + ref + " lacks cryptocensus:primitive/algorithm");
      }

      CryptoAsset asset;
      try {
        asset =
            new CryptoAsset(
                Primitive.valueOf(props.get("primitive")),
                props.get("algorithm"),
                props.get("mode"),
                props.get("padding"),
                props.containsKey("keySize")
                    ? (int)
                        Math.min(
                            Integer.MAX_VALUE, nonNegativeLong(props.get("keySize"), "keySize"))
                    : 0,
                props.get("provider"),
                props.get("protocolVersion"),
                props.containsKey("operation") ? Operation.valueOf(props.get("operation")) : null);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Component " + ref + ": " + e.getMessage(), e);
      }
      // the standard fields other tools read must agree with the properties we enforce on
      Object name = c.get("name");
      if (name != null && !asset.displayName().equals(name)) {
        throw new IllegalArgumentException(
            "Component "
                + ref
                + ": name '"
                + name
                + "' contradicts its cryptocensus properties ("
                + asset.displayName()
                + ")");
      }
      if (strict) {
        // Every standard CycloneDX field other tools read (assetType, primitive, mode, padding,
        // parameterSetIdentifier, protocol version, cipher suite) must be exactly what these
        // properties produce. The policy-derived NIST level is excluded: it may differ by version.
        Map<String, Object> actual =
            new LinkedHashMap<>(
                asMap(c.getOrDefault("cryptoProperties", Map.of()), "cryptoProperties"));
        Map<String, Object> expected =
            new LinkedHashMap<>(
                asMap(
                    component(new Inventory.Entry(asset, new Policy().assess(asset), Map.of()))
                        .get("cryptoProperties"),
                    "cryptoProperties"));
        stripPolicyFields(actual);
        stripPolicyFields(expected);
        if (!expected.equals(actual)) {
          throw new IllegalArgumentException(
              "Component "
                  + ref
                  + ": cryptoProperties "
                  + actual
                  + " contradict its cryptocensus properties (expected "
                  + expected
                  + ")");
        }
      }

      Map<String, Object> evidence = asMap(c.getOrDefault("evidence", Map.of()), "evidence");
      List<Object> occurrences =
          asList(evidence.getOrDefault("occurrences", List.of()), "occurrences");
      if (occurrences.isEmpty()) {
        // keep the asset: an inventory must never lose a finding because evidence was stripped
        long count = props.containsKey("count") ? nonNegativeLong(props.get("count"), "count") : 0;
        inventory.add(asset, Occurrence.UNKNOWN, count);
      }
      for (Object occ : occurrences) {
        Map<String, Object> om = asMap(occ, "occurrence");
        long count = 1;
        String via = null;
        Object ctx = om.get("additionalContext");
        if (ctx != null) {
          for (String part :
              ctx.toString().split(";", -1)) { // -1: keep empty segments; skipped below
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) {
              continue;
            }
            if ("count".equals(kv[0])) {
              count = nonNegativeLong(kv[1], "count");
            }
            if ("via".equals(kv[0])) {
              via = Escapes.decode(kv[1]);
            }
          }
        }
        Object location = om.get("location");
        if (!(location instanceof String locationName)) {
          throw new IllegalArgumentException("Component " + ref + ": occurrence without location");
        }
        Object line = om.get("line");
        int lineNumber = -1;
        if (line != null) {
          if (!(line instanceof Long l) || l < 0 || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Component " + ref + ": invalid line " + line);
          }
          lineNumber = l.intValue();
        }
        Object symbol = om.get("symbol");
        inventory.add(
            asset,
            new Occurrence(
                locationName,
                symbol instanceof String symbolName ? symbolName : null,
                lineNumber,
                via),
            count);
      }
    }
    return new Document(inventory, Collections.unmodifiableMap(metaProps));
  }

  @SuppressWarnings("unchecked")
  private static void stripPolicyFields(Map<String, Object> cryptoProperties) {
    Object alg = cryptoProperties.get("algorithmProperties");
    if (alg instanceof Map) {
      Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) alg);
      copy.remove("nistQuantumSecurityLevel");
      cryptoProperties.put("algorithmProperties", copy);
    }
  }

  private static Map<String, String> properties(
      Map<String, Object> holder, boolean rejectDuplicates) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Object p : asList(holder.getOrDefault("properties", List.of()), "properties")) {
      Map<String, Object> pm = asMap(p, "property");
      Object name = pm.get("name");
      Object value = pm.get("value");
      if (!(name instanceof String propertyName) || !(value instanceof String propertyValue)) {
        throw new IllegalArgumentException("Property needs string name and value: " + pm);
      }
      if (result.put(propertyName, propertyValue) != null
          && rejectDuplicates
          && propertyName.startsWith(P)) {
        throw new IllegalArgumentException("Duplicate property " + name);
      }
    }
    return result;
  }

  static long nonNegativeLong(String value, String what) {
    try {
      long n = Long.parseLong(value.trim());
      if (n < 0) {
        throw new IllegalArgumentException("Negative " + what + ": " + value);
      }
      return n;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid " + what + ": " + value, e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object o, String what) {
    if (!(o instanceof Map)) {
      throw new IllegalArgumentException("Expected " + what + " to be an object");
    }
    return (Map<String, Object>) o;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object o, String what) {
    if (!(o instanceof List)) {
      throw new IllegalArgumentException("Expected " + what + " to be an array");
    }
    return (List<Object>) o;
  }

  // ---------------------------------------------------------------- CycloneDX enums

  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "classifies canonical JCA algorithm and mode names into a CycloneDX primitive label by"
              + " ASCII keyword; the label is descriptive and never feeds the risk assessment")
  static String cdxPrimitive(CryptoAsset a) {
    String alg = a.algorithm().toUpperCase(Locale.ROOT);
    switch (a.primitive()) {
      case SIGNATURE -> {
        return "signature";
      }
      case KEY_AGREEMENT -> {
        return "key-agree";
      }
      case KEM -> {
        return "kem";
      }
      case MAC -> {
        return "mac";
      }
      case MESSAGE_DIGEST -> {
        return alg.startsWith("SHAKE") ? "xof" : "hash";
      }
      case CIPHER, KEY_GENERATION -> {
        if ("RSA".equals(alg)) {
          return "pke";
        }
        if (alg.startsWith("HMAC")) {
          return "mac";
        }
        if ("RC4".equals(alg) || "ARCFOUR".equals(alg) || "CHACHA20".equals(alg)) {
          return "stream-cipher";
        }
        if ("CHACHA20-POLY1305".equals(alg)) {
          return "ae";
        }
        String mode = a.mode() == null ? "" : a.mode().toUpperCase(Locale.ROOT);
        if ("GCM".equals(mode) || "CCM".equals(mode) || "SIV".equals(mode)) {
          return "ae";
        }
        return "block-cipher";
      }
      case KEY_PAIR_GENERATION -> {
        if (alg.startsWith("ML-KEM")) {
          return "kem";
        }
        if (alg.startsWith("ML-DSA")
            || alg.startsWith("SLH-DSA")
            || "DSA".equals(alg)
            || "EDDSA".equals(alg)
            || "ED25519".equals(alg)
            || "ED448".equals(alg)) {
          return "signature";
        }
        if ("RSA".equals(alg) || "RSASSA-PSS".equals(alg)) {
          return "pke";
        }
        if ("DH".equals(alg)
            || "DIFFIEHELLMAN".equals(alg)
            || "XDH".equals(alg)
            || "X25519".equals(alg)
            || "X448".equals(alg)) {
          return "key-agree";
        }
        return "other";
      }
      default -> {
        return "unknown";
      }
    }
  }

  private static List<String> cdxFunctions(CryptoAsset a) {
    Operation op = a.operation();
    switch (a.primitive()) {
      case CIPHER -> {
        if (op == Operation.ENCRYPT || op == Operation.WRAP) {
          return List.of("encrypt");
        }
        if (op == Operation.DECRYPT || op == Operation.UNWRAP) {
          return List.of("decrypt");
        }
        return List.of("encrypt", "decrypt");
      }
      case SIGNATURE -> {
        if (op == Operation.SIGN) {
          return List.of("sign");
        }
        if (op == Operation.VERIFY) {
          return List.of("verify");
        }
        return List.of("sign", "verify");
      }
      case KEY_AGREEMENT -> {
        return List.of("keyderive");
      }
      case KEM -> {
        return List.of("encapsulate", "decapsulate");
      }
      case KEY_PAIR_GENERATION, KEY_GENERATION -> {
        return List.of("keygen");
      }
      case MESSAGE_DIGEST -> {
        return List.of("digest");
      }
      case MAC -> {
        return List.of("tag");
      }
      default -> {
        return List.of("unknown");
      }
    }
  }

  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "maps a JCA mode token onto a CycloneDX enum value by ASCII keyword; an exotic spelling"
              + " can at worst be exported as another mode label, never change an assessment")
  private static String cdxMode(String mode) {
    String m = mode.toLowerCase(Locale.ROOT);
    return switch (m) {
      case "cbc", "ecb", "ccm", "gcm", "cfb", "ofb", "ctr" -> m;
      default -> "other";
    };
  }

  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "maps a JCA padding token onto a CycloneDX enum value by ASCII prefix; an exotic"
              + " spelling can at worst be exported as another padding label")
  private static String cdxPadding(String padding) {
    String p = padding.toUpperCase(Locale.ROOT);
    if ("NOPADDING".equals(p)) {
      return "raw";
    }
    if (p.startsWith("PKCS5")) {
      return "pkcs5";
    }
    if (p.startsWith("PKCS7")) {
      return "pkcs7";
    }
    if (p.startsWith("PKCS1")) {
      return "pkcs1v15";
    }
    if (p.startsWith("OAEP")) {
      return "oaep";
    }
    return "other";
  }

  private static String tlsVersionNumber(String version) {
    if (version == null) {
      return "unknown";
    }
    return version.startsWith("TLSv") ? version.substring(4) : version;
  }

  private static Map<String, Object> map(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }
}
