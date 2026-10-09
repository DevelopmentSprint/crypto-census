package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;

import net.bytebuddy.utility.OpenedClassReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Byte Buddy's experimental switch. Unshaded here, so the relocated and the public property name
 * coincide; AgentIT checks the shaded jar, where they differ.
 */
class InstallerTest {

  private String saved;

  @BeforeEach
  void clear() {
    saved = System.getProperty(OpenedClassReader.EXPERIMENTAL_PROPERTY);
    System.clearProperty(OpenedClassReader.EXPERIMENTAL_PROPERTY);
  }

  @AfterEach
  void restore() {
    if (saved == null) {
      System.clearProperty(OpenedClassReader.EXPERIMENTAL_PROPERTY);
    } else {
      System.setProperty(OpenedClassReader.EXPERIMENTAL_PROPERTY, saved);
    }
  }

  @Test
  void publicPropertyNameIsBuiltAtRunTimeSoShadingCannotRewriteIt() {
    assertThat(Installer.UNSHADED_EXPERIMENTAL_PROPERTY).isEqualTo("net.bytebuddy.experimental");
  }

  @Test
  void experimentalIsOffByDefault() {
    assertThat(Installer.configureByteBuddy(false)).isFalse();
    assertThat(System.getProperty(OpenedClassReader.EXPERIMENTAL_PROPERTY)).isNull();
  }

  @Test
  void agentOptionTurnsExperimentalOn() {
    assertThat(Installer.configureByteBuddy(true)).isTrue();
    assertThat(Boolean.getBoolean(OpenedClassReader.EXPERIMENTAL_PROPERTY)).isTrue();
  }

  @Test
  void systemPropertyTurnsExperimentalOn() {
    System.setProperty(Installer.UNSHADED_EXPERIMENTAL_PROPERTY, "true");
    assertThat(Installer.configureByteBuddy(false)).isTrue();
    assertThat(Boolean.getBoolean(OpenedClassReader.EXPERIMENTAL_PROPERTY)).isTrue();
  }

  @Test
  void falseSystemPropertyKeepsItOff() {
    System.setProperty(Installer.UNSHADED_EXPERIMENTAL_PROPERTY, "false");
    assertThat(Installer.configureByteBuddy(false)).isFalse();
  }
}
