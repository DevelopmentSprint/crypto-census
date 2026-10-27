package com.developmentsprint.cryptocensus.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;

class AnnotationsTest {
  @Test
  void usageAnnotationsExposeTheirRuntimeContract() {
    assertUsageContract(CryptoFlow.class);
    assertUsageContract(DataProtection.class);
    assertThat(CensusAnnotationApi.class.getAnnotation(Retention.class).value())
        .isEqualTo(RetentionPolicy.RUNTIME);
    assertThat(CensusAnnotationApi.class.getAnnotation(Target.class).value())
        .containsExactly(ElementType.ANNOTATION_TYPE);
    assertThat(CensusAnnotationApi.class.isAnnotationPresent(Documented.class)).isTrue();
  }

  @Test
  void undeclaredValuesRemainDistinctFromActualRequirements() throws ReflectiveOperationException {
    assertThat(new Example().identityOnly()).isEqualTo("identity");
    var method = Example.class.getDeclaredMethod("identityOnly");
    var flow = method.getAnnotation(CryptoFlow.class);
    var protection = method.getAnnotation(DataProtection.class);
    assertThat(flow.value()).isEqualTo("records-export");
    assertThat(flow.purpose()).isEqualTo(CryptoPurpose.UNSPECIFIED);
    assertThat(protection.classification()).isEmpty();
    assertThat(DataProtection.UNDECLARED).isEqualTo(-1);
    assertThat(protection.confidentialityYears()).isEqualTo(DataProtection.UNDECLARED);
    assertThat(protection.authenticityYears()).isEqualTo(DataProtection.UNDECLARED);
    assertThat(CryptoFlow.class.getDeclaredMethod("value").getDefaultValue()).isNull();
    assertThat(CensusAnnotationApi.class.getDeclaredMethod("value").getDefaultValue()).isNull();
  }

  @Test
  void declaredRequirementsSurviveRuntimeReflection() throws ReflectiveOperationException {
    assertThat(new Example().protectedExport()).isEqualTo("export");
    var method = Example.class.getDeclaredMethod("protectedExport");
    assertThat(method.getAnnotation(CryptoFlow.class).purpose()).isEqualTo(CryptoPurpose.TRANSPORT);
    var protection = method.getAnnotation(DataProtection.class);
    assertThat(protection.classification()).isEqualTo("restricted");
    assertThat(protection.confidentialityYears()).isEqualTo(20);
    assertThat(protection.authenticityYears()).isEqualTo(10);
    assertThat(CryptoPurpose.values())
        .containsExactly(
            CryptoPurpose.UNSPECIFIED,
            CryptoPurpose.TRANSPORT,
            CryptoPurpose.STORAGE,
            CryptoPurpose.KEY_PROTECTION,
            CryptoPurpose.SIGNING,
            CryptoPurpose.VERIFICATION);
    assertThat(CryptoPurpose.valueOf("KEY_PROTECTION")).isEqualTo(CryptoPurpose.KEY_PROTECTION);
  }

  @Test
  void declarationsDoNotTransferToImplementationsOrOverrides() throws ReflectiveOperationException {
    DeclaredInterface implementation = new Implementation();
    assertThat(implementation.execute()).isEqualTo("implementation");
    assertThat(new BaseExample().execute()).isEqualTo("base");
    assertThat(new OverrideExample().execute()).isEqualTo("override");
    assertThat(Implementation.class.getDeclaredMethod("execute").getAnnotation(CryptoFlow.class))
        .isNull();
    assertThat(OverrideExample.class.getDeclaredMethod("execute").getAnnotation(CryptoFlow.class))
        .isNull();
    assertThat(DeclaredInterface.class.getDeclaredMethod("execute").getAnnotation(CryptoFlow.class))
        .isNotNull();
  }

  private static void assertUsageContract(Class<? extends Annotation> type) {
    assertThat(type.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
    assertThat(type.getAnnotation(Target.class).value()).containsExactly(ElementType.METHOD);
    assertThat(type.isAnnotationPresent(Documented.class)).isTrue();
    assertThat(type.isAnnotationPresent(Inherited.class)).isFalse();
    assertThat(type.getAnnotation(CensusAnnotationApi.class).value()).isEqualTo(1);
  }

  private static final class Example {
    @CryptoFlow("records-export")
    @DataProtection
    String identityOnly() {
      return "identity";
    }

    @CryptoFlow(value = "records-export", purpose = CryptoPurpose.TRANSPORT)
    @DataProtection(
        classification = "restricted",
        confidentialityYears = 20,
        authenticityYears = 10)
    String protectedExport() {
      return "export";
    }
  }

  private interface DeclaredInterface {
    @CryptoFlow("interface-flow")
    String execute();
  }

  private static final class Implementation implements DeclaredInterface {
    @Override
    public String execute() {
      return "implementation";
    }
  }

  private static class BaseExample {
    @CryptoFlow("base-flow")
    String execute() {
      return "base";
    }
  }

  private static final class OverrideExample extends BaseExample {
    @Override
    String execute() {
      return "override";
    }
  }
}
