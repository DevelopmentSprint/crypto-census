# Enterprise delivery operations

Use CI to collect and preserve the original runtime CBOM, then deliver it with the Maven plugin
or standalone CLI. A runtime CBOM records observed cryptographic use; keep the separately generated
software inventory too. See [delivery profiles](DELIVERY-PROFILES.md) for a complete profile and
[Dependency-Track](DEPENDENCY-TRACK.md) for that destination's explicit fidelity limitations.

## Receiver retries

The default is one attempt. All vendor adapters use a single upload attempt. Dependency-Track writes always make one attempt: its tested contract
does not establish deduplication. Retry options on its profile are rejected before networking.
The legacy `publish` command also remains one attempt. Use a `generic-https` delivery profile to
enable bounded retries through either entry point:

```json
"options": {
  "receiverSupportsIdempotency": true,
  "maxAttempts": 3
}
```

Set this only after your receiver operator verifies all of these requirements:

- It persists `Idempotency-Key`, the exact request digest and the outcome atomically with acceptance.
- The same key and same bytes return the original outcome without another write or side effect.
- Reusing the key with different bytes returns a conflict, without replacing the original.
- Concurrent requests for a key are serialized, and the retention period covers your entire retry
  and manual reconciliation window. Never retry after the receiver has expired the key.

This setting is an operator assertion, not proof that a remote implementation meets the contract.
The sanitized plan labels it `OPERATOR_ASSERTED`. Tests exercise a receiver that persists a request,
loses the response, then deduplicates the retry; that is not vendor certification.

The key is a versioned, length-delimited SHA-256 identity over the configured endpoint, destination
ID, application, module, version, run ID and publication digest. Profiles also bind every explicitly
configured nonsecret extension header (`X-Tenant-Id`, `X-Correlation-Id`) in sorted name order using
the version-two identity encoding; no-header identities retain version one. Credentials and local output paths
do not participate. Retrying the same artifact/run/mapping uses the same key. A different run has a
different key. Every attempt sends identical evidence bytes. Use a distinct `destinationId` for each
receiver tenant/routing context selected through authentication. Changing an explicit routing
header changes the key; credentials deliberately do not participate, so changing an authentication-selected
tenant also requires a different destination ID.

At most three attempts are permitted for HTTP 408, 429, 500, 502, 503 and 504 or an ambiguous timeout
or connection failure. Authentication failures, conflicts, redirects, TLS failures, oversized
responses and interruption never trigger retries. Jittered backoff and `Retry-After` seconds or an
HTTP date consume the same 60-second monotonic operation budget. Invalid or excessive server advice,
or advice that cannot fit in the remaining budget, stops delivery; Census never shortens the
receiver's requested delay. Request/body consumption is limited to 30 seconds per attempt and
connection establishment to 10 seconds.

HTTP acceptance still does not prove semantic ingestion or retention. If an earlier attempt may
have been accepted, a later rejection cannot erase that ambiguity: the receipt remains `UNKNOWN`
unless a later response establishes acceptance. Retain the artifact and receipt, then reconcile
against the receiver's key before any manual resubmission. A receipt write failure also requires
reconciliation; it never triggers another upload.

## Authenticated CONNECT proxies

Census supports explicitly configured Basic authentication to an HTTP CONNECT proxy. Basic
credentials are visible to that proxy and on the client-to-proxy network; use only your approved
trusted enterprise route. The origin connection inside the tunnel uses verified HTTPS. Origin
bearer/API credentials are sent inside that TLS connection, and proxy credentials never answer an
origin authentication challenge.

For a profile, add environment references to the existing `transport` object:

```json
"transport": {
  "caBundle": "enterprise-ca.pem",
  "proxyHost": "proxy.example.com",
  "proxyPort": 8080,
  "proxyUsername": { "type": "ENV", "name": "CENSUS_PROXY_USER" },
  "proxyPassword": { "type": "ENV", "name": "CENSUS_PROXY_PASSWORD" }
}
```

For legacy Maven `publish`, use `census.delivery.proxyUserEnv` and
`census.delivery.proxyPasswordEnv`, or exclusively `census.delivery.proxyServerId` to read the
username and decrypted password of a Maven settings server. Always set
`census.delivery.proxyHost` and optionally `census.delivery.proxyPort` (default 8080). Origin
`serverId`/`tokenEnv` and proxy authentication remain separate.

