# TLS correlation

TLS correlation is optional enrichment. Enable it with `enrichment=true,tlsCorrelation=true`; the original inventory collector remains independent. The enrichment installer opens only `java.base/sun.security.ssl` to its own module and uses a separate transformer. An unsupported hook disables TLS enrichment without undoing the primary inventory transformer.

## Evidence semantics

Each observed `HandshakeContext` belongs to one transport and one attempt. Connection identifiers are opaque, unique within the run, and unrelated to TLS session identifiers. A successful `TransportContext.finishHandshake` records a completed local endpoint. Session construction, ticket derivation and post-handshake key updates do not count as completed handshakes. Fatal failures are failed attempts; close, collection timeout and shutdown before completion are unknown outcomes with explicit diagnostics.

The collector snapshots the initiating scope when JSSE constructs its handshake context. This identifies the execution scope that initiated the attempt. It does not establish which later requests or records use a pooled connection. Sockets and engines share the same transport ownership model. A dynamic owner is installed only inside the owning handshake's dispatch or kickstart method, and restored on every return or exception. Delegated engine tasks therefore associate JCA assets with their own handshake without inheriting application scopes from another thread.

For TLS 1.3 the selected group comes from ServerHello's key-share extension. The cipher suite and offered groups cannot substitute for it. Local CertificateVerify production and successful peer verification have separate fields. A resumed handshake does not inherit a prior CertificateVerify as a newly observed signature. A fresh key share and PSK resumption can both be true.

Ticket-derived session objects retain a weak identity relation to their parent session. A completed in-process origin can supply an opaque attempt reference for subsequent client resumption. A stateless server ticket, restored cache or unavailable parent remains an unknown PSK origin. The collector does not inspect ticket bytes, raw session IDs, signatures, key material or record payloads. Lineage traversal is bounded and rejects cycles or excessive depth.

## Bounds and lifecycle

The collector retains at most 4096 completed plus pending attempts, 64 related asset references per attempt, and 8192 weak ticket/session associations. Dynamic ownership uses a separate instance of the tested scope stack, capped at 32 frames; overflow masks attribution until the outermost call exits. Transport and handshake indexes use weak identity keys; attempt state never strongly retains those objects. A five-minute observation timeout is checked opportunistically during creation and snapshotting. No timer thread or network activity is introduced. Shutdown finalizes pending attempts without waiting for the TLS connection to finish.

Capacity loss and collector failures update enrichment health. Runtime hook capability is reported separately from each attempt's observed outcome. Recorded callback failures degrade TLS capability even if the external health sink itself fails. Entry and restoration faults invalidate ownership rather than exposing an outer or stale attempt. Completed evidence and its lineage remain immutable after snapshotting.

## Support boundaries

The exact feasibility hooks and reproduction matrix are documented in [TLS-CORRELATION-FEASIBILITY.md](TLS-CORRELATION-FEASIBILITY.md). `TlsProductionIT` exercises the production transformer and collector using real socket connections, resumption, rejected certificates, and concurrent engines sharing a delegated-task executor. Its runtime override properties match the feasibility fixture, including actual `X25519MLKEM768` negotiation on a supporting JDK.

The TLS 1.3 SunJSSE adapter exposes selected groups and directional CertificateVerify schemes. TLS 1.2 completion/resumption is partial evidence: selected group and signature scheme remain unobserved. Other providers have no verified adapter. Unknown information stays unknown rather than being inferred from provider availability, supported algorithms, or nearby thread activity.

Full-agent tests must additionally verify that retained inventory asset references join correctly, scope declarations remain resolvable, sidecar validation preserves exact counts, and disabling enrichment preserves ordinary CBOM behavior. The isolated production fixture covers the TLS adapter itself; it does not replace those integration checks.

## Canonical assessment facts

`TlsPolicy` indexes one run's bounded attempt list and returns `TlsFacts` independently of business priority. Version `tls-facts-v1` recognizes selected groups 29 (x25519) and 4588 (X25519MLKEM768), with exact classical TLS 1.3 signature names from the [IANA TLS Parameters registry](https://www.iana.org/assignments/tls-parameters/). Unknown groups, contradictory names and unknown signatures stay unestablished. Completion and a verified fresh exchange are prerequisites for selected-group exposure facts.

The facts keep selected exchange, conservative composite confidentiality, current local/peer authentication, and PSK-origin exposure separate. Missing or invalid ancestry prevents an overall resistant confidentiality claim. A classical origin combined with a fresh hybrid exchange remains an explicit mixed-protection limitation; it does not label that hybrid exchange vulnerable. Current signature facts never inherit an origin's signature as a new operation. Callers must qualify these facts with collection capabilities and health, and retain individual JCA findings.
