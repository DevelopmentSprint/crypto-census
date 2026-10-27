package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.ContextJson;
import com.developmentsprint.cryptocensus.core.ContextModel;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Validated three-way change sets; imported paths never select a write destination. */
public final class ContextChanges {
  public static final int VERSION = 1;

  private ContextChanges() {}

  /** Declaration keys include the protection field, so disjoint field edits merge independently. */
  public enum Section {
    FLOWS,
    DECLARATIONS,
    RESOLUTIONS,
    READINESS
  }

  /** Immutable address within one context collection; labels and revisions are not identity. */
  public record Key(Section section, String id) implements Comparable<Key> {
    /** Validates a bounded stable address. */
    public Key {
      Objects.requireNonNull(section, "section");
      id = ContextModel.text(id, "edit identity", 2048, false);
    }

    @Override
    public int compareTo(Key other) {
      int order = section.compareTo(other.section);
      return order == 0 ? id.compareTo(other.id) : order;
    }
  }

  /** Base values are copied from report data; null is explicit creation or deletion. */
  @SuppressFBWarnings(
      value = "UNSAFE_HASH_EQUALS",
      justification = "Public content hashes provide revision checking, not secret authentication")
  public record Edit(Key key, Object base, String baseHash, Object proposed) {
    /** Snapshots and type-checks each value, validating identity and the supplied base hash. */
    public Edit {
      Objects.requireNonNull(key, "key");
      base = canonical(key, base);
      proposed = canonical(key, proposal(key.section(), proposed));
      baseHash = ContextModel.hash(baseHash, "base field hash");
      if (!baseHash.equals(CanonicalJson.revision(base))) {
        throw new IllegalArgumentException("Base field hash mismatch");
      }
      if (Objects.equals(base, proposed)) {
        throw new IllegalArgumentException("Edit has no change");
      }
    }
  }

  /** Full immutable base permits Java to verify both the context and per-field revision claims. */
  @SuppressFBWarnings(
      value = {"EI_EXPOSE_REP", "UNSAFE_HASH_EQUALS"},
      justification =
          "Public revision hashes are not secrets. Edit list is copied through"
              + " ContextModel.bounded; edit values are immutable canonical snapshots")
  public record ChangeSet(
      int schemaVersion, ContextDocument base, String baseContextHash, List<Edit> edits) {
    /** Validates all base assertions before any destination is opened. */
    public ChangeSet {
      if (schemaVersion != VERSION) {
        throw new IllegalArgumentException("Unsupported change-set schema");
      }
      Objects.requireNonNull(base, "base");
      baseContextHash = ContextModel.hash(baseContextHash, "base context hash");
      if (!baseContextHash.equals(ContextCodec.revision(base))) {
        throw new IllegalArgumentException("Base context hash mismatch");
      }
      edits = ContextModel.bounded(edits, ContextModel.MAX_DECLARATIONS, "edits");
      Map<Key, Object> fields = fields(base);
      java.util.Set<Key> seen = new HashSet<>();
      for (Edit edit : edits) {
        if (!seen.add(edit.key()) || !Objects.equals(fields.get(edit.key()), edit.base())) {
          throw new IllegalArgumentException(
              "Duplicate edit or base field differs from base context");
        }
      }
    }
  }

  /** Conflict values remain available for explicit user review; no proposed edit is written. */
  public record Conflict(Key key, Object base, Object current, Object proposed) {
    /** Owns canonical immutable values just as exported edits do. */
    public Conflict {
      Objects.requireNonNull(key, "key");
      base = canonical(key, base);
      current = canonical(key, current);
      proposed = canonical(key, proposal(key.section(), proposed));
    }
  }

  /** A conflict-free result contains a validated document; a conflict result has no document. */
  public record Merge(ContextDocument document, List<Conflict> conflicts) {
    /** Snapshots conflicts and prevents partial merge results being mistaken for success. */
    public Merge {
      conflicts = List.copyOf(conflicts);
      if (document == null == conflicts.isEmpty()) {
        throw new IllegalArgumentException("Inconsistent merge result");
      }
    }
  }

