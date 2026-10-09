package com.developmentsprint.cryptocensus.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Crypto Census version, filtered into {@code META-INF/cryptocensus/version.properties} (a path the
 * agent's package relocation leaves alone) at build time.
 */
public final class Version {

  private static final String VERSION = load();

  private Version() {}

  /**
   * The project version this class was built as.
   *
   * @return e.g. {@code 0.1.0}, or {@code unknown}
   */
  public static String get() {
    return VERSION;
  }

  private static String load() {
    try (InputStream in =
        Version.class.getResourceAsStream("/META-INF/cryptocensus/version.properties")) {
      if (in == null) {
        return "unknown";
      }
      Properties p = new Properties();
      p.load(in);
      return p.getProperty("version", "unknown");
    } catch (IOException e) {
      return "unknown";
    }
  }
}
