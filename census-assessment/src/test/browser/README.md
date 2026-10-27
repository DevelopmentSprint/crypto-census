# Offline guided report browser checks

These tests run the packaged Java renderer in Chromium using only synthetic inventory/context.
They exercise browser navigation, hostile text under the actual CSP, keyboard controls, draft
state, bounded imports, export downloads, and Java apply/reassessment with unchanged input hashes.
Binding choices require resolved declaring ownership and usable unscoped evidence; lost scope
is not repaired by naming a flow. The classification editor separates unanswered questions
from deliberate custom identifiers, including an organization-defined identifier `unknown`.
The browser must issue no HTTP requests and no CSP violations. Tool installation itself needs
network access; the resulting report does not.

From the repository root, with Node 20+ and a supported JDK:

```sh
./mvnw -B -ntp -pl census-assessment -am verify -Dgitbuildhook.gitconfig.skip=true
npm ci --ignore-scripts --prefix census-assessment/src/test/browser
(cd census-assessment/src/test/browser && npx --no-install playwright install chromium)
npm test --prefix census-assessment/src/test/browser
```

On Linux hosts missing browser libraries, use `playwright install --with-deps chromium` during
setup. To use an installed Chrome/Chromium executable, set `CENSUS_BROWSER_EXECUTABLE` to its
absolute path. `JAVA_HOME` selects the Java executable; otherwise the tests use `java` on PATH.

Temporary fixture directories live under the module's ignored `target/` and are removed after
each test. They contain no application or private tracker data. Do not substitute production
reports in this fixture suite or upload enriched reports through broad artifact globs.
