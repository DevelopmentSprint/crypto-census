package com.developmentsprint.cryptocensus.maven;

import org.apache.maven.plugins.annotations.Mojo;

/** Verifies sealed current-run evidence and applies the same policy as check. */
@Mojo(name = "enforce", threadSafe = true)
public class EnforceMojo extends CheckMojo {
  @Override
  Mode mode() {
    return Mode.ENFORCE;
  }
}
