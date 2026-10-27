package com.developmentsprint.cryptocensus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TlsSunJsseTest {
  @Test
  void selectedGroupUsesOnlyServerHelloAndMissingOrInaccessibleFieldsStayUnknown()
      throws Exception {
    Extensions context = new Extensions();
    context.handshakeExtensions.put(Extension.OFFERED, new Object());
    assertThat(TlsSunJsse.group(context)).isEqualTo(-1);
    context.handshakeExtensions.put(Extension.SH_KEY_SHARE, new Selection());
    assertThat(TlsSunJsse.group(context)).isEqualTo(29);
    context.handshakeExtensions = null;
    assertThat(TlsSunJsse.group(context)).isEqualTo(-1);
    assertThat(TlsSunJsse.groupName(-1)).isEmpty();
    try {
      assertThat(TlsSunJsse.groupName(29)).isEqualTo("x25519");
    } catch (IllegalAccessException closedModule) {
      assertThat(closedModule).hasMessage("NamedGroup.nameOf");
    }
    assertThat(TlsSunJsse.field(null, "unread")).isNull();
    assertThat(TlsSunJsse.postHandshake(null)).isTrue();
    assertThat(TlsSunJsse.postHandshake(context)).isFalse();
    assertThatThrownBy(() -> TlsSunJsse.field(new Object(), "missing"))
        .isInstanceOf(NoSuchFieldException.class);
  }

  private enum Extension {
    OFFERED,
    SH_KEY_SHARE
  }

  // Structural fixture fields are read reflectively by the production adapter.
  @SuppressWarnings("UnusedVariable")
  private static final class Extensions {
    Map<Extension, Object> handshakeExtensions = new LinkedHashMap<>();
  }

  // Structural fixture fields are read reflectively by the production adapter.
  @SuppressWarnings("UnusedVariable")
  private static final class Selection {
    final Share serverShare = new Share();
  }

  // Structural fixture fields are read reflectively by the production adapter.
  @SuppressWarnings("UnusedVariable")
  private static final class Share {
    final int namedGroupId = 29;
  }
}
