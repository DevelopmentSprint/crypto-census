package com.developmentsprint.cryptocensus.delivery.dependencytrack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Fail-closed interpretation of the pinned native API. Remote values never enter diagnostics. */
final class DependencyTrackRules {
  static final String SERVER_VERSION = "5.2.0";
  static final String EVIDENCE_REVISION = "dependency-track-5.2.0/2026-10-10";
  static final String OWNER_GROUP = "cryptocensus";
  static final String OWNER_NAME = "runtime.owner";
  static final int PAGE_SIZE = 25;
  static final Set<String> LOSSES =
      Set.of(
          "DT_CRYPTO_PROPERTIES_DROPPED",
          "DT_OPERATION_IDENTITIES_COLLAPSED",
          "DT_OCCURRENCE_CONTEXT_DROPPED",
          "DT_METADATA_PROPERTIES_DROPPED",
          "DT_DOCUMENT_IDENTIFIERS_REWRITTEN",
          "DT_TOOL_PROVENANCE_REPLACED",
          "DT_ORIGINAL_BYTES_NOT_RETAINED");

  private DependencyTrackRules() {}

  static void verifyServer(Object value) {
    Map<?, ?> server = object(value);
    if (!"Dependency-Track".equals(server.get("application"))
        || !SERVER_VERSION.equals(server.get("version"))) {
      throw new IllegalArgumentException("DT_SERVER_UNVERIFIED");
    }
  }

  static void verifyProject(Object value, UUID expected, String version) {
    Map<?, ?> project = object(value);
    if (!expected.equals(uuid(project.get("uuid")))
        || !version.equals(project.get("version"))
        || !"APPLICATION".equals(project.get("classifier"))
        || project.get("collectionLogic") != null
        || !Boolean.TRUE.equals(project.get("active"))) {
      throw new IllegalArgumentException("DT_PROJECT_MAPPING_UNVERIFIED");
    }
  }

  static void verifyOwner(Object value, String owner) {
    List<?> properties = array(value);
    if (properties.size() > 1000) {
      throw new IllegalArgumentException("DT_PROJECT_OWNERSHIP_UNVERIFIED");
    }
    int matches = 0;
    for (Object element : properties) {
      Map<?, ?> property = object(element);
      if (OWNER_GROUP.equals(property.get("groupName"))
          && OWNER_NAME.equals(property.get("propertyName"))) {
        if (!"STRING".equals(property.get("propertyType"))
            || !owner.equals(property.get("propertyValue"))) {
          throw new IllegalArgumentException("DT_PROJECT_OWNERSHIP_UNVERIFIED");
        }
        matches++;
      }
    }
    if (matches != 1) {
      throw new IllegalArgumentException("DT_PROJECT_OWNERSHIP_UNVERIFIED");
    }
  }

  static List<UUID> componentPage(Object value) {
    List<?> components = array(value);
    if (components.size() > PAGE_SIZE) {
      throw new IllegalArgumentException("DT_INVENTORY_PAGINATION_UNVERIFIED");
    }
    return components.stream()
        .map(DependencyTrackRules::object)
        .map(
            component -> {
              if (!"CRYPTOGRAPHIC_ASSET".equals(component.get("classifier"))) {
                throw new IllegalArgumentException("DT_SOFTWARE_INVENTORY_REPLACEMENT_FORBIDDEN");
              }
              return uuid(component.get("uuid"));
            })
        .toList();
  }

  static void verifyNoServices(Object value) {
    if (!array(value).isEmpty()) {
      throw new IllegalArgumentException("DT_SERVICE_INVENTORY_REPLACEMENT_FORBIDDEN");
    }
  }

  static UUID uploadToken(Object value, UUID expectedProject) {
    Map<?, ?> acknowledgment = object(value);
    if (!expectedProject.equals(uuid(acknowledgment.get("projectUuid")))) {
      throw new IllegalArgumentException("DT_UPLOAD_ACK_MISMATCH");
    }
    return uuid(acknowledgment.get("token"));
  }

  static Completion completion(Object value) {
    Map<?, ?> observation = object(value);
    Object processing = observation.get("processing");
    Object status = observation.get("status");
    if (Boolean.TRUE.equals(processing) && ("PENDING".equals(status) || "RUNNING".equals(status))) {
      return Completion.PENDING;
    }
    if (Boolean.FALSE.equals(processing)) {
      if ("COMPLETED".equals(status)) {
        return Completion.COMPLETED;
      }
      if ("FAILED".equals(status)) {
        return Completion.FAILED;
      }
    }
    return Completion.UNVERIFIED;
  }

  static UUID uuid(Object value) {
    if (!(value instanceof String text)
        || !text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
      throw new IllegalArgumentException("DT_RESPONSE_INVALID");
    }
    return UUID.fromString(text);
  }

  private static Map<?, ?> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("DT_RESPONSE_INVALID");
    }
    return map;
  }

  private static List<?> array(Object value) {
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("DT_RESPONSE_INVALID");
    }
    return list;
  }

  enum Completion {
    PENDING,
    COMPLETED,
    FAILED,
    UNVERIFIED
  }
}
