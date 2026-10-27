# TLS correlation feasibility

The executable `TlsCorrelationFeasibilityIT` proves object-owned TLS 1.3 correlation for SunJSSE sockets and engines. It is a test-only javaagent, separate from the production installer. It establishes exact hooks for the production collector; it does not claim that production TLS evidence is already implemented.

## Verified runtime evidence

| Runtime | Selected group | Result |
| --- | --- | --- |
| Homebrew OpenJDK 17.0.18 | x25519 (29) | Same classical fixture |
| Homebrew OpenJDK 21.0.10 | x25519 (29) | Same classical fixture |
| Homebrew OpenJDK 25.0.2 | x25519 (29) | Socket full handshake, resumption, trust failure, concurrent engines and delegated tasks |
| Oracle JDK 25.0.5.0.3+1-LTS-2 | X25519MLKEM768 (4588 / 0x11ec) | Same fixture with actual hybrid negotiation |

Each successful fixture records eight completed local endpoints: two socket connections and two concurrent engine connections, with both client and server observed. Exactly two socket endpoints are resumed. An additional untrusted socket connection records two failed endpoints. The engine connections share one delegated-task executor, proving that thread identity cannot serve as connection identity. Peer signature verification runs on that executor and stays associated with the correct handshake.

The Oracle runtime was downloaded into ignored `census-agent/target/toolchains` from the [official archive endpoint](https://download.oracle.com/java/25/latest/jdk-25_macos-aarch64_bin.tar.gz), with the [official SHA-256](https://download.oracle.com/java/25/latest/jdk-25_macos-aarch64_bin.tar.gz.sha256) verified before extraction: `453e3a7744c1cf2ceec984a70f35b18a3c6591014b12ccf12350430d63d4575d`. The latest URL is mutable; this version and checksum identify the tested artifact. No runtime archive is committed. Oracle documents the hybrid backport in its [25.0.5 release notes](https://www.oracle.com/java/technologies/javase/25-0-5-relnotes.html). Installed 25.0.2's lack of a hybrid TLS group is a runtime capability limitation, not a universal collector limitation.

## Exact hooks and ownership

These signatures and fields were checked against both tested JDK 25 runtimes' `java.base` source and exercised on all listed runtimes.

| Hook | Captured structural evidence |
| --- | --- |
| `sun.security.ssl.TransportContext.finishHandshake()` | Capture its `handshakeContext` on entry, before the implementation clears it. On successful return require `isNegotiated`; read `conSession` protocol and cipher suite. The transport owns the connection. |
| `TransportContext.fatal(Alert, String, boolean, Throwable)` | Capture a failed active handshake before it is cleared; exclude `PostHandshakeContext`. Never treat failure as successful negotiation. |
| `CertificateVerify$T13CertificateVerifyMessage(HandshakeContext, ByteBuffer)` | Successful constructor return means peer signature verification succeeded. Read only `signatureScheme.name`, associate with the supplied handshake object. |
| `CertificateVerify$T13CertificateVerifyMessage(HandshakeContext, X509Possession)` | Successful constructor return supplies the locally produced CertificateVerify scheme, separately from peer authentication. |
| `SSLSessionImpl(SSLSessionImpl, SessionId)` | A derived session has an object-identity relation to its parent. This is ticket lineage metadata, not another handshake. |

`HandshakeContext.conContext` joins signature events to the owning transport, including delegated tasks. `handshakeExtensions[SH_KEY_SHARE].serverShare.namedGroupId` is the ServerHello selection, not the offered groups list. It is present for full and PSK-with-fresh-key-share handshakes in both tested runtimes. `isResumption` and `resumingSession` describe the current attempt. A resumed attempt must not invent a new CertificateVerify operation.

The [SSLEngine contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/SSLEngine.html) permits delegated tasks to execute separately. The fixture deliberately reuses a task thread across concurrent connections. All joins use object identity; no thread-local relationship is assumed.

## Production evidence contract

Use an opaque per-run connection identifier plus a monotonically increasing attempt sequence, with endpoint role, transport kind, outcome, protocol and cipher suite. Preserve selected group identifier/name separately from cipher suite. Record local and peer signature schemes separately: a server without client authentication legitimately has only a local CertificateVerify scheme.

Resumption, fresh key exchange and ticket-origin knowledge need independent known/unknown states. A known selected key share supports a fresh exchange even on a resumed handshake. The fixture asserts that a resumed client references a captured ticket-derived session by identity, but does not yet prove a complete PSK-origin chain across cache restoration, stateless server tickets or process boundaries. Unknown lineage must remain unknown. Never carry a prior signature into the current attempt as a newly observed signature.

Associate operation evidence through the owning handshake to produce related asset references. Keep observed cryptographic facts distinct from runtime capability, missing-field diagnostics and later business-context policy. A local successful finish is evidence of that endpoint's established session, not proof of remote application acceptance.

## Reproduction

Build with the repository's required JDK 25 and Java 17 target:

```sh
./mvnw -B -ntp -pl census-agent -am test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=TlsCorrelationFeasibilityIT -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dgitbuildhook.gitconfig.skip=true
```

To exercise a separate hybrid-capable runtime, add:

```sh
-Dcensus.tls.java=/absolute/path/to/jdk/bin/java \
-Dcensus.tls.group=X25519MLKEM768 -Dcensus.tls.groupId=4588
```

The test builds its probe and bootstrap bridge jars in a temporary directory, generates local test credentials, and launches a child JVM. Only structural metadata is retained. It never reads key material, signature bytes, session identifiers, ticket bytes or record payloads into evidence.

## Production work still required

The bounded probe intentionally uses strong identity references for its short-lived child JVM and lets instrumentation errors fail the fixture. Production must replace these with bounded weak-identity ownership, terminal-attempt cleanup, duplicate suppression and per-feature diagnostics; all advice must preserve application behavior on collector failure. Reflection access is granted explicitly by the child JVM's `--add-opens`; production module opening and bootstrap isolation need independent verification.

Capability detection must validate exact hook descriptors and required fields per provider/runtime. TLS 1.2 groups, other providers, external PSKs, HelloRetryRequest, key updates, client authentication, application-context attachment and operation-to-asset joins are not demonstrated by this fixture. Neither supported-group offers nor a PQ-capable provider alone justify reporting a negotiated hybrid group. Unavailable features must be reported specifically while supported TLS 1.3 evidence remains usable.
