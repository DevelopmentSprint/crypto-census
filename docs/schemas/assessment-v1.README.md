# Assessment output v1

[assessment-v1.schema.json](assessment-v1.schema.json) describes the deterministic
JSON emitted by `AssessmentEngine` and `AssessmentReport`. It references the
existing context and evidence definitions in this directory; validators need
all three schema documents. The executable contract test supplies them from an
in-memory registry that rejects unknown resource locations, without fetching
schemas from the network.

The root contains the full policy profile, version dependencies, context and
profile revisions, run provenance, findings, flow summaries, edit base context
and field hashes, and explicit gaps and limitations. Primitive findings carry
nullable reconciled entry-point counts and ordered scope paths. TLS findings
have separate confidentiality, local-authentication and peer-authentication
axes; their `entryPointCount` is always null. Flow counts and handshake attempt
counts remain independent.

Strict object shapes reject unknown fields. Nullable values distinguish an
unknown count or absent identity/subject from zero or an established
association. Limits mirror the producer where it has explicit per-collection
bounds; the canonical JSON codec separately enforces aggregate size and nesting
budgets. Structural schema validation does not authenticate provenance,
recompute hashes or policy decisions, validate referential integrity, or prove
readiness and coverage. Use the Java input/context/evidence codecs for those
contracts.

The [synthetic dataset](../../census-assessment/src/test/resources/assessment/README.md)
contains a full paired-input report plus readable golden rule/reason/next-action
rows. Tests validate both the representative and empty report, reject malformed
versions, shapes, priorities and negative counts, and prohibit attaching an
entry-point count to a TLS row. Input replay and independent ordering/priority
assertions keep the schema check from being a purely structural snapshot test.
