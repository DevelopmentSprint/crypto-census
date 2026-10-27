package com.developmentsprint.cryptocensus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceCatalogSelectorsTest {
  @Test
  void legacyBytesAreUnqualifiedWhileProgrammaticMappingsRemainExplicit() {
    var explicit = entry("file:/app.jar", "named");
    assertThat(explicit.qualification()).isEqualTo(SourceCatalog.Qualification.EXPLICIT_DEPLOYMENT);
    String encoded =
        new String(new SourceCatalog(1, List.of(explicit)).encode(), StandardCharsets.UTF_8);
    String legacy = encoded.replace(",\n      \"qualification\": \"EXPLICIT_DEPLOYMENT\"", "");
    assertThat(legacy).doesNotContain("qualification");
    assertThat(
            SourceCatalog.parse(legacy.getBytes(StandardCharsets.UTF_8))
                .entries()
                .get(0)
                .qualification())
        .isEqualTo(SourceCatalog.Qualification.UNSPECIFIED);
    for (SourceCatalog.Qualification qualification : SourceCatalog.Qualification.values()) {
      var qualified =
          new SourceCatalog(
              1,
              List.of(
                  new SourceCatalog.Entry(
                      explicit.locationUri(),
                      explicit.artifactId(),
                      explicit.applicationId(),
                      explicit.origin(),
                      explicit.deploymentId(),
                      explicit.loaderName(),
                      qualification)));
      assertThat(SourceCatalog.parse(qualified.encode())).isEqualTo(qualified);
    }
    assertThatThrownBy(
            () ->
                SourceCatalog.parse(
                    encoded
                        .replace("EXPLICIT_DEPLOYMENT", "invented")
                        .getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void namedLoaderPrecedesMoreSpecificWildcardAndUnknownUsesOnlyWildcard() {
    var broad = entry("vfs:/content/", "deployment.one");
    var narrow = entry("vfs:/content/one.war/", "");
    var exact = entry("vfs:/content/one.war/WEB-INF/classes", "deployment.one");
    var catalog = new SourceCatalog(1, List.of(narrow, broad));
    String source = "vfs:/content/one.war/WEB-INF/classes";
    assertThat(catalog.resolve(source, "deployment.one")).contains(broad);
    assertThat(catalog.resolve(source)).contains(narrow);
    assertThat(catalog.resolve(source, "deployment.other")).contains(narrow);
    assertThat(
            new SourceCatalog(1, List.of(broad, narrow, exact)).resolve(source, "deployment.one"))
        .contains(exact);
    assertThat(new SourceCatalog(1, List.of(exact)).resolve(source)).isEmpty();
    assertThat(catalog.resolve("vfs:/contents/one.war", "deployment.one")).isEmpty();
  }

  @Test
  void sameLocationCanHaveDistinctExactSelectorsButDuplicatePairIsRejected() {
    var one = entry("file:/shared/app.jar", "one");
    var two = entry("file:/shared/app.jar", "two");
    var any = entry("file:/shared/app.jar", "");
    var catalog = new SourceCatalog(1, List.of(one, two, any));
    assertThat(catalog.resolve("file:/shared/app.jar", "one")).contains(one);
    assertThat(catalog.resolve("file:/shared/app.jar", "two")).contains(two);
    assertThat(catalog.resolve("file:/shared/app.jar", "other")).contains(any);
    assertThatThrownBy(() -> new SourceCatalog(1, List.of(one, one)))
        .hasMessageContaining("Duplicate");
    assertThatThrownBy(() -> entry("file:/app.jar", "x".repeat(257)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void optionalSelectorRoundTripsWithoutChangingLegacyEncoding() {
    var legacy =
        new SourceCatalog.Entry(
            "file:/app.jar", "artifact", "app", ContextModel.Origin.MAIN, "deployment");
    var oldCatalog = new SourceCatalog(1, List.of(legacy));
    assertThat(new String(oldCatalog.encode(), StandardCharsets.UTF_8))
        .doesNotContain("loaderName");
    assertThat(SourceCatalog.parse(oldCatalog.encode())).isEqualTo(oldCatalog);
    var named = new SourceCatalog(1, List.of(entry("file:/app.jar", "named"), legacy));
    assertThat(SourceCatalog.parse(named.encode()).encode()).isEqualTo(named.encode());
    assertThat(SourceCatalog.parse(named.encode()).resolve("file:/app.jar", "named"))
        .contains(entry("file:/app.jar", "named"));
    assertThat(new SourceCatalog(1, List.of(legacy, entry("file:/app.jar", "named"))).encode())
        .isEqualTo(named.encode());
  }

  @Test
  void vendorUrlsNormalizeLexicallyAndNeverNeedAnExistingArtifact() {
    var vfs = entry("vfs:/content/unused/../one.war/WEB-INF/classes", "");
    assertThat(vfs.locationUri()).isEqualTo("vfs:/content/one.war/WEB-INF/classes");
    var archive = entry("wsjar:file:/unavailable/../app.war!/WEB-INF/classes/", "");
    assertThat(archive.locationUri())
        .isEqualTo("wsjar:" + Path.of("/app.war").toUri() + "!/WEB-INF/classes/");
    assertThat(
            new SourceCatalog(1, List.of(archive))
                .resolve("wsjar:file:/app.war!/WEB-INF/classes/A.class"))
        .contains(archive);
    assertThat(entry("vfs:/content/space%20name.war", "").locationUri())
        .isEqualTo("vfs:/content/space%20name.war");
    assertThat(entry("vfs:/content/encoded%2520name.war", "").locationUri())
        .isEqualTo("vfs:/content/encoded%2520name.war");
  }

  @Test
  void remoteOpaqueAmbiguousAndEscapingVendorSourcesStayUnknown() {
    var catalog = new SourceCatalog(1, List.of(entry("vfs:/", "")));
    for (String invalid :
        List.of(
            "vfs:relative",
            "vfs:/%2Fserver/foo",
            "wsjar:file:/app.war!/%2Fserver/foo",
            "vfs://remote/content",
            "vfs:/content?query",
            "vfs:/content#fragment",
            "vfs:/../escape",
            "vfs:/a/../../escape",
            "wsjar:https://remote/app.war!/classes/",
            "wsjar:file://remote/app.war!/classes/",
            "wsjar:file:/app.war",
            "wsjar:file:/app.war!/../escape",
            "wsjar:file:/app.war!/classes?query",
            "wsjar:file:/app.war!/classes#fragment")) {
      assertThat(catalog.resolve(invalid)).as(invalid).isEmpty();
      assertThatThrownBy(() -> entry(invalid, ""))
          .as(invalid)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  private static SourceCatalog.Entry entry(String location, String loader) {
    return new SourceCatalog.Entry(
        location, "artifact", "app", ContextModel.Origin.MAIN, "deployment", loader);
  }
}
