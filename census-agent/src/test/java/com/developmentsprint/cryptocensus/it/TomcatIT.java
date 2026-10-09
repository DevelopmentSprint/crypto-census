package com.developmentsprint.cryptocensus.it;

import static com.developmentsprint.cryptocensus.it.ConformanceAssertions.assertAttributedTo;
import static com.developmentsprint.cryptocensus.it.ConformanceAssertions.assertHealthy;
import static org.assertj.core.api.Assertions.assertThat;

import com.developmentsprint.cryptocensus.core.Cbom;
import com.developmentsprint.cryptocensus.core.Inventory;
import com.developmentsprint.cryptocensus.core.Primitive;
import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Conformance: embedded Tomcat serving HTTPS, with crypto inside a servlet, in a child JVM with the
 * agent; alone and together with the OpenTelemetry Java agent (which instruments Tomcat too).
 */
class TomcatIT {

  private static final String SERVLET = TomcatWorkload.CryptoServlet.class.getName();

  @TempDir static Path tmp;
  static Path keystore;

  @BeforeAll
  static void createKeystore() throws Exception {
    keystore = ChildJvm.keystore(tmp);
  }

  @ParameterizedTest(name = "with OpenTelemetry agent: {0}")
  @ValueSource(booleans = {false, true})
  void attributesServletCryptoAndRecordsServerTls(boolean withOpenTelemetry) throws Exception {
    Path cbom = tmp.resolve("tomcat-" + withOpenTelemetry + ".json");
    List<String> jvmArgs = new ArrayList<>();
    if (withOpenTelemetry) {
      jvmArgs.addAll(ChildJvm.openTelemetryAgent());
    }
    jvmArgs.add("-javaagent:" + ChildJvm.agentJar() + "=output=" + cbom + ",app=tomcat-it");

    ChildJvm.Result run =
        ChildJvm.run(
            tmp,
            jvmArgs,
            List.of(
                ChildJvm.classpathEntry(TomcatWorkload.class),
                ChildJvm.classpathEntry(Tomcat.class),
                ChildJvm.classpathEntry(PostConstruct.class)),
            TomcatWorkload.class,
            List.of(
                keystore.toString(),
                ChildJvm.KEYSTORE_PASSWORD,
                tmp.resolve("tomcat-base-" + withOpenTelemetry).toString()));

    assertThat(cbom).as(run.stderr()).exists();
    Cbom.Document document = Cbom.read(cbom);
    assertHealthy(run, document);
    if (withOpenTelemetry) {
      assertThat(run.stderr()).contains("opentelemetry-javaagent");
    }

    // crypto inside the servlet is attributed to the servlet, not to Tomcat or the APM agent
    assertAttributedTo(document, Primitive.MESSAGE_DIGEST, "SHA3-256", SERVLET, "doGet");
    assertAttributedTo(document, Primitive.MAC, "HmacSHA3-256", SERVLET, "doGet");

    // the HTTPS connector's sessions: Tomcat's NIO channel on the server side, the client in the
    // workload; both through JSSE
    List<Inventory.Entry> tls =
        document.inventory().entries().stream()
            .filter(e -> e.asset().primitive() == Primitive.TLS)
            .toList();
    assertThat(tls).as(ConformanceAssertions.describe(document)).isNotEmpty();
    assertThat(tls).allSatisfy(e -> assertThat(e.asset().protocolVersion()).isEqualTo("TLSv1.3"));
    assertThat(tls.stream().flatMap(e -> e.occurrences().keySet().stream()))
        .allSatisfy(o -> assertThat(o.via()).isEqualTo("jsse"))
        .anySatisfy(o -> assertThat(o.location()).startsWith("org.apache.tomcat."))
        .anySatisfy(o -> assertThat(o.location()).isEqualTo(TomcatWorkload.class.getName()));
  }
}
