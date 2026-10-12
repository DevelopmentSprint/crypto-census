package com.developmentsprint.cryptocensus.delivery.profile;

import com.developmentsprint.cryptocensus.delivery.preparation.InputKind;
import java.util.Set;

/** Versioned, evidence-backed limits; acceptance does not imply semantic or byte retention. */
public record CapabilityDeclaration(
    String adapterId,
    String serverVersion,
    String evidenceRevision,
    Set<InputKind> inputKinds,
    Set<String> schemaVersions,
    Set<String> lossCodes,
    InventoryMutation mutation,
    boolean asynchronous,
    boolean nativeIdempotency,
    java.util.Map<String, FieldSupport> semanticFields,
    ByteRetention byteRetention,
    Linkage linkage) {
  /** Copies all capability collections. */
  public CapabilityDeclaration {
    inputKinds = Set.copyOf(inputKinds);
    schemaVersions = Set.copyOf(schemaVersions);
    lossCodes = Set.copyOf(lossCodes);
    semanticFields = java.util.Map.copyOf(semanticFields);
  }

  /** Constructor with explicitly unverified semantic/retention/linkage claims. */
  public CapabilityDeclaration(
      String adapterId,
      String serverVersion,
      String evidenceRevision,
      Set<InputKind> inputKinds,
      Set<String> schemaVersions,
      Set<String> lossCodes,
      InventoryMutation mutation,
      boolean asynchronous,
      boolean nativeIdempotency) {
    this(
        adapterId,
        serverVersion,
        evidenceRevision,
        inputKinds,
        schemaVersions,
        lossCodes,
        mutation,
        asynchronous,
        nativeIdempotency,
        java.util.Map.of(),
        ByteRetention.UNVERIFIED,
        Linkage.UNVERIFIED);
  }

  /** Adds tested evidence without promoting untested fields. */
  public CapabilityDeclaration withEvidence(
      java.util.Map<String, FieldSupport> fields, ByteRetention retention, Linkage links) {
    return new CapabilityDeclaration(
        adapterId,
        serverVersion,
        evidenceRevision,
        inputKinds,
        schemaVersions,
        lossCodes,
        mutation,
        asynchronous,
        nativeIdempotency,
        fields,
        retention,
        links);
  }

  /** Independently measured semantic support. */
  public enum FieldSupport {
    PRESERVED,
    TRANSFORMED,
    DROPPED,
    UNVERIFIED
  }

  /** Original input bytes retained by this destination, separate from inventory export. */
  public enum ByteRetention {
    NONE,
    TEMPORARY,
    DURABLE_RETRIEVABLE,
    UNVERIFIED
  }

  /** Verified target behavior for links to separate evidence. */
  public enum Linkage {
    NATIVE,
    REFERENCE_ONLY,
    UNVERIFIED
  }

  /** Mutation semantics verified for this server contract. */
  public enum InventoryMutation {
    ARTIFACT_ONLY,
    REPLACE_SNAPSHOT,
    APPEND,
    MERGE_BY_KEY,
    UNVERIFIED
  }
}