  /** Collects all conflicting fields; disjoint changes and already-applied proposals are safe. */
  public static Merge merge(ContextDocument current, ChangeSet changes) {
    Map<Key, Object> merged = new TreeMap<>(fields(current));
    List<Conflict> conflicts = new ArrayList<>();
    for (Edit edit : changes.edits()) {
      Object actual = merged.get(edit.key());
      if (!Objects.equals(actual, edit.base()) && !Objects.equals(actual, edit.proposed())) {
        conflicts.add(new Conflict(edit.key(), edit.base(), actual, edit.proposed()));
      } else if (edit.proposed() == null) {
        merged.remove(edit.key());
      } else {
        merged.put(edit.key(), edit.proposed());
      }
    }
    conflicts.sort(java.util.Comparator.comparing(Conflict::key));
    return conflicts.isEmpty()
        ? new Merge(document(merged), List.of())
        : new Merge(null, conflicts);
  }

  /** Creates a change set by comparing two complete validated contexts. */
  public static ChangeSet between(ContextDocument base, ContextDocument proposed) {
    Map<Key, Object> before = fields(base);
    Map<Key, Object> after = fields(proposed);
    java.util.Set<Key> keys = new java.util.TreeSet<>(before.keySet());
    keys.addAll(after.keySet());
    List<Edit> edits = new ArrayList<>();
    for (Key key : keys) {
      if (!Objects.equals(before.get(key), after.get(key))) {
        edits.add(
            new Edit(
                key, before.get(key), CanonicalJson.revision(before.get(key)), after.get(key)));
      }
    }
    return new ChangeSet(VERSION, base, ContextCodec.revision(base), edits);
  }

  /** Encodes base context and edits with deterministic ordering. */
  public static byte[] encode(ChangeSet changes) {
    return CanonicalJson.encode(
        Map.of(
            "schemaVersion",
            changes.schemaVersion(),
            "base",
            CanonicalJson.decode(ContextCodec.encode(changes.base())),
            "baseContextHash",
            changes.baseContextHash(),
            "edits",
            ContextJson.ordered(changes.edits().stream().map(ContextChanges::edit).toList())));
  }

  /** Parses a bounded, strict change set and verifies every embedded base assertion. */
  public static ChangeSet parse(byte[] bytes) {
    Map<String, Object> value = CanonicalJson.object(CanonicalJson.decode(bytes));
    CanonicalJson.requireKeys(value, "schemaVersion", "base", "baseContextHash", "edits");
    if (CanonicalJson.integer(value, "schemaVersion") != VERSION) {
      throw new IllegalArgumentException("Unsupported change-set schema");
    }
    return new ChangeSet(
        VERSION,
        ContextCodec.parse(CanonicalJson.encode(value.get("base"))),
        CanonicalJson.text(value, "baseContextHash"),
        CanonicalJson.array(value, "edits").stream().map(ContextChanges::edit).toList());
  }

  /** Immutable canonical field values and Java-computed revisions feed the guided editor. */
  public static Map<Key, Object> fields(ContextDocument context) {
    Map<Key, Object> result = new TreeMap<>();
    context
        .flows()
        .forEach(
            value -> put(result, Section.FLOWS, value.subject().ref(), ContextCodec.flow(value)));
    context
        .declarations()
        .forEach(
            value ->
                put(
                    result,
                    Section.DECLARATIONS,
                    ContextDocument.declarationId(value),
                    ContextJson.declaration(value)));
    context
        .resolutions()
        .forEach(
            value ->
                put(
                    result,
                    Section.RESOLUTIONS,
                    ContextDocument.fieldId(value.subject(), value.field()),
                    ContextCodec.resolution(value)));
    context
        .readiness()
        .forEach(
            value ->
                put(
                    result,
                    Section.READINESS,
                    value.subject().ref(),
                    ContextCodec.readiness(value)));
    return java.util.Collections.unmodifiableMap(result);
  }

  private static void put(Map<Key, Object> fields, Section section, String id, Object value) {
    fields.put(new Key(section, id), CanonicalJson.decode(CanonicalJson.encode(value)));
  }

