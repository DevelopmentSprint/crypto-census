package com.developmentsprint.cryptocensus.maven;

import org.apache.maven.plugins.annotations.Mojo;

/** Finalizes exact current-run evidence without enforcing its crypto policy. */
@Mojo(name = "report", threadSafe = true)
public class ReportMojo extends CheckMojo {
  @Override
  Mode mode() {
    return Mode.REPORT;
  }
}
