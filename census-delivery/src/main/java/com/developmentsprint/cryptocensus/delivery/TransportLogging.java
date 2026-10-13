package com.developmentsprint.cryptocensus.delivery;

import java.util.Locale;
import org.slf4j.LoggerFactory;

/** Refuses credential-bearing transport when process logging would expose headers or bodies. */
public final class TransportLogging {
  private TransportLogging() {}

  /** Logging is trusted process configuration; Census never mutates global logger settings. */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Only documented ASCII logging keywords are recognized; not authentication text")
  public static boolean safe(boolean apache) {
    if (apache) {
      return !LoggerFactory.getLogger("org.apache.hc.client5.http.wire").isDebugEnabled()
          && !LoggerFactory.getLogger("org.apache.hc.client5.http.headers").isDebugEnabled()
          && !LoggerFactory.getLogger("org.apache.hc.client5.http.impl.auth.AuthenticationHandler")
              .isDebugEnabled()
          && !LoggerFactory.getLogger("org.apache.hc.client5.http.impl.auth.BasicScheme")
              .isDebugEnabled();
    }
    String logging =
        System.getProperty("jdk.httpclient.HttpClient.log", "").toLowerCase(Locale.ROOT);
    for (String part : logging.split(",", -1)) {
      if ("all".equals(part.strip())
          || "headers".equals(part.strip())
          || "content".equals(part.strip())) {
        return false;
      }
    }
    return true;
  }
}
