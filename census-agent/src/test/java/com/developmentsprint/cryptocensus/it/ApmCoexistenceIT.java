package com.developmentsprint.cryptocensus.it;

import static com.developmentsprint.cryptocensus.it.ConformanceAssertions.assertAttributedTo;
import static com.developmentsprint.cryptocensus.it.ConformanceAssertions.assertHealthy;
import static com.developmentsprint.cryptocensus.it.ConformanceAssertions.entries;
import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.Operation;
import com.developmentsprint.cryptocensus.core.Primitive;
import com.developmentsprint.cryptocensus.core.Risk;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Conformance: Crypto Census next to the OpenTelemetry Java agent, in both {@code -javaagent}
 * orders. Both append to the bootstrap class path and retransform JDK classes; neither may break
 * the application or the other's collection.
 */
class ApmCoexistenceIT {

  @TempDir static Path tmp;
  static Path keystore;

  @BeforeAll
  static void createKeystore() throws Exception {
    keystore = ChildJvm.keystore(tmp);
  }

  @ParameterizedTest(name = "OpenTelemetry agent first: {0}")
  @ValueSource(booleans = {true, false})
  void bothAgentsWorkInEitherOrder(boolean openTelemetryFirst) throws Exception {
    Path cbom = tmp.resolve("otel-first-" + openTelemetryFirst + ".json");
    String census = "-javaagent:" + ChildJvm.agentJar() + "=output=" + cbom + ",app=apm-it";
    List<String> jvmArgs = new ArrayList<>();
    if (openTelemetryFirst) {
      jvmArgs.addAll(ChildJvm.openTelemetryAgent());
      jvmArgs.add(census);
    } else {
      jvmArgs.add(census);
      jvmArgs.addAll(ChildJvm.openTelemetryAgent());
    }

    ChildJvm.Result run =
        ChildJvm.run(
            tmp,
            jvmArgs,
            List.of(ChildJvm.classpathEntry(CryptoWorkload.class)),
            CryptoWorkload.class,
            List.of(keystore.toString(), ChildJvm.KEYSTORE_PASSWORD));

    assertThat(cbom).as(run.stderr()).exists();
    Cbom.Document document = Cbom.read(cbom);
    assertHealthy(run, document);
    // the APM agent really was active
    assertThat(run.stderr()).contains("opentelemetry-javaagent");

    // the workload's assets, attributed to the workload
    assertAttributedTo(
        document, Primitive.MESSAGE_DIGEST, "MD5", CryptoWorkload.class.getName(), null);
    assertThat(entries(document, Primitive.CIPHER, "AES"))
        .anySatisfy(
            e -> {
              assertThat(e.asset().mode()).isEqualToIgnoringCase("GCM");
              assertThat(e.asset().keySize()).isEqualTo(256);
              assertThat(e.asset().operation()).isEqualTo(Operation.ENCRYPT);
              assertThat(e.assessment().risk()).isEqualTo(Risk.QUANTUM_SAFE);
            });
    assertThat(entries(document, Primitive.SIGNATURE, "SHA256withRSA"))
        .anySatisfy(e -> assertThat(e.assessment().risk()).isEqualTo(Risk.QUANTUM_VULNERABLE));
    assertThat(entries(document, Primitive.KEY_AGREEMENT, "X25519")).isNotEmpty();
    assertThat(document.inventory().entries())
        .anySatisfy(
            e -> {
              assertThat(e.asset().primitive()).isEqualTo(Primitive.TLS);
              assertThat(e.asset().protocolVersion()).isEqualTo("TLSv1.3");
            });
  }
}
