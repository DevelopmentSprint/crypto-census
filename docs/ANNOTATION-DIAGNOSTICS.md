# Annotation diagnostics

When enrichment discovers an invalid declaration or an unsupported annotated method,
Census records an enrichment transform error and emits a warning through the JVM's
`System.Logger`. With the default JVM logger, look in the test process's standard
error output or its captured test log. These diagnostics do not change the existing
core inventory gate; the evidence sidecar retains its separate health counters.

Each warning identifies a reason, declaring class, method and JVM descriptor when
available. Descriptors distinguish overloaded methods. Annotation values, exception
messages and protected runtime data are excluded. Class/method/descriptor text is
sanitized and bounded to 256/128/256 characters. Each agent run retains at most 32
unique structural diagnostics and emits at most one additional limit message.
Health counters continue to account for failures after that display limit.

| Reason | What to inspect |
| --- | --- |
| `INVALID_OR_UNRESOLVED_DECLARATION` | Check the named method's annotation API dependency/version, required `CryptoFlow`, IDs, enum values and declared lifetimes. |
| `UNSUPPORTED_METHOD` | The annotated method is abstract, native or a constructor; annotate a supported concrete method instead. |
| `BRIDGE_UNAVAILABLE` | Check whether the declaring class loader can resolve the Census bridge. |
| `DESCRIPTORS_UNAVAILABLE` | The annotation transformer lacks its descriptor scan; retain the test log for investigation. |
| `CLASS_BUDGET_EXCEEDED` | The class exceeds the bounded scan budget. Its annotations were not processed. |
| `CLASS_READ_FAILED` / `TRANSFORM_FAILED` | Census could not inspect or transform this class; its scope evidence is incomplete. |
| `INSTALL_FAILED` | Annotation instrumentation could not be installed. |
| `DIAGNOSTIC_LIMIT_REACHED` | Further distinct locations are omitted from the log; health counters remain available. |

A logging sink failure disables further diagnostic emission for that run. The
failure does not escape the transform path, and the existing enrichment health
counter remains the indicator that observation is incomplete. JVM resource
exhaustion can prevent any diagnostic from being emitted.