Both references and an explicit host are required. Profile proxy credentials currently accept
environment references only. Resolve the values in your job's secret store; do not put secret
values in profile files or command arguments. The profile hash seals the references and endpoint,
and the plan binds the exact CA file digest. Execution resolves secrets only after revalidation.

For legacy CLI publication, add these options to the [complete publish command](DELIVERY.md):

```sh
--proxy-host proxy.example.com --proxy-port 8080 \
--proxy-user-env CENSUS_PROXY_USER --proxy-password-env CENSUS_PROXY_PASSWORD
```

The client never inherits ambient proxy settings, follows redirects, falls back to a direct
connection, or weakens hostname/certificate validation. Default JVM trust is augmented only for
the selected client when `caBundle` is configured. Census never changes global `Authenticator`,
`SSLContext`, or `jdk.http.auth.tunneling.disabledSchemes` settings. Authenticated proxy requests
use a narrowly scoped Apache HttpClient instance because the JDK disables Basic CONNECT by
default; unauthenticated requests keep the existing JDK transport. Library retries, cookies,
compression and origin challenge authentication are disabled. Headers, bodies, authentication,
CONNECT and TLS remain bounded, and timeout cancels the request and closes the client immediately.

Census refuses delivery with `UNSAFE_TRANSPORT_LOGGING` if Apache wire/header/auth-challenge DEBUG logging is
active (including Maven `-X` when it enables those loggers), or JDK HTTP header/content logging is
configured for its JDK transport. Configure those logger categories to INFO or higher before
starting delivery; do not enable sensitive transport tracing during the operation. Process logging
configuration is trusted state. This check changes no global logger settings.

## Execute and inspect a sealed profile

Build the CLI from the repository, then plan without credentials or network access:

```sh
./mvnw -pl census-cli -am package -DskipTests
java -jar census-cli/target/census-cli-0.1.0.jar delivery --action plan --profile delivery.json
```

Use the plan digest printed by that command with the existing profile `preflight` and `publish`
commands. See [the command reference](DELIVERY-PROFILES.md) for exact flags. Never regenerate an
expected source digest in the upload job merely to make a mismatched artifact pass validation.
Retain originals, prepared manifests, sealed plans and receipts even when a destination fails.
Profile receipt `exchanges` counts network exchanges, including retries, retention readback and
vendor preflight/polling; it is not a count of created remote inventory records.

An HTTP 401/403 requires checking the origin credential reference and destination permissions.
A proxy 407 requires checking the proxy references and permitted CONNECT route. An untrusted CA or
wrong hostname requires fixing the certificate/CA configuration; no insecure switch exists.
`UNKNOWN` requires receiver reconciliation. Diagnostics intentionally exclude remote response
bodies and secret values, including malicious responses that echo credentials.

## Reproduce the transport security matrix

The tests create ephemeral local certificates and loopback proxy/HTTPS fixtures. They need no
vendor credentials and do not touch the retained Dependency-Track demonstration stack:

```sh
./mvnw -pl census-delivery -am test \
  -Dtest=AuthenticatedProxyTest,ProfileTransportTest,ProfileFlowTest,RetryPolicyTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Run this command once with `JAVA_HOME` pointing to Java 17 and again to Java 25. It verifies private
CA trust, hostname rejection, proxy/origin credential separation, refusal to fall back directly,
bounded body consumption, stable retry bytes/identity and preservation of ambiguous outcomes.

Protocol references: [JDK proxy authentication defaults](https://docs.oracle.com/en/java/javase/17/core/java-networking.html),
[Apache client controls](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/classic/HttpClientBuilder.html),
and [HTTP Retry-After semantics](https://www.rfc-editor.org/rfc/rfc9110.html#name-retry-after).

The JDK property `jdk.httpclient.enableAllMethodRetry=true` is incompatible with bounded delivery attempts. Census refuses the JDK transport under that setting; remove it before starting the JVM. Do not change global HTTP retry or logging properties while a delivery process is running. Explicit Census retries remain limited to generic receivers with a declared idempotency contract.
