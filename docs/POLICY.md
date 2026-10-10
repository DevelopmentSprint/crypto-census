# Observation and risk policy

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
`pki-validation`, `secure-random`. Frames of JCA providers are skipped by package, whichever class
loader defined them (OpenJCEPlus on Semeru and HSM vendors' providers are not JDK classes), and
Bouncy Castle's TLS stack (BCJSSE) counts as `via jsse`.

Algorithm names are **canonicalized** through the provider that served the call
(`Provider.getService(type, name).getAlgorithm()`), so any alias or OID the provider accepts
(`SHA`, `DSS`, `1.3.14.3.2.29`, ...) is reported under its standard name. A static table covers the
same for CBOMs read without a live provider. A name that still cannot be resolved is `unknown`, which
fails the build by default.

## Risk policy

Policy `cryptocensus-default/6`, recorded in every CBOM. It uses allow-lists only: algorithms, block
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

Raw `NONEwith*` signatures have an unobserved external hash and are `unknown`, unless an independently broken key makes the verdict `broken`. Unrecognized signature hashes and RSA paddings also preserve independently broken key findings. An unrecognized signature key type preserves a recognized broken hash, including MD5 or SHA-1 signing. An [explicit exception](MAVEN.md#reviewed-exceptions) is available when you have reviewed the external hashing.

TLS classification requires a recognized protocol before evaluating its suite. An unrecognized protocol/suite combination stays `unknown` and fails the default gate; the policy does not infer suite security for a protocol it cannot classify.
