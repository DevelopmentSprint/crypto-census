package com.developmentsprint.cryptocensus.agent;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.annotations.CensusAnnotationApi;
import com.developmentsprint.cryptocensus.annotations.CryptoFlow;
import com.developmentsprint.cryptocensus.annotations.CryptoPurpose;
import com.developmentsprint.cryptocensus.annotations.DataProtection;
import com.developmentsprint.cryptocensus.core.ContextModel;
import java.lang.reflect.Modifier;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.StubMethod;
import org.junit.jupiter.api.Test;

class FlowDeclarationsTest {
  @Test
  void declarationsCarryNormalizedRevisionAndDeclaringArtifact() {
    var definition = read("archive");
    assertThat(definition.subject()).isEqualTo(new ContextModel.Subject("acme:billing", "archive"));
    assertThat(definition.bindings().get(0).sourceArtifact()).isEqualTo("acme:library");
    assertThat(definition.bindings().get(0).origin()).isEqualTo(ContextModel.Origin.LIBRARY);
    assertThat(definition.declarations())
        .extracting(ContextModel.Declaration::value)
        .containsExactly("STORAGE", "restricted", "20", "10");
    assertThat(definition.declarations())
        .extracting(value -> value.source().revision())
        .allMatch(hash -> hash.matches("[a-f0-9]{64}"));
    assertThat(definition).isEqualTo(read("archive"));
  }

  @Test
  void absentProtectionAndUnspecifiedPurposeDoNotInventRequirements() {
    assertThat(read("minimal").declarations()).isEmpty();
    assertThat(read("defaults").declarations()).isEmpty();
    assertThat(FlowDeclarations.declared(method("unannotated"))).isFalse();
    assertThat(FlowDeclarations.declared(method("orphan"))).isTrue();
  }

  @Test
  void malformedMetadataIsRejectedInsteadOfBeingNormalizedIntoClaims() {
    for (String method :
        new String[] {
          "orphan", "badId", "badClassification", "zeroYears", "negativeYears", "excessiveYears"
        }) {
      assertThatThrownBy(() -> read(method)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void unresolvedVersionAndChangedDefaultsAreRejected() {
    for (int version : new int[] {0, 2}) {
      MethodDescription method = synthetic(version, CryptoPurpose.UNSPECIFIED);
      assertThatThrownBy(
              () -> FlowDeclarations.read(method, "app", "artifact", ContextModel.Origin.MAIN))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("version");
    }
    MethodDescription changed = synthetic(1, CryptoPurpose.STORAGE);
    assertThatThrownBy(
            () -> FlowDeclarations.read(changed, "app", "artifact", ContextModel.Origin.MAIN))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("defaults");
  }

  private static MethodDescription synthetic(int version, CryptoPurpose defaultPurpose) {
    DynamicType.Builder<?> annotation =
        new ByteBuddy()
            .makeAnnotation()
            .name(FlowDeclarations.FLOW)
            .defineMethod("value", String.class, Modifier.PUBLIC | Modifier.ABSTRACT)
            .withoutCode()
            .defineMethod("purpose", CryptoPurpose.class, Modifier.PUBLIC | Modifier.ABSTRACT)
            .defaultValue(defaultPurpose, CryptoPurpose.class);
    if (version != 0) {
      annotation =
          annotation.annotateType(
              AnnotationDescription.Builder.ofType(CensusAnnotationApi.class)
                  .define("value", version)
                  .build());
    }
    TypeDescription description = annotation.make().getTypeDescription();
    return new ByteBuddy()
        .subclass(Object.class)
        .name("fixture.Versioned")
        .defineMethod("work", void.class, Modifier.PUBLIC)
        .intercept(StubMethod.INSTANCE)
        .annotateMethod(
            AnnotationDescription.Builder.ofType(description).define("value", "valid").build())
        .make()
        .getTypeDescription()
        .getDeclaredMethods()
        .filter(named("work"))
        .getOnly();
  }

  private static ContextModel.FlowDefinition read(String name) {
    return FlowDeclarations.read(
        method(name), "acme:billing", "acme:library", ContextModel.Origin.LIBRARY);
  }

  private static MethodDescription method(String name) {
    return TypeDescription.ForLoadedType.of(Declarations.class)
        .getDeclaredMethods()
        .filter(named(name))
        .getOnly();
  }

  static class Declarations {
    @CryptoFlow(value = "archive", purpose = CryptoPurpose.STORAGE)
    @DataProtection(
        classification = "restricted",
        confidentialityYears = 20,
        authenticityYears = 10)
    public void archive() {}

    @CryptoFlow("minimal")
    public void minimal() {}

    @CryptoFlow("defaults")
    @DataProtection
    public void defaults() {}

    public void unannotated() {}

    @DataProtection
    public void orphan() {}

    @CryptoFlow("not a valid id")
    public void badId() {}

    @CryptoFlow("valid")
    @DataProtection(classification = "not valid")
    public void badClassification() {}

    @CryptoFlow("valid")
    @DataProtection(confidentialityYears = 0)
    public void zeroYears() {}

    @CryptoFlow("valid")
    @DataProtection(authenticityYears = -2)
    public void negativeYears() {}

    @CryptoFlow("valid")
    @DataProtection(confidentialityYears = 1001)
    public void excessiveYears() {}
  }
}
