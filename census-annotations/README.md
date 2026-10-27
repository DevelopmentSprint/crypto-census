# Census annotations

`census-annotations` supplies passive Java 17 metadata with no runtime dependencies. It does not
install an agent, invoke cryptography, or change method behavior. An observing Census agent can
use the declarations to associate executed cryptographic entry points with business context.

```xml
<dependency>
  <groupId>com.developmentsprint</groupId>
  <artifactId>census-annotations</artifactId>
  <version>0.1.0</version>
</dependency>
```

Start with a stable identity:

```java
@CryptoFlow("records-export")
public void exportRecords() {
    archiveClient.upload(records);
}
```

Optionally declare the protection requirement alongside it:

```java
@CryptoFlow(value = "records-export", purpose = CryptoPurpose.TRANSPORT)
@DataProtection(classification = "restricted", confidentialityYears = 20)
public void exportRecords() {
    archiveClient.upload(records);
}
```

For signatures, declare the required authenticity lifetime:

```java
@CryptoFlow(value = "firmware-signing", purpose = CryptoPurpose.SIGNING)
@DataProtection(authenticityYears = 15)
public byte[] signFirmware() {
    return signer.sign();
}
```

Flow IDs and nonempty classification identifiers use lowercase ASCII
`[a-z0-9][a-z0-9._-]{0,63}`. Classification values belong to the organization; unmapped values
remain unknown during assessment. Positive lifetime values are minimum required years from
creation or signing, in the range 1–1000. `DataProtection.UNDECLARED` (`-1`) means no declaration;
zero does not mean safe. Empty classification and unspecified purpose also mean undeclared.

Version 1 requires `@DataProtection` and `@CryptoFlow` on the same concrete executable method.
Declarations on interfaces or overridden methods are not copied onto implementations or
overrides. Multiple methods can intentionally share an ID; conflicting declarations need
review. Invalid declaration values require an observer diagnostic, not a change to application
behavior. Java itself only enforces annotation targets and required members.

Use a normal compile dependency to make annotation types/defaults available at runtime. Libraries
can use provided/optional dependencies, but the consuming application must supply the artifact
for observers that require type metadata. An unresolved type is an observation gap. This artifact
carries `@CensusAnnotationApi(1)` on its usage annotations; users do not add that marker themselves.
The automatic module name is `com.developmentsprint.cryptocensus.annotations`.

An annotation identifies synchronous dynamic execution scope. It does not prove which data was
protected, propagate context through executors/reactive code, or associate later requests with a
pooled TLS handshake. Shared helpers and asynchronous work require the report's evidence limits
to remain visible. Without an agent, the declarations are simply metadata.
