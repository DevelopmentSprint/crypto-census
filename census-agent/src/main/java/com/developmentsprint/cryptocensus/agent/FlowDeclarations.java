package com.developmentsprint.cryptocensus.agent;

import static net.bytebuddy.matcher.ElementMatchers.named;

import com.developmentsprint.cryptocensus.core.ContextModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.enumeration.EnumerationDescription;
import net.bytebuddy.description.method.MethodDescription;

/** Reads declaration metadata without linking application annotation classes into bootstrap. */
final class FlowDeclarations {
  static final String FLOW = "com.developmentsprint.cryptocensus.annotations.CryptoFlow";
  static final String PROTECTION = "com.developmentsprint.cryptocensus.annotations.DataProtection";
  private static final String MARKER =
      "com.developmentsprint.cryptocensus.annotations.CensusAnnotationApi";
  private static final String PURPOSE =
      "com.developmentsprint.cryptocensus.annotations.CryptoPurpose";

  private FlowDeclarations() {}

  static boolean declared(MethodDescription method) {
    return annotation(method, FLOW) != null || annotation(method, PROTECTION) != null;
  }

  /** Invalid or unresolved declarations are rejected; the caller supplies explicit lost scope. */
  static ContextModel.FlowDefinition read(
      MethodDescription method,
      String applicationId,
      String artifactId,
      ContextModel.Origin origin) {
    AnnotationDescription flow = annotation(method, FLOW);
    if (flow == null) {
      throw new IllegalArgumentException("DataProtection requires CryptoFlow on the same method");
    }
    version(flow);
    EnumerationDescription defaultPurpose =
        defaultValue(flow, "purpose", EnumerationDescription.class);
    if (!"UNSPECIFIED".equals(purpose(defaultPurpose))) {
      throw new IllegalArgumentException("Unsupported CryptoFlow defaults");
    }
    String flowId = flow.getValue("value").resolve(String.class);
    String purpose = purpose(flow.getValue("purpose").resolve(EnumerationDescription.class));
    AnnotationDescription protection = annotation(method, PROTECTION);
    String classification = "";
    int confidentiality = -1;
    int authenticity = -1;
    if (protection != null) {
      version(protection);
      if (!defaultValue(protection, "classification", String.class).isEmpty()
          || defaultValue(protection, "confidentialityYears", Integer.class) != -1
          || defaultValue(protection, "authenticityYears", Integer.class) != -1) {
        throw new IllegalArgumentException("Unsupported DataProtection defaults");
      }
      classification = protection.getValue("classification").resolve(String.class);
      confidentiality = protection.getValue("confidentialityYears").resolve(Integer.class);
      authenticity = protection.getValue("authenticityYears").resolve(Integer.class);
    }
    var subject = new ContextModel.Subject(applicationId, flowId);
    var binding =
        new ContextModel.Binding(
            method.getDeclaringType().asErasure().getName(),
            method.getName(),
            method.getDescriptor(),
            artifactId,
            origin);
    String revision =
        FlowHashes.revision(
            Map.of(
                "apiVersion",
                1,
                "flowId",
                flowId,
                "purpose",
                purpose,
                "classification",
                classification,
                "confidentialityYears",
                confidentiality,
                "authenticityYears",
                authenticity));
    String sourceId =
        "annotation:"
            + FlowHashes.revision(
                Map.of(
                    "class",
                    binding.className(),
                    "method",
                    binding.method(),
                    "descriptor",
                    binding.descriptor(),
                    "artifact",
                    artifactId));
    var source =
        new ContextModel.Source(ContextModel.SourceKind.ANNOTATION, sourceId, revision, "", "");
    List<ContextModel.Declaration> declarations = new ArrayList<>();
    if (!"UNSPECIFIED".equals(purpose)) {
      declarations.add(
          new ContextModel.Declaration(subject, ContextModel.Field.PURPOSE, purpose, source));
    }
    if (!classification.isEmpty()) {
      declarations.add(
          new ContextModel.Declaration(
              subject, ContextModel.Field.CLASSIFICATION, classification, source));
    }
    years(declarations, subject, source, ContextModel.Field.CONFIDENTIALITY_YEARS, confidentiality);
    years(declarations, subject, source, ContextModel.Field.AUTHENTICITY_YEARS, authenticity);
    return new ContextModel.FlowDefinition(subject, flowId, declarations, List.of(binding));
  }

  private static void years(
      List<ContextModel.Declaration> declarations,
      ContextModel.Subject subject,
      ContextModel.Source source,
      ContextModel.Field field,
      int years) {
    if (years != -1) {
      declarations.add(
          new ContextModel.Declaration(subject, field, Integer.toString(years), source));
    }
  }

  private static String purpose(EnumerationDescription value) {
    if (!PURPOSE.equals(value.getEnumerationType().getName())) {
      throw new IllegalArgumentException("Unsupported purpose enumeration");
    }
    return ContextModel.Field.PURPOSE.validate(value.getValue());
  }

  private static <T> T defaultValue(AnnotationDescription annotation, String name, Class<T> type) {
    var value =
        annotation
            .getAnnotationType()
            .getDeclaredMethods()
            .filter(named(name))
            .getOnly()
            .getDefaultValue();
    if (value == null) {
      throw new IllegalArgumentException("Unsupported annotation defaults");
    }
    return value.resolve(type);
  }

  private static void version(AnnotationDescription annotation) {
    for (AnnotationDescription marker : annotation.getAnnotationType().getDeclaredAnnotations()) {
      if (MARKER.equals(marker.getAnnotationType().getName())) {
        if (marker.getValue("value").resolve(Integer.class) == 1) {
          return;
        }
        break;
      }
    }
    throw new IllegalArgumentException("Unsupported or unresolved annotation API version");
  }

  private static AnnotationDescription annotation(MethodDescription method, String name) {
    for (AnnotationDescription value : method.getDeclaredAnnotations()) {
      if (name.equals(value.getAnnotationType().getName())) {
        return value;
      }
    }
    return null;
  }
}
