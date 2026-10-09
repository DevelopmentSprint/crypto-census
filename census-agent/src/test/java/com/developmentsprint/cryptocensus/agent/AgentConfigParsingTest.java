package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/** Option parsing: the agent must start with sane values and say why, whatever it is given. */
class AgentConfigParsingTest {

  @Test
  void defaultsWithoutOptions() {
    for (String args : new String[] {null, "", "  ", ",,"}) {
      AgentConfig config = AgentConfig.parse(args);
      assertThat(config.output).isEqualTo(Paths.get("cryptocensus-cbom.json"));
      assertThat(config.app).isNull();
      assertThat(config.summary).isTrue();
      assertThat(config.tls).isTrue();
      assertThat(config.debug).isFalse();
      assertThat(config.maxAssets).isEqualTo(500);
      assertThat(config.maxSites).isEqualTo(50);
      assertThat(config.warnings).as(args).isEmpty();
    }
  }

  @Test
  void readsEveryOption() {
    AgentConfig config =
        AgentConfig.parse(
            "output=out/cbom.json,app=Billing,summary=false,tls=FALSE,debug=True,"
                + "maxAssets=7,maxSites=3");
    assertThat(config.output).isEqualTo(Paths.get("out/cbom.json"));
    assertThat(config.app).isEqualTo("Billing");
    assertThat(config.summary).isFalse();
    assertThat(config.tls).isFalse();
    assertThat(config.debug).isTrue();
    assertThat(config.maxAssets).isEqualTo(7);
    assertThat(config.maxSites).isEqualTo(3);
    assertThat(config.warnings).isEmpty();
  }

  @Test
  void trimsKeysAndValuesAndPercentDecodesValues() {
    AgentConfig config = AgentConfig.parse(" app = My%2C%20App%3D1 , output = a%25b.json ,");
    assertThat(config.app).isEqualTo("My, App=1");
    assertThat(config.output).isEqualTo(Paths.get("a%b.json"));
    assertThat(config.warnings).isEmpty();
  }

  @Test
  void dirGetsAnUnguessableFileNamePerJvm() {
    AgentConfig first = AgentConfig.parse("dir=cboms");
    AgentConfig second = AgentConfig.parse("dir=cboms");
    Path name = first.output.getFileName();
    assertThat(first.output.getParent()).isEqualTo(Paths.get("cboms"));
    assertThat(name.toString())
        .matches("cbom-" + ProcessHandle.current().pid() + "-[0-9a-f]+\\.json");
    assertThat(second.output).isNotEqualTo(first.output);
  }

  @Test
  void dirWinsOverOutputWithWarning() {
    AgentConfig config = AgentConfig.parse("output=x.json,dir=cboms");
    assertThat(config.output.getParent()).isEqualTo(Paths.get("cboms"));
    assertThat(config.warnings).containsExactly("both 'dir' and 'output' given; using 'dir'");
  }

  @Test
  void warnsAboutMalformedUnknownAndRepeatedOptions() {
    AgentConfig config = AgentConfig.parse("summary,=x,bogus=1,app=first,app=second");
    assertThat(config.app).isEqualTo("first");
    assertThat(config.summary).isTrue();
    assertThat(config.warnings)
        .containsExactly(
            "ignoring malformed option 'summary'",
            "ignoring malformed option '=x'",
            "ignoring unknown option 'bogus'",
            "option 'app' given more than once; keeping the first value");
  }

  @Test
  void ignoresValuesThatAreNotValidEscapes() {
    AgentConfig config = AgentConfig.parse("app=50%,output=%00.json");
    assertThat(config.app).isNull();
    assertThat(config.output).isEqualTo(Paths.get("cryptocensus-cbom.json"));
    assertThat(config.warnings).hasSize(2);
    assertThat(config.warnings.get(0)).startsWith("ignoring option 'app': Malformed escape");
    assertThat(config.warnings.get(1)).startsWith("ignoring option 'output': Encoded NUL");
  }

  @Test
  void fallsBackToTheDefaultFileForAnUnusablePath() {
    AgentConfig config = AgentConfig.parse("output=bad\u0000name.json");
    assertThat(config.output).isEqualTo(Paths.get("cryptocensus-cbom.json"));
    assertThat(config.warnings).singleElement().asString().startsWith("invalid output path (");
  }

  @Test
  void keepsDefaultsForInvalidBooleansAndCounts() {
    AgentConfig config =
        AgentConfig.parse("summary=yes,debug=1,maxAssets=0,maxSites=-5,tls=,app=x");
    assertThat(config.summary).isTrue();
    assertThat(config.debug).isFalse();
    assertThat(config.tls).isTrue();
    assertThat(config.maxAssets).isEqualTo(500);
    assertThat(config.maxSites).isEqualTo(50);
    assertThat(config.warnings)
        .containsExactly(
            "option 'summary' must be true or false, got 'yes'; using true",
            "option 'tls' must be true or false, got ''; using true",
            "option 'debug' must be true or false, got '1'; using false",
            "option 'maxAssets' must be a positive integer, got '0'; using 500",
            "option 'maxSites' must be a positive integer, got '-5'; using 50");
  }

  @Test
  void rejectsCountsThatAreNotIntegers() {
    AgentConfig config = AgentConfig.parse("maxAssets=lots,maxSites=99999999999");
    assertThat(config.maxAssets).isEqualTo(500);
    assertThat(config.maxSites).isEqualTo(50);
    assertThat(config.warnings).hasSize(2);
  }

  @Test
  void warningsCannotBeModified() {
    AgentConfig config = AgentConfig.parse("bogus=1");
    assertThat(config.warnings).isUnmodifiable();
  }
}
