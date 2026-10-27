package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import com.developmentsprint.cryptocensus.core.SourceCatalog;
import java.net.URI;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlowIdentitiesTest {
  @Test
  void declaringArtifactAndLoaderRemainSeparateFromApplicationIdentity() throws Exception {
    var catalog =
        new SourceCatalog(
            1,
            List.of(
                new SourceCatalog.Entry(
                    "file:/build/library.jar",
                    "acme:library",
                    "acme:billing",
                    ContextModel.Origin.LIBRARY,
                    "deployment-a")));
    var identities = new FlowIdentities(catalog, null, ignored -> {}, 8);
    var domain = domain("file:/build/library.jar");
    var firstLoader = new ClassLoader() {};
    var first = identities.resolve(domain, firstLoader);
    assertThat(first.applicationId()).isEqualTo("acme:billing");
    assertThat(first.sourceArtifact()).isEqualTo("acme:library");
    assertThat(first.deploymentId()).isEqualTo("deployment-a");
    assertThat(first.provenance()).isEqualTo("source-catalog");
    assertThat(identities.origin(domain, firstLoader)).isEqualTo(ContextModel.Origin.LIBRARY);
    assertThat(identities.resolve(domain, firstLoader)).isEqualTo(first);
    assertThat(identities.resolve(domain, new ClassLoader() {}).id()).isNotEqualTo(first.id());
    assertThat(identities.snapshot()).hasSize(3);
  }

  @Test
  void unmappedSourcesAreExplicitAndOnlyStableOverrideResolvesThem() {
    var sources = new SourceCatalog(1, List.of());
    var unknown = new FlowIdentities(sources, null, ignored -> {}, 4);
    assertThat(unknown.resolve(null, null).status())
        .isEqualTo(EvidenceDocument.IdentityStatus.UNRESOLVED);
    assertThat(unknown.origin(null, null)).isEqualTo(ContextModel.Origin.UNKNOWN);
    var explicit = new FlowIdentities(sources, "stable-app", ignored -> {}, 4);
    var resolved = explicit.resolve(new ProtectionDomain(null, null), null);
    assertThat(resolved.status()).isEqualTo(EvidenceDocument.IdentityStatus.RESOLVED);
    assertThat(resolved.applicationId()).isEqualTo("stable-app");
    assertThat(resolved.sourceArtifact()).isEmpty();
    assertThat(resolved.provenance()).isEqualTo("explicit-application-id");
  }

  @Test
  void boundedIdentitiesAndLoadersDegradeWithoutRetainingExtraEntries() {
    List<EvidenceDocument.Counter> diagnostics = new ArrayList<>();
    var identities =
        new FlowIdentities(new SourceCatalog(1, List.of()), "app", diagnostics::add, 2);
    ClassLoader first = new ClassLoader() {};
    assertThat(identities.resolve(null, first).id()).isNotEqualTo(FlowIdentities.UNKNOWN);
    assertThat(identities.resolve(null, new ClassLoader() {}).id())
        .isEqualTo(FlowIdentities.UNKNOWN);
    assertThat(identities.resolve(null, new ClassLoader() {}).id())
        .isEqualTo(FlowIdentities.UNKNOWN);
    assertThat(identities.resolve(null, first).id()).isNotEqualTo(FlowIdentities.UNKNOWN);
    assertThat(identities.snapshot()).hasSize(2);
    assertThat(diagnostics)
        .containsExactly(
            EvidenceDocument.Counter.DROPPED_BINDINGS, EvidenceDocument.Counter.DROPPED_BINDINGS);
    assertThatThrownBy(
            () -> new FlowIdentities(new SourceCatalog(1, List.of()), null, diagnostics::add, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new FlowIdentities(new SourceCatalog(1, List.of()), null, diagnostics::add, 4097))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ProtectionDomain domain(String uri) throws Exception {
    return new ProtectionDomain(
        new CodeSource(URI.create(uri).toURL(), (Certificate[]) null), null);
  }
}
