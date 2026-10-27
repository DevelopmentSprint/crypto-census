package com.developmentsprint.cryptocensus.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.developmentsprint.cryptocensus.core.CanonicalJson;
import com.developmentsprint.cryptocensus.core.ContextJson;
import com.developmentsprint.cryptocensus.core.ContextModel;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextWorkflowTest {
  private static final ContextModel.Subject SUBJECT = new ContextModel.Subject("app", "archive");
  private static final ContextModel.Subject DEFAULT = new ContextModel.Subject("app", "");
  private static final String DATE = "2026-10-26T00:00:00Z";

  private static ContextModel.Source source(
      ContextModel.SourceKind kind, String id, String revision) {
    return new ContextModel.Source(kind, id, revision.repeat(64), "reviewer", DATE);
  }

  private static ContextModel.Declaration declaration(
      ContextModel.Subject subject, ContextModel.Field field, String value, String revision) {
    return new ContextModel.Declaration(
        subject, field, value, source(ContextModel.SourceKind.USER, "review", revision));
  }

  private static ContextDocument document(ContextModel.Declaration... declarations) {
    return new ContextDocument(1, List.of(), List.of(declarations), List.of(), List.of());
  }

  @Test
  void identicalSavedAndObservedDeclarationsConsumeOneCombinedBudget() {
    List<ContextModel.Declaration> declarations =
        java.util.stream.IntStream.range(0, ContextModel.MAX_DECLARATIONS / 2 + 1)
            .mapToObj(
                index ->
                    new ContextModel.Declaration(
                        SUBJECT,
                        ContextModel.Field.CLASSIFICATION,
                        "sensitive",
                        source(ContextModel.SourceKind.ANNOTATION, "source-" + index, "a")))
            .toList();
    ContextDocument context = new ContextDocument(1, List.of(), declarations, List.of(), List.of());
    Reconciliation.Result result =
        Reconciliation.resolve(SUBJECT, ContextModel.Field.CLASSIFICATION, declarations, context);
    assertThat(result.status()).isEqualTo(Reconciliation.Status.AGREED);
    assertThat(result.sources()).hasSize(declarations.size());
  }

  @Test
  void guidedUserReadinessRevisionUsesCanonicalReferences() {
    ContextDocument.Readiness readiness =
        new ContextDocument.Readiness(
            SUBJECT,
            ContextDocument.ReadinessState.CANDIDATE,
            "Reviewed prerequisite",
            source(ContextModel.SourceKind.USER, "guided", "a"),
            List.of("a", "z"));
    Map<String, Object> proposed = new java.util.TreeMap<>(ContextCodec.readiness(readiness));
    Map<String, Object> pending =
        new java.util.TreeMap<>(CanonicalJson.object(proposed.get("source")));
    pending.put("revision", "");
    proposed.put("source", pending);
    ContextChanges.Key key =
        new ContextChanges.Key(ContextChanges.Section.READINESS, SUBJECT.ref());
    ContextChanges.Edit first =
        new ContextChanges.Edit(key, null, CanonicalJson.revision(null), proposed);
    proposed.put("validationRefs", List.of("z", "a"));
    ContextChanges.Edit second =
        new ContextChanges.Edit(key, null, CanonicalJson.revision(null), proposed);
    assertThat(first).isEqualTo(second);
    assertThat(ContextCodec.readiness(first.proposed()).source().revision()).hasSize(64);
    pending.put("kind", "ANNOTATION");
    assertThatThrownBy(
            () -> new ContextChanges.Edit(key, null, CanonicalJson.revision(null), proposed))
        .hasMessageContaining("Only guided USER");
  }

  @Test
  void replacementWithUnchangedAssertedRevisionInvalidatesReviewedResolution() {
    ContextModel.Declaration original =
        declaration(SUBJECT, ContextModel.Field.CONFIDENTIALITY_YEARS, "5", "a");
    ContextDocument.Resolution resolution =
        new ContextDocument.Resolution(
            SUBJECT,
            original.field(),
            "5",
            List.of(ContextDocument.SourceRef.of(original)),
            "reviewer",
            DATE);
    ContextDocument base =
        new ContextDocument(1, List.of(), List.of(original), List.of(resolution), List.of());
    ContextDocument changed =
        new ContextDocument(
            1,
            List.of(),
            List.of(declaration(SUBJECT, original.field(), "20", "a")),
            List.of(resolution),
            List.of());
    ContextDocument applied =
        ContextChanges.merge(base, ContextChanges.between(base, changed)).document();
    assertThat(Reconciliation.resolve(SUBJECT, original.field(), List.of(), applied).status())
        .isEqualTo(Reconciliation.Status.STALE_RESOLUTION);
  }

  @Test
  void importedUnorderedValuesRemainIdempotentAfterCanonicalWrite() {
    ContextDocument.Readiness readiness =
        new ContextDocument.Readiness(
            SUBJECT,
            ContextDocument.ReadinessState.CANDIDATE,
            "Candidate only",
            source(ContextModel.SourceKind.USER, "readiness", "a"),
            List.of("z", "a"));
    Map<String, Object> unordered = new java.util.TreeMap<>(ContextCodec.readiness(readiness));
    unordered.put("validationRefs", List.of("z", "a"));
    ContextChanges.Edit edit =
        new ContextChanges.Edit(
            new ContextChanges.Key(ContextChanges.Section.READINESS, SUBJECT.ref()),
            null,
            CanonicalJson.revision(null),
            unordered);
    ContextChanges.ChangeSet changes =
        new ContextChanges.ChangeSet(
            1,
            ContextDocument.empty(),
            ContextCodec.revision(ContextDocument.empty()),
            List.of(edit));
    ContextDocument first = ContextChanges.merge(ContextDocument.empty(), changes).document();
    ContextDocument reloaded = ContextCodec.parse(ContextCodec.encode(first));
    assertThat(ContextChanges.merge(reloaded, changes).conflicts()).isEmpty();
    assertThat(ContextChanges.merge(reloaded, changes).document()).isEqualTo(reloaded);
  }

  @Test
  void reusedSourceRevisionCannotHideContradictoryValues() {
    ContextModel.Declaration original =
        declaration(SUBJECT, ContextModel.Field.CONFIDENTIALITY_YEARS, "5", "a");
    ContextModel.Declaration contradictory = declaration(SUBJECT, original.field(), "20", "a");
    ContextDocument.Resolution resolution =
        new ContextDocument.Resolution(
            SUBJECT,
            original.field(),
            "5",
            List.of(ContextDocument.SourceRef.of(original)),
            "reviewer",
            DATE);
    ContextDocument context =
        new ContextDocument(1, List.of(), List.of(original), List.of(resolution), List.of());
    Reconciliation.Result result =
        Reconciliation.resolve(SUBJECT, original.field(), List.of(contradictory), context);
    assertThat(result.status()).isEqualTo(Reconciliation.Status.CONFLICT);
    assertThat(result.value()).isEmpty();
    assertThat(result.sources()).hasSize(2);
  }

  @Test
  void contextRoundTripPreservesRetirementBindingsResolutionsAndAssertedReadiness() {
    ContextModel.Declaration declaration =
        declaration(SUBJECT, ContextModel.Field.CONFIDENTIALITY_YEARS, "20", "a");
    ContextDocument.Flow flow =
        new ContextDocument.Flow(
            SUBJECT,
            "Archive label",
            ContextDocument.State.RETIRED,
            List.of(
                new ContextModel.Binding(
                    "example.Archive",
                    "save",
                    "()V",
                    "example:archive",
                    ContextModel.Origin.MAIN)));
    ContextDocument.Resolution resolution =
        new ContextDocument.Resolution(
            SUBJECT,
            declaration.field(),
            "20",
            List.of(ContextDocument.SourceRef.of(declaration)),
            "reviewer",
            DATE);
    ContextDocument.Readiness readiness =
        new ContextDocument.Readiness(
            SUBJECT,
            ContextDocument.ReadinessState.VALIDATED_IN_TESTED_SCOPE,
            "Claim awaiting validation",
            declaration.source(),
            List.of("test-report:one"));
    ContextDocument context =
        new ContextDocument(
            1, List.of(flow), List.of(declaration), List.of(resolution), List.of(readiness));
    assertThat(ContextCodec.parse(ContextCodec.encode(context))).isEqualTo(context);
    assertThat(
            ContextChanges.parse(
                    ContextChanges.encode(ContextChanges.between(ContextDocument.empty(), context)))
                .base())
        .isEqualTo(ContextDocument.empty());
    assertThat(
            ContextChanges.merge(
                    ContextDocument.empty(),
                    ContextChanges.between(ContextDocument.empty(), context))
                .document())
        .isEqualTo(context);
    assertThatThrownBy(() -> new ContextDocument(2, List.of(), List.of(), List.of(), List.of()))
        .hasMessageContaining("Unsupported");
    assertThatThrownBy(
            () -> new ContextDocument(1, List.of(flow, flow), List.of(), List.of(), List.of()))
        .hasMessageContaining("Duplicate");
    assertThatThrownBy(() -> document(declaration, declaration)).hasMessageContaining("Duplicate");
  }

  @Test
  void contextRevisionIgnoresOrderAndOwnsLists() {
    ContextModel.Declaration owner = declaration(SUBJECT, ContextModel.Field.OWNER, "Team", "a");
    ContextModel.Declaration years =
        declaration(SUBJECT, ContextModel.Field.CONFIDENTIALITY_YEARS, "20", "b");
    assertThat(ContextCodec.revision(document(owner, years)))
        .isEqualTo(ContextCodec.revision(document(years, owner)));
    List<ContextModel.Declaration> input = new ArrayList<>(List.of(owner));
    ContextDocument retained = new ContextDocument(1, List.of(), input, List.of(), List.of());
    input.clear();
    assertThat(retained.declarations()).containsExactly(owner);
    assertThatThrownBy(() -> retained.declarations().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void disjointEditsMergeWithoutErasingCurrentFieldsAndReplayIsIdempotent() {
    ContextDocument base = ContextDocument.empty();
    ContextModel.Declaration owner = declaration(SUBJECT, ContextModel.Field.OWNER, "Team", "a");
    ContextModel.Declaration years =
        declaration(SUBJECT, ContextModel.Field.CONFIDENTIALITY_YEARS, "20", "b");
    ContextChanges.ChangeSet change = ContextChanges.between(base, document(years));
    ContextChanges.Merge merged = ContextChanges.merge(document(owner), change);
    assertThat(merged.conflicts()).isEmpty();
    assertThat(merged.document().declarations()).containsExactlyInAnyOrder(owner, years);
    assertThat(ContextChanges.merge(merged.document(), change).document())
        .isEqualTo(merged.document());
    assertThat(ContextChanges.parse(ContextChanges.encode(change))).isEqualTo(change);
  }

  @Test
  void conflictsAreCollectedAndDeletionRequiresTheMatchingBase() {
    ContextModel.Declaration oldOwner = declaration(SUBJECT, ContextModel.Field.OWNER, "A", "a");
    ContextModel.Declaration oldYears =
        declaration(SUBJECT, ContextModel.Field.CONFIDENTIALITY_YEARS, "5", "a");
    ContextDocument base = document(oldOwner, oldYears);
    ContextDocument proposed = document(declaration(SUBJECT, ContextModel.Field.OWNER, "B", "b"));
    ContextDocument current =
        document(
            declaration(SUBJECT, ContextModel.Field.OWNER, "C", "c"),
            declaration(SUBJECT, ContextModel.Field.CONFIDENTIALITY_YEARS, "20", "c"));
    ContextChanges.Merge merged =
        ContextChanges.merge(current, ContextChanges.between(base, proposed));
    assertThat(merged.document()).isNull();
    assertThat(merged.conflicts()).hasSize(2);
    assertThat(ContextChanges.merge(base, ContextChanges.between(base, proposed)).document())
        .isEqualTo(proposed);
  }

  @Test
  void forgedBaseHashesAndMovedIdentitiesAreRejected() {
    ContextModel.Declaration owner = declaration(SUBJECT, ContextModel.Field.OWNER, "Team", "a");
    ContextChanges.Key key =
        new ContextChanges.Key(
            ContextChanges.Section.DECLARATIONS, ContextDocument.declarationId(owner));
    assertThatThrownBy(
            () ->
                new ContextChanges.Edit(key, null, "a".repeat(64), ContextJson.declaration(owner)))
        .hasMessageContaining("hash mismatch");
    assertThatThrownBy(
            () ->
                new ContextChanges.Edit(
                    new ContextChanges.Key(ContextChanges.Section.DECLARATIONS, "wrong"),
                    null,
                    CanonicalJson.revision(null),
                    ContextJson.declaration(owner)))
        .hasMessageContaining("identity");
    ContextChanges.Edit deletion =
        new ContextChanges.Edit(
            key,
            ContextJson.declaration(owner),
            CanonicalJson.revision(ContextJson.declaration(owner)),
            null);
    assertThatThrownBy(
            () ->
                new ContextChanges.ChangeSet(
                    1,
                    ContextDocument.empty(),
                    ContextCodec.revision(ContextDocument.empty()),
                    List.of(deletion)))
        .hasMessageContaining("base field");
    assertThatThrownBy(
            () ->
                new ContextChanges.ChangeSet(1, ContextDocument.empty(), "b".repeat(64), List.of()))
        .hasMessageContaining("context hash");
    assertThatThrownBy(() -> new ContextChanges.Edit(key, null, CanonicalJson.revision(null), null))
        .hasMessageContaining("no change");
    Map<String, Object> extra =
        new java.util.TreeMap<>(
            CanonicalJson.object(
                CanonicalJson.decode(ContextCodec.encode(ContextDocument.empty()))));
    extra.put("destination", "/untrusted/path");
    assertThatThrownBy(() -> ContextCodec.parse(CanonicalJson.encode(extra)))
        .hasMessageContaining("Unexpected JSON fields");
  }

  @Test
  void applicationDefaultsAreLabeledAndCannotOverwriteSpecificRequirements() {
    ContextModel.Declaration broad =
        declaration(DEFAULT, ContextModel.Field.CONFIDENTIALITY_YEARS, "5", "a");
    Reconciliation.Result assumed =
        Reconciliation.resolve(SUBJECT, broad.field(), List.of(), document(broad));
    assertThat(assumed.status()).isEqualTo(Reconciliation.Status.APPLICATION_DEFAULT);
    assertThat(assumed.specific()).isFalse();
    ContextModel.Declaration specific = declaration(SUBJECT, broad.field(), "20", "b");
    Reconciliation.Result effective =
        Reconciliation.resolve(SUBJECT, broad.field(), List.of(specific), document(broad));
    assertThat(effective.value()).isEqualTo("20");
    assertThat(effective.sources()).containsExactlyInAnyOrder(broad, specific);
    assertThat(effective.specific()).isTrue();
    assertThat(
            Reconciliation.resolve(SUBJECT, ContextModel.Field.OWNER, List.of(), document(broad))
                .status())
        .isEqualTo(Reconciliation.Status.UNKNOWN);
  }

  @Test
  void agreeingSourcesRemainPreservedAndChangedRevisionsInvalidateResolution() {
    ContextModel.Declaration user =
        declaration(SUBJECT, ContextModel.Field.CONFIDENTIALITY_YEARS, "20", "a");
    ContextModel.Declaration annotation =
        new ContextModel.Declaration(
            SUBJECT,
            user.field(),
            "5",
            source(ContextModel.SourceKind.ANNOTATION, "example.Archive", "b"));
    assertThat(
            Reconciliation.resolve(SUBJECT, user.field(), List.of(annotation), document(user))
                .conflict())
        .isTrue();
    ContextDocument.Resolution resolution =
        new ContextDocument.Resolution(
            SUBJECT,
            user.field(),
            "20",
            List.of(ContextDocument.SourceRef.of(user), ContextDocument.SourceRef.of(annotation)),
            "reviewer",
            DATE);
    ContextDocument resolved =
        new ContextDocument(1, List.of(), List.of(user), List.of(resolution), List.of());
    Reconciliation.Result effective =
        Reconciliation.resolve(SUBJECT, user.field(), List.of(annotation), resolved);
    assertThat(effective.status()).isEqualTo(Reconciliation.Status.REVIEWED);
    assertThat(effective.value()).isEqualTo("20");
    ContextModel.Declaration changed =
        new ContextModel.Declaration(
            SUBJECT,
            user.field(),
            "10",
            source(ContextModel.SourceKind.ANNOTATION, "example.Archive", "c"));
    assertThat(Reconciliation.resolve(SUBJECT, user.field(), List.of(changed), resolved).status())
        .isEqualTo(Reconciliation.Status.STALE_RESOLUTION);
    ContextModel.Declaration agreed =
        new ContextModel.Declaration(SUBJECT, user.field(), "20", annotation.source());
    assertThat(
            Reconciliation.resolve(SUBJECT, user.field(), List.of(agreed, agreed), document(user))
                .sources())
        .hasSize(2);
    assertThat(
            Reconciliation.resolve(SUBJECT, user.field(), List.of(agreed), document(user)).status())
        .isEqualTo(Reconciliation.Status.AGREED);
    ContextModel.Declaration unknown =
        declaration(SUBJECT, ContextModel.Field.PURPOSE, "UNSPECIFIED", "a");
    assertThat(
            Reconciliation.resolve(
                    SUBJECT, unknown.field(), List.of(unknown), ContextDocument.empty())
                .status())
        .isEqualTo(Reconciliation.Status.UNKNOWN);
  }

  @Test
  void managedApplyWritesOnlyConflictFreeResultsAndRequiresDestination(@TempDir Path directory)
      throws Exception {
    Path destination = directory.resolve("private/context.json");
    ContextDocument base = ContextDocument.empty();
    ContextDocument first = document(declaration(SUBJECT, ContextModel.Field.OWNER, "A", "a"));
    ContextChanges.ChangeSet change = ContextChanges.between(base, first);
    assertThatThrownBy(() -> ContextStore.apply(null, change))
        .hasMessageContaining("explicit durable");
    assertThat(ContextStore.apply(destination, change).written()).isTrue();
    assertThat(ContextCodec.read(destination)).isEqualTo(first);
    assertThat(ContextStore.apply(destination, change).written()).isFalse();
    byte[] before = Files.readAllBytes(destination);
    ContextDocument conflicting =
        document(declaration(SUBJECT, ContextModel.Field.OWNER, "B", "b"));
    ContextStore.Applied conflict =
        ContextStore.apply(destination, ContextChanges.between(base, conflicting));
    assertThat(conflict.merge().conflicts()).hasSize(1);
    assertThat(conflict.written()).isFalse();
    assertThat(Files.readAllBytes(destination)).isEqualTo(before);
  }

  @Test
  void missingDestinationCannotSilentlyDropUneditedBaseAnswers(@TempDir Path directory)
      throws Exception {
    ContextModel.Declaration owner = declaration(SUBJECT, ContextModel.Field.OWNER, "Team", "a");
    ContextDocument base = document(owner);
    ContextDocument proposed =
        document(owner, declaration(SUBJECT, ContextModel.Field.CLASSIFICATION, "sensitive", "b"));
    Path destination = directory.resolve("mistyped-context.json");
    ContextChanges.ChangeSet changes = ContextChanges.between(base, proposed);
    assertThatThrownBy(() -> ContextStore.apply(destination, changes))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("destination is missing")
        .hasMessageContaining("original context file");
    assertThat(Files.exists(destination)).isFalse();
    Files.write(destination, ContextCodec.encode(base));
    assertThat(ContextStore.apply(destination, changes).written()).isTrue();
    assertThat(Files.readAllBytes(destination)).isEqualTo(ContextCodec.encode(proposed));
  }

  @Test
  void lockContentionAndSymlinkInputsCannotChangeContext(@TempDir Path directory) throws Exception {
    Path destination = directory.resolve("context.json");
    Path lockPath = directory.resolve("context.json.census.lock");
    ContextChanges.ChangeSet changes =
        ContextChanges.between(
            ContextDocument.empty(),
            document(declaration(SUBJECT, ContextModel.Field.OWNER, "Team", "a")));
    try (FileChannel channel =
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = channel.lock()) {
      assertThat(lock.isValid()).isTrue();
      assertThatThrownBy(() -> ContextStore.apply(destination, changes))
          .isInstanceOf(java.io.IOException.class)
          .hasMessageContaining("locked");
      assertThat(Files.exists(destination)).isFalse();
    }
    Path actual = directory.resolve("actual.json");
    Files.write(actual, ContextCodec.encode(ContextDocument.empty()));
    Files.createSymbolicLink(destination, actual);
    assertThatThrownBy(() -> ContextStore.apply(destination, changes))
        .isInstanceOf(java.io.IOException.class);
    assertThat(ContextCodec.read(actual)).isEqualTo(ContextDocument.empty());
  }
}
