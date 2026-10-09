package com.developmentsprint.cryptocensus.maven;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PrepareAgentMojoTest {

  private static final String AGENT =
      "-javaagent:/repo/census-agent-0.1.0.jar=dir=/t/run1,app=x,summary=false,tls=true";
  private static final String AGENT2 =
      "-javaagent:/repo/census-agent-0.1.0.jar=dir=/t/run2,app=x,summary=false,tls=true";

  @Test
  void appendsToAnExistingArgLine() {
    assertThat(PrepareAgentMojo.append("-Xmx1g -Dfoo=bar", AGENT))
        .isEqualTo("-Xmx1g -Dfoo=bar " + AGENT);
  }

  @Test
  void usesTheOptionAloneWhenNothingIsSet() {
    assertThat(PrepareAgentMojo.append(null, AGENT)).isEqualTo(AGENT);
    assertThat(PrepareAgentMojo.append("  ", AGENT)).isEqualTo(AGENT);
  }

  @Test
  void isIdempotentAcrossRepeatedExecutions() {
    String once = PrepareAgentMojo.append("-Xmx1g", AGENT);
    String twice = PrepareAgentMojo.append(once, AGENT2);
    assertThat(twice).isEqualTo("-Xmx1g " + AGENT2);
  }

  @Test
  void replacesAQuotedEarlierOption() {
    String quoted = "\"-javaagent:/my repo/census-agent-0.1.0.jar=dir=/t/run1\"";
    assertThat(PrepareAgentMojo.append("-Xmx1g " + quoted, AGENT2)).isEqualTo("-Xmx1g " + AGENT2);
  }

  @Test
  void leavesOtherAgentsAlone() {
    String jacoco = "-javaagent:/repo/org.jacoco.agent-0.8.13-runtime.jar=destfile=/t/jacoco.exec";
    assertThat(PrepareAgentMojo.append(jacoco, AGENT)).isEqualTo(jacoco + " " + AGENT);
  }
}