  private static Object canonical(Key key, Object value) {
    if (value == null) {
      return null;
    }
    Object snapshot = CanonicalJson.decode(CanonicalJson.encode(value));
    String identity = identity(key.section(), snapshot);
    if (!key.id().equals(identity)) {
      throw new IllegalArgumentException("Edit identity differs from value identity");
    }
    Object wire =
        switch (key.section()) {
          case FLOWS -> ContextCodec.flow(ContextCodec.flow(snapshot));
          case DECLARATIONS -> ContextJson.declaration(ContextJson.declaration(snapshot));
          case RESOLUTIONS -> ContextCodec.resolution(ContextCodec.resolution(snapshot));
          case READINESS -> ContextCodec.readiness(ContextCodec.readiness(snapshot));
        };
    return CanonicalJson.decode(CanonicalJson.encode(wire));
  }

  private static String identity(Section section, Object snapshot) {
    return switch (section) {
      case FLOWS -> ContextCodec.flow(snapshot).subject().ref();
      case DECLARATIONS -> ContextDocument.declarationId(ContextJson.declaration(snapshot));
      case RESOLUTIONS -> {
        ContextDocument.Resolution resolution = ContextCodec.resolution(snapshot);
        yield ContextDocument.fieldId(resolution.subject(), resolution.field());
      }
      case READINESS -> ContextCodec.readiness(snapshot).subject().ref();
    };
  }

  // Guided editors copy Java-provided base hashes and request USER source revisions here.
  private static Object proposal(Section section, Object value) {
    if (value == null || (section != Section.DECLARATIONS && section != Section.READINESS)) {
      return value;
    }
    Map<String, Object> snapshot =
        new TreeMap<>(CanonicalJson.object(CanonicalJson.decode(CanonicalJson.encode(value))));
    Map<String, Object> source = new TreeMap<>(CanonicalJson.object(snapshot.get("source")));
    if (!CanonicalJson.text(source, "revision").isEmpty()) {
      return snapshot;
    }
    if (!"USER".equals(CanonicalJson.text(source, "kind"))) {
      throw new IllegalArgumentException(
          "Only guided USER proposals may request a source revision");
    }
    source.put("revision", "0".repeat(64));
    snapshot.put("source", source);
    snapshot =
        new TreeMap<>(
            CanonicalJson.object(
                canonical(new Key(section, identity(section, snapshot)), snapshot)));
    source = new TreeMap<>(CanonicalJson.object(snapshot.get("source")));
    source.remove("revision");
    snapshot.put("source", source);
    String revision = CanonicalJson.revision(snapshot);
    source.put("revision", revision);
    return snapshot;
  }

  private static ContextDocument document(Map<Key, Object> fields) {
    List<ContextDocument.Flow> flows = new ArrayList<>();
    List<ContextModel.Declaration> declarations = new ArrayList<>();
    List<ContextDocument.Resolution> resolutions = new ArrayList<>();
    List<ContextDocument.Readiness> readiness = new ArrayList<>();
    fields.forEach(
        (key, value) -> {
          switch (key.section()) {
            case FLOWS -> flows.add(ContextCodec.flow(value));
            case DECLARATIONS -> declarations.add(ContextJson.declaration(value));
            case RESOLUTIONS -> resolutions.add(ContextCodec.resolution(value));
            case READINESS -> readiness.add(ContextCodec.readiness(value));
            default -> throw new IllegalArgumentException("Unknown context section");
          }
        });
    return new ContextDocument(
        ContextDocument.VERSION, flows, declarations, resolutions, readiness);
  }

  private static Map<String, Object> edit(Edit edit) {
    Map<String, Object> result = new TreeMap<>();
    result.put("section", edit.key().section().name());
    result.put("id", edit.key().id());
    result.put("base", edit.base());
    result.put("baseHash", edit.baseHash());
    result.put("proposed", edit.proposed());
    return result;
  }

  private static Edit edit(Object value) {
    Map<String, Object> object = CanonicalJson.object(value);
    CanonicalJson.requireKeys(object, "section", "id", "base", "baseHash", "proposed");
    Section section = Section.valueOf(CanonicalJson.text(object, "section"));
    Object proposed = proposal(section, object.get("proposed"));
    String id = CanonicalJson.text(object, "id");
    if (id.isEmpty()) {
      if (object.get("base") != null || proposed == null) {
        throw new IllegalArgumentException(
            "Automatic edit identity requires creation with a null base");
      }
      id = identity(section, proposed);
    }
    return new Edit(
        new Key(section, id), object.get("base"), CanonicalJson.text(object, "baseHash"), proposed);
  }
}
