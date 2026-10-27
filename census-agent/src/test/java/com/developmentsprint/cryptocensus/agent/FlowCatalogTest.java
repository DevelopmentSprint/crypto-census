package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.ContextModel;
import com.developmentsprint.cryptocensus.core.EvidenceDocument;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlowCatalogTest {
  @Test
  void repeatedBindingsAreIdempotentAndNestedPathsKeepParentRequirements() {
    var catalog = new FlowCatalog(8, 8, ignored -> {});
    var identity = identity("loader-a");
    var outer = definition("archive", "store");
    int outerId = catalog.register(identity, outer);
    int helperId = catalog.register(identity, definition("helper", "encode"));
    assertThat(catalog.register(identity, outer)).isEqualTo(outerId);
    var scopes = new FlowScopes(4);
    scopes.enable();
    final long parent = scopes.enter(outerId);
    long helper = scopes.enter(helperId);
    var context = catalog.context(scopes.current());
    assertThat(context.path())
        .extracting(value -> value.subject().flowId())
        .containsExactly("archive", "helper");
    scopes.exit(helper);
    scopes.exit(parent);
    assertThat(catalog.context(scopes.current()).association())
        .isEqualTo(EvidenceDocument.Association.NO_SCOPE);
    assertThat(catalog.snapshot()).hasSize(2);
    assertThat(
            catalog
                .context(new FlowScopes.Snapshot(FlowScopes.Status.SCOPE_LOST, List.of()))
                .path())
        .isEmpty();
    assertThat(
            catalog
                .context(new FlowScopes.Snapshot(FlowScopes.Status.SYNCHRONOUS_SCOPE, List.of(999)))
                .association())
        .isEqualTo(EvidenceDocument.Association.SCOPE_LOST);
  }

  @Test
  void flowDefinitionsMergeBindingsButKeepDifferentLoaderIdentities() {
    var catalog = new FlowCatalog(8, 8, ignored -> {});
    catalog.register(identity("loader-a"), definition("archive", "first"));
    catalog.register(identity("loader-a"), definition("archive", "second"));
    catalog.register(identity("loader-b"), definition("archive", "first"));
    assertThat(catalog.snapshot()).hasSize(2);
    assertThat(catalog.snapshot().get(0).definition().bindings()).hasSize(2);
    assertThat(catalog.snapshot().get(1).definition().bindings()).hasSize(1);
  }

  @Test
  void capsAndUnresolvedIdentityProduceUnknownBindingWithoutInventingDefinitions() {
    List<EvidenceDocument.Counter> diagnostics = new ArrayList<>();
    var catalog = new FlowCatalog(1, 1, diagnostics::add);
    var identity = identity("loader-a");
    assertThat(catalog.register(identity, definition("first", "method"))).isPositive();
    assertThat(catalog.register(identity, definition("second", "method"))).isZero();
    assertThat(catalog.register(identity, definition("first", "different"))).isZero();
    var unresolved =
        new EvidenceDocument.IdentityMapping(
            "unresolved",
            "deployment",
            "loader",
            "",
            "",
            EvidenceDocument.IdentityStatus.UNRESOLVED,
            "unmapped");
    assertThat(catalog.register(unresolved, definition("third", "method"))).isZero();
    assertThat(catalog.snapshot()).hasSize(1);
    assertThat(diagnostics)
        .containsExactly(
            EvidenceDocument.Counter.DROPPED_FLOWS,
            EvidenceDocument.Counter.DROPPED_BINDINGS,
            EvidenceDocument.Counter.LOST_ATTRIBUTION);
    assertThatThrownBy(() -> new FlowCatalog(0, 1, diagnostics::add))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FlowCatalog(4097, 1, diagnostics::add))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static EvidenceDocument.IdentityMapping identity(String id) {
    return new EvidenceDocument.IdentityMapping(
        id,
        "deployment",
        id,
        "acme:library",
        "app",
        EvidenceDocument.IdentityStatus.RESOLVED,
        "source-catalog");
  }

  static ContextModel.FlowDefinition definition(String id, String method) {
    return new ContextModel.FlowDefinition(
        new ContextModel.Subject("app", id),
        id,
        List.of(),
        List.of(
            new ContextModel.Binding(
                "example.Library", method, "()V", "acme:library", ContextModel.Origin.LIBRARY)));
  }
}
