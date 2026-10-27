package com.developmentsprint.cryptocensus.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuidedReportTest {
  @Test
  void hostileDataRemainsInertAndExecutableResourcesHaveExactCspHashes(@TempDir Path directory)
      throws Exception {
    AssessmentReport report =
        AssessmentEngine.assess(
            List.of(GuidedReportFixture.input()),
            GuidedReportFixture.context(),
            PolicyProfile.defaults());
    String html = GuidedReport.html(report);
    assertThat(html)
        .doesNotContain(GuidedReportFixture.HOSTILE)
        .doesNotContain("unsafe-inline", "unsafe-eval");
    assertThat(html).contains("\\u003c/script\\u003e", "connect-src 'none'", "default-src 'none'");
    for (String tag : List.of("style", "script")) {
      String start = "<" + tag + ">";
      String contents =
          html.substring(
              html.indexOf(start) + start.length(),
              html.indexOf("</" + tag + ">", html.indexOf(start)));
      String hash =
          Base64.getEncoder()
              .encodeToString(
                  MessageDigest.getInstance("SHA-256")
                      .digest(contents.getBytes(StandardCharsets.UTF_8)));
      assertThat(html).contains("'sha256-" + hash + "'");
    }
    Path output = directory.resolve("assessment.html");
    GuidedReport.write(report, output);
    assertThat(Files.readString(output)).isEqualTo(html);
    assertThatThrownBy(() -> GuidedReport.html(null)).isInstanceOf(NullPointerException.class);
  }
}
