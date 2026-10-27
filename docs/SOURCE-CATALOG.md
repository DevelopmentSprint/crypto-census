# Explicit source and deployment ownership

A source catalog associates a declaring class's CodeSource with an artifact,
application and deployment. These identities are supplied by the build or
operator. Census does not infer ownership from a package name, WAR filename or
class-loader name, and never opens or fetches a catalog location.

An entry has `locationUri`, `artifactId`, `applicationId`, `origin` and
`deploymentId`. The optional `loaderName` selects an exact Java
`ClassLoader.getName()` value. Omission or an empty value matches any loader.
An unknown or unnamed loader cannot match a named selector. Loader names are
selectors, not stable deployment identifiers; the separate declared
`deploymentId` supplies that identity.

```json
{
  "schemaVersion": 1,
  "entries": [
    {
      "locationUri": "vfs:/content/orders.war/WEB-INF/classes",
      "loaderName": "deployment.orders.war",
      "artifactId": "example:orders:1",
      "applicationId": "orders",
      "origin": "MAIN",
      "deploymentId": "orders-production",
      "qualification": "EXPLICIT_DEPLOYMENT"
    }
  ]
}
```

For a standalone agent, set `qualification` to `EXPLICIT_DEPLOYMENT` when the operator
has established that deployment mapping. Merely naming `deploymentId` does not qualify
ownership: an omitted qualification decodes as `UNSPECIFIED`, preserving compatibility
without inventing authority. `GENERATED_BUILD` identifies build-produced artifact labels,
which do not distinguish runtime deployments. Multiple eligible loaders for one application
can therefore remain ambiguous until explicitly mapped. Maven treats entries supplied through its operator override catalog as explicit deployment
mappings. Standalone catalog loading does not perform that upgrade: a standalone
`GENERATED_BUILD` entry remains generated, not an operator qualification.

Exact named-loader entries take precedence over wildcard entries. Within that
choice, the longest matching location wins. A location ending in `/` is a
directory prefix; otherwise it matches exactly. Duplicate normalized
`(locationUri, loaderName)` pairs are rejected. This permits the same JAR URL
loaded by two named loaders to have two explicit deployment mappings. Existing
five-field catalogs retain their canonical encoding.

Supported local location forms are `file:/path`, `vfs:/path` and
`wsjar:file:/archive.war!/entry/path`. Normalization is lexical, including dot
segments; no symlink resolution or artifact access occurs. Remote authorities,
queries, fragments, relative paths and vendor paths escaping their root are
rejected. A `wsjar:` location must wrap a local `file:` URI. Unsupported or
unmapped CodeSources remain unknown.

## Observed server forms

A two-WAR probe on the pinned conformance images observed these values:

| Runtime | CodeSource | Loader name |
| --- | --- | --- |
| Open Liberty 26.0.0.8, IBM Semeru 25 | `file:/opt/ol/wlp/usr/servers/defaultServer/dropins/context-one.war` | unnamed |
| WildFly 41.0.1.Final, Temurin 21 | `vfs:/content/context-one.war/WEB-INF/classes` | `deployment.context-one.war` |

The second deployment used the corresponding `context-two` values. Image
digests are pinned by `AppServerIT`; these observations do not promise identical
paths on other server versions or configurations. `wsjar:` normalization has
unit coverage only; these two runtime probes did not produce `wsjar:` URLs.

The [schema](schemas/source-catalog-v1.schema.json) bounds catalogs to 4096
entries and loader selectors to 256 characters. Source mapping and optional
global application overrides are distinct: an application override does not
invent an artifact or deployment identity.
