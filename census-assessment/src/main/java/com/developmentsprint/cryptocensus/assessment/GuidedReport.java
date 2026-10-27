package com.developmentsprint.cryptocensus.assessment;

import com.developmentsprint.cryptocensus.core.DocumentFiles;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/** Self-contained offline editor; authoritative assessment and revision logic remains in Java. */
public final class GuidedReport {
  private GuidedReport() {}

  /** Publishes HTML with restrictive content policy and no external resources. */
  public static void write(AssessmentReport report, Path destination) throws IOException {
    DocumentFiles.write(destination, html(report).getBytes(StandardCharsets.UTF_8));
  }

  /** Renders untrusted report strings as embedded JSON, never as HTML markup. */
  public static String html(AssessmentReport report) throws IOException {
    Objects.requireNonNull(report, "report");
    String script = resource("guided-report.js");
    String style = resource("guided-report.css");
    String data =
        new String(report.bytes(), StandardCharsets.UTF_8)
            .replace("&", "\\u0026")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029");
    return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\""
        + " content=\"width=device-width,initial-scale=1\"><meta"
        + " http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; connect-src"
        + " 'none'; base-uri 'none'; form-action 'none'; object-src 'none'; script-src"
        + " 'sha256-"
        + digest(script)
        + "'; style-src 'sha256-"
        + digest(style)
        + "'\">"
        + "<title>Crypto Census — migration review</title><style>"
        + style
        + "</style></head><body><main id=\"app\"></main><noscript>This offline report needs"
        + " JavaScript for navigation and draft editing. The companion JSON retains the complete"
        + " assessment.</noscript><script id=\"report-data\" type=\"application/json\">"
        + data
        + "</script>"
        + "<script>"
        + script
        + "</script></body></html>";
  }

  private static String resource(String name) throws IOException {
    try (InputStream input = GuidedReport.class.getResourceAsStream(name)) {
      if (input == null) {
        throw new IOException("Missing bundled report resource: " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String digest(String text) {
    try {
      return Base64.getEncoder()
          .encodeToString(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Required SHA-256 provider unavailable", e);
    }
  }
}
