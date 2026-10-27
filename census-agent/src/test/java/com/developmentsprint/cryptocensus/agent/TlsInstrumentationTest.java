package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.EvidenceDocument.Association;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.CapabilityStatus;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.Counter;
import com.developmentsprint.cryptocensus.core.EvidenceDocument.ScopeContext;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.bytebuddy.dynamic.ClassFileLocator;
import org.junit.jupiter.api.Test;

class TlsInstrumentationTest {
  @Test
  void unavailableModuleAccessDisablesOnlyTlsAndReportsCapability() {
    List<Counter> health = new ArrayList<>();
    TlsCollector.configure(
        () -> new ScopeContext("test", List.of(), Association.NO_SCOPE), health::add);
    Instrumentation unavailable =
        (Instrumentation)
            Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, arguments) -> {
                  throw new UnsupportedOperationException("module access unavailable");
                });
    assertThat(TlsInstrumentation.install(unavailable, ClassFileLocator.NoOp.INSTANCE)).isFalse();
    assertThat(health).containsExactly(Counter.TRANSFORM_ERRORS);
    assertThat(TlsInstrumentation.capabilities())
        .allSatisfy(
            capability -> assertThat(capability.status()).isEqualTo(CapabilityStatus.UNAVAILABLE));
    assertThat(TlsCollector.enter(new Object())).isNull();
    TlsCollector.close();
  }
}
