package com.developmentsprint.cryptocensus.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/** Structural SunJSSE adapter. No key, ticket, session identifier or message bytes are read. */
final class TlsSunJsse {
  static final String PREFIX = "sun.security.ssl.";

  private TlsSunJsse() {}

  static Object field(Object target, String name) throws ReflectiveOperationException {
    if (target == null) {
      return null;
    }
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        if (!field.trySetAccessible()) {
          throw new IllegalAccessException(name);
        }
        return field.get(target);
      } catch (NoSuchFieldException ignored) {
        // Shared handshake fields live on HandshakeContext, not its role-specific subclasses.
      }
    }
    throw new NoSuchFieldException(name);
  }

  static boolean postHandshake(Object handshake) {
    return handshake == null
        || (PREFIX + "PostHandshakeContext").equals(handshake.getClass().getName());
  }

  static Object transport(Object handshake) throws ReflectiveOperationException {
    return field(handshake, "conContext");
  }

  static boolean client(Object transport) throws ReflectiveOperationException {
    return Boolean.TRUE.equals(field(field(transport, "sslConfig"), "isClientMode"));
  }

  static boolean socket(Object transport) throws ReflectiveOperationException {
    return field(transport, "transport") instanceof SSLSocket;
  }

  static SSLSession session(Object transport) throws ReflectiveOperationException {
    return (SSLSession) field(transport, "conSession");
  }

  static int group(Object handshake) throws ReflectiveOperationException {
    Map<?, ?> extensions = (Map<?, ?>) field(handshake, "handshakeExtensions");
    if (extensions != null) {
      for (Map.Entry<?, ?> entry : extensions.entrySet()) {
        if ("SH_KEY_SHARE".equals(((Enum<?>) entry.getKey()).name())) {
          return (int) field(field(entry.getValue(), "serverShare"), "namedGroupId");
        }
      }
    }
    return -1;
  }

  static String groupName(int group) throws ReflectiveOperationException {
    if (group < 0) {
      return "";
    }
    Class<?> namedGroup = Class.forName(PREFIX + "NamedGroup", false, null);
    Method nameOf = namedGroup.getDeclaredMethod("nameOf", int.class);
    if (!nameOf.trySetAccessible()) {
      throw new IllegalAccessException("NamedGroup.nameOf");
    }
    return (String) nameOf.invoke(null, group);
  }

  static String signature(Object message) throws ReflectiveOperationException {
    return (String) field(field(message, "signatureScheme"), "name");
  }
}
