# Crypto Census

**Find out what cryptography your Java application *actually* uses, and what a quantum computer will break.**

Crypto Census is a `-javaagent` that observes the JCA/JSSE entry points your JVM goes through
(cipher, signature, key-agreement and KEM initialization, key generation, digests, MACs, TLS
sessions) and writes a [CycloneDX 1.6 CBOM](https://cyclonedx.org/capabilities/cbom/)
(Cryptography Bill of Materials). Each asset gets a post-quantum readiness verdict and the line of
code that used it.

```
[broken            ] signature       SHA1withRSA-2048 (sign)      SHA-1 signature generation is disallowed (SP 800-131A)
                       at com.acme.payments.LegacySigner.sign:31                     x1
[quantum-vulnerable] signature       SHA256withRSA-2048 (sign)    RSA is broken by Shor's algorithm; migrate to ML-KEM / ML-DSA
                       at com.acme.payments.PaymentSigner.sign:25                    x1
[weak              ] cipher          AES-256 (encrypt)            No mode specified; JCA providers default to ECB
                       at com.acme.payments.Vault.seal:18                            x1
[quantum-safe      ] cipher          AES-256-GCM (encrypt)        AES-GCM with a 256-bit key (CNSA 2.0)
                       at com.acme.payments.CardVault.seal:31                        x1
[acceptable        ] tls             TLSv1.3 TLS_AES_256_GCM_SHA384
                       at <jdk> (via jsse)                                           x2
[quantum-vulnerable] key-agreement   X25519
                       at <jdk> (via jsse)                                           x1
```

Apache 2.0. No paid tier. No server to run.

## Why

Regulators are asking banks and other critical infrastructure for a **cryptographic inventory** as the
first step of post-quantum migration (NIST FIPS 203/204/205, NSA CNSA 2.0, the EU PQC roadmap, the
financial sector's "harvest now, decrypt later" warnings). The open-source tools that exist today
scan *source code*. They cannot see:

- algorithms chosen at runtime: `Cipher.getInstance(config.get("alg"))`
- key sizes that come from keystores or configuration
- what your libraries do on your behalf: JDBC drivers, HTTP clients, SAML/JWT libraries
- what TLS actually negotiated with the systems you talk to

Crypto Census watches the running JVM, so it reports what *happened*, not what the code looks like it
might do. It complements static scanners; it does not replace them (code paths your tests never run
are not observed).

## Quick start

### Maven (recommended): inventory your test suite

```xml
<plugin>
  <groupId>io.github.cryptocensus</groupId>
  <artifactId>census-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <goals>
        <goal>prepare-agent</goal>   <!-- attaches the agent to Surefire/Failsafe via argLine -->
        <goal>check</goal>           <!-- merges per-JVM CBOMs, prints findings, enforces policy -->
      </goals>
    </execution>
  </executions>
  <configuration>
    <failOn>broken</failOn>          <!-- broken | weak | quantum-vulnerable | none -->
    <allow>
      <algorithm>SHA-1</algorithm>   <!-- signed-off exceptions -->
    </allow>
  </configuration>
</plugin>
```

`mvn verify` writes `target/cryptocensus/cbom.json`. To see what *would* block a post-quantum
migration:

```
mvn verify -Dcensus.failOn=quantum-vulnerable
```

| `check` option                | Default  | Meaning                                                                 |
|-------------------------------|----------|-------------------------------------------------------------------------|
| `failOn`                      | `broken` | Lowest risk that fails the build, or `none`                             |
| `failOnUnknown`               | `true`   | Fail on assets the policy cannot judge (unrecognized, or key strength not observable) |
| `failOnMissingData`           | `true`   | Fail when tests ran but no agent data exists (agent never attached)     |
| `failOnIncompleteCollection`  | `true`   | Fail when the agent reports instrumentation errors, dropped assets, or calls still running at shutdown |
| `allow`                       |          | Algorithms accepted regardless of risk                                  |
| `ignoreJdkInternal`           | `false`  | Ignore assets with no application frame (jar verification, PKIX)       |

#### What `check` guarantees

`check` fails the build when any of these is true:

1. an observed asset is at or above `failOn`, or is `unknown` (with the default `failOnUnknown=true`);
2. an input in this build's run directory is not valid agent evidence: wrong schema, a missing,
   non-numeric or inconsistent health counter, a component without Crypto Census properties, or
   standard CycloneDX fields that contradict them;
3. the agent reported instrumentation errors, recording failures, dropped assets, or calls still
   running at shutdown (`failOnIncompleteCollection`);
4. there is no evidence at all and it cannot be *established* that no tests ran
   (`failOnMissingData`). Established means: no compiled tests, or an explicit `skipTests` /
   `maven.test.skip` / `skipITs` with no test report written during this build. Silence does not
   count, so `forkCount=0` with reports disabled still fails.

The run directory is bound to the current build (Maven project context), so neither a POM property
nor evidence from an earlier build can satisfy `check`. Running `prepare-agent` again before Failsafe
keeps the unit-test results.

Not detected (by design or not yet):

- one test JVM with the agent cannot vouch for a *different* execution that never had it (for
  example a Failsafe execution with its own hard-coded `argLine`) when the other produced evidence;
- tests run with reporting disabled *and* an explicit skip property set;
- code paths your tests never exercise;
- hostile code inside the observed JVM (see Known limitations).

#### Combining with your own `argLine`

A `-DargLine=...` on the command line replaces the agent option and Maven gives plugins no way to
undo that, so the build will fail with an explanation. Keep JVM options in the POM, or give the agent
its own property and compose them with Surefire's late binding:

```xml
<!-- census-maven-plugin -->
<configuration><propertyName>censusArgLine</propertyName></configuration>

<!-- maven-surefire-plugin -->
<configuration><argLine>@{censusArgLine} -Xmx1g</argLine></configuration>
```

### Any JVM: staging, a batch job, an app server

```
java -javaagent:census-agent.jar=output=cbom.json,app=payments-api -jar payments-api.jar
```

The CBOM is written when the JVM exits.

| Option      | Default                  | Meaning                                                              |
|-------------|--------------------------|----------------------------------------------------------------------|
| `output`    | `cryptocensus-cbom.json` | CBOM file to write on exit                                           |
| `dir`       |                          | Write `cbom-<pid>-<random>.json` into this directory instead         |
| `app`       |                          | Application name recorded in CBOM metadata                           |
| `summary`   | `true`                   | Print the findings table to stderr on exit                           |
| `tls`       | `true`                   | Record negotiated TLS sessions                                       |
| `maxAssets` | `500`                    | Distinct assets kept; beyond this observations are dropped, counted, and fail `check` as incomplete |
| `maxSites`  | `50`                     | Call sites kept per asset; beyond this they fold into `<overflow>` (counted; `check` warns) |
| `debug`     | `false`                  | Log instrumentation errors                                           |

Option values are percent-decoded: write `,` `=` `%` and spaces in paths as `%2C` `%3D` `%25` `%20`
(the Maven plugin does this for you).

## What is observed

| JCA/JSSE entry point                                | Recorded as         | Also captured                          |
|-----------------------------------------------------|---------------------|----------------------------------------|
| `Cipher.init`                                       | cipher              | mode, padding, key size, encrypt/decrypt |
| `Signature.initSign` / `initVerify`                 | signature           | key size, sign/verify                  |
| `KeyAgreement.init`                                 | key-agreement       | curve or group size (X25519, P-256)    |
| `KEM.newEncapsulator` / `newDecapsulator` (JDK 21+) | kem                 | parameter set (ML-KEM-768)             |
| `KeyPairGenerator.generateKeyPair` (any provider)   | key-pair-generation | generated key size / parameter set     |
| `KeyGenerator.generateKey`                          | key-generation      | key size                               |
| `Mac.init`                                          | mac                 | key size                               |
| `MessageDigest.getInstance`                         | message-digest      |                                        |
| JSSE session establishment                          | tls                 | protocol version, cipher suite         |

**Counts are entry-point events**, not bytes or operations: one `Cipher.init` followed by a thousand
`doFinal` calls is one event, and a digest that is created but never used still counts. Only the
outermost JCA call is counted, so `Cipher.init` delegating to another overload is one event.

Each event is attributed to the **first application frame** on the stack (decided by class loader,
not package name), plus the JDK subsystem it went *via*: `jsse`, `jar-verification`,
`pki-validation`, `secure-random`.

Algorithm names are **canonicalized** through the provider that served the call
(`Provider.getService(type, name).getAlgorithm()`), so any alias or OID the provider accepts
(`SHA`, `DSS`, `1.3.14.3.2.29`, ...) is reported under its standard name. A static table covers the
same for CBOMs read without a live provider. A name that still cannot be resolved is `unknown`, which
fails the build by default.

## Risk policy

Policy `cryptocensus-default/5`, recorded in every CBOM. It uses allow-lists only: algorithms, block
cipher modes, paddings, signature key types and TLS suites must each be recognized, or the verdict is
`unknown`. Hash and key-size rules are evaluated separately per operation and the more severe wins.

| Risk                 | Examples                                                                 | Basis                    |
|----------------------|--------------------------------------------------------------------------|--------------------------|
| `broken`             | DES, RC4, MD5, SHA-1 *signing*, DSA *signing* (FIPS 186-5), TDEA *encryption*, RSA key transport below 2048 bits or with PKCS#1 v1.5 padding (*encryption and decryption*, SP 800-131A §6), RSA signing < 2048, RSA verify < 1024, DSA verify < 512, HMAC/KMAC keys < 112 bits, TLS 1.0/1.1 | SP 800-131A Rev. 2, RFC 8996 |
| `weak`               | ECB (including a bare `"AES"`, which defaults to ECB), PCBC, SHA-1 digests, legacy use only (SHA-1 *verify*, TDEA *decrypt*, RSA 1024–2047 *verify*, DSA 512–2047 *verify*, ECDSA 160–223 *verify*), Blowfish, CBC/static-RSA TLS suites | SP 800-131A Table 2, Sweet32 |
| `quantum-vulnerable` | RSA, ECDSA, ECDH, X25519, Ed25519, DH, DSA verification                  | Shor's algorithm         |
| `acceptable`         | AES-128/192, SHA-256, HMAC-SHA-256, TLS 1.2/1.3 AEAD suites               | classically secure       |
| `quantum-safe`       | ML-KEM, ML-DSA, SLH-DSA, LMS/XMSS, 256-bit symmetric keys, SHA-384/512   | FIPS 203/204/205, SP 800-208 |
| `unknown`            | unrecognized names, modes, paddings or TLS suite/protocol combinations; pre-standard Kyber/Dilithium; HMAC/KMAC or RSA/DSA/EC/DH keys whose size could not be observed (e.g. PKCS#11 token keys); PBE without an observed work factor | needs a human |

**Quantum-safe is not the same as CNSA 2.0.** CNSA 2.0 is a specific list: AES-256, SHA-384/512,
ML-KEM-1024 and ML-DSA-87, plus LMS/XMSS for software and firmware signing. ML-KEM-768, SHA3-384 and
Camellia-256 are quantum-resistant but *not* CNSA 2.0. The CBOM records this separately as
`cryptocensus:cnsa2`; LMS/XMSS are never flagged, because their parameter set and purpose are not
observable.

An AES key whose size was not observed stays `acceptable`: AES only has 128/192/256-bit keys, so the
missing size cannot hide a weak key. An HMAC or public key whose size was not observed is `unknown`,
because it can.

**Unknown is not a severity.** When the policy combines independent findings about one asset (hash
and key size of a signature, padding and key size of RSA encryption), a definite `broken` still wins,
but otherwise `unknown` is kept rather than hidden behind a lesser finding: SHA-1 *verification* with
an unobservable key size is `unknown`, not `weak`.

This profile is deliberately **stricter than SP 800-131A** in one respect: all classical public-key
cryptography is reported as quantum-vulnerable.

## Safety in production-like environments

- **Observe only.** Advice is wrapped with `suppress = Throwable.class` and never changes arguments,
  return values or exceptions. Secret key material is only read from a short list of reviewed JDK key
  classes (`SecretKeySpec`, SunJCE's DES/DESede keys), whose `getEncoded()` returns a copy that is then
  wiped. Third-party keys are never exported; PKCS#11 (HSM/token) keys are not inspected at all.
- **Bounded memory.** Distinct assets and call sites are capped (`maxAssets`, `maxSites`); names are
  truncated (after normalization, on character boundaries). Dropped assets make the collection
  incomplete and fail `check`; call sites beyond `maxSites` lose only their attribution (the asset and
  its verdict are kept), so `check` warns. The CBOM parser bounds depth, total values, estimated
  retained memory and bytes read; raising `maxAssets` far beyond the default can exceed those bounds.
- **Namespaced.** Every class in the agent jar lives under `io.github.cryptocensus.agent` (Byte Buddy and
  the core library are relocated), so adding it to the bootstrap class path cannot shadow your classes.
- **Safe output.** A CBOM is written through the single handle that exclusively created a random
  temporary file (`CREATE_NEW`, which never follows or reuses an existing entry), then atomically renamed
  into place: a symlink or FIFO at the destination is replaced, never opened. On POSIX the file is
  `0600`; on Windows it inherits the directory's ACL, so keep output in a directory only you can read.
  Inputs larger than 16 MB are refused; a CBOM at the default caps is about 5 MB.
  The output directory itself is trusted: someone who can rename entries inside it can still swap
  files around.
- **Fails open, reports honestly.** If the agent cannot start, it logs one line and the application
  runs normally; if anything went wrong while recording, the CBOM says so (`cryptocensus:transformErrors`,
  `recordingFailures`, `dropped`).
- **Installs once.** A repeated `-javaagent` or attach is ignored.
- Compiled for **Java 17**. CI runs 17, 21 and 25 on Linux and Windows; also tested locally on 23.

## Known limitations

- **Self-reported evidence.** The agent runs inside the application it observes; hostile code in the
  same JVM could suppress or forge observations, and anything running as the same OS user could edit
  the output. Treat a CBOM as developer evidence. For audit-grade provenance, collect it with a trusted
  pipeline and sign it outside the observed JVM (the merged CBOM records a SHA-256 of every input).
- **Coverage = what ran.** Code paths your tests or workload never exercise are not observed.
- **Alias resolution needs the provider.** A provider that registers an algorithm under a
  non-standard canonical name will be reported (and judged) under that name.
- **TLS key-exchange group.** The TLS asset records protocol and cipher suite. The negotiated group
  (`X25519`, `secp256r1`, `X25519MLKEM768`) shows up as separate key-agreement / KEM assets `via jsse`,
  so a hybrid ML-KEM handshake appears as an ML-KEM asset *and* an X25519 asset.
- **TLS sessions are recorded when JSSE creates them**, which is before the handshake has finished
  authenticating; a handshake that later fails is still counted. TLS 1.3 resumption tickets add
  session objects.
- **Not captured:** PSS/OAEP parameters, GCM tag length, PBE iteration counts, the named curve behind
  an EC key (only its size), and a DSA key's subgroup size N (only the modulus L), so the N conditions
  of SP 800-131A Table 2 are not assessed.
- **Shutdown boundary.** Collection ends when the agent's shutdown hook runs; crypto performed by other
  shutdown hooks may be missed.
- **CDS warning (JDK 17+).** Like other agents that extend the bootstrap class path (OpenTelemetry's,
  for example), the JVM prints `Sharing is only supported for boot loader classes because bootstrap
  classpath has been appended`. It is harmless; class-data sharing stays on for JDK classes.
- **Overhead** has not been benchmarked yet. Each recorded event walks the stack. The agent is aimed
  at test suites and staging today.
- Bouncy Castle's **lightweight API** (`org.bouncycastle.crypto.*`) bypasses the JCA and is not
  observed. Bouncy Castle used **as a JCA provider** is.

## Roadmap

- Gradle plugin
- Correlate the TLS key-exchange group with its session (detect hybrid ML-KEM)
- Capture PSS/OAEP/GCM/PBE parameters and named EC curves
- Periodic and on-demand CBOM flush (JMX) for long-running servers
- Sampling mode and benchmarks for production use
- Keystore and truststore inventory (certificates, key sizes, expiry)
- Conformance matrix across app servers (WildFly, Open Liberty, Tomcat) and JDK vendors

## Building

```
mvn verify
```

Modules:

- `census-core`: model, canonical names, risk policy, CBOM read/write. Zero runtime dependencies;
  output is validated against the official CycloneDX 1.6 schema in tests.
- `census-agent`: the `-javaagent` (shaded jar), with integration tests that run real JVMs.
- `census-maven-plugin`: `prepare-agent` and `check` goals, with Maven Invoker tests for argLine
  composition, odd paths, missing data and failure modes.
- `census-demo`: a toy payments service that runs the plugin on itself.

## License

[Apache License 2.0](LICENSE)
