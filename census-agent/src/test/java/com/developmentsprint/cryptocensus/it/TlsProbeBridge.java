package com.developmentsprint.cryptocensus.it;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

/** Test-only bounded probe. It reads structural fields, never key, ticket or record bytes. */
public final class TlsProbeBridge {
  private static final int LIMIT = 100;
  private static final IdentityHashMap<Object, Integer> CONNECTIONS = new IdentityHashMap<>();
  private static final IdentityHashMap<Object, String[]> SIGNATURES = new IdentityHashMap<>();
  private static final IdentityHashMap<Object, Object> TICKETS = new IdentityHashMap<>();
  private static final List<String> EVENTS = new ArrayList<>();

  private TlsProbeBridge() {}

  /** Snapshot while the connection still owns its handshake. */
  public static Object handshake(Object transport) throws Exception {
    return field(transport, "handshakeContext");
  }

  /** Records a real successful finish, preserving identity through delegated task execution. */
  public static synchronized void completed(Object transport, Object handshake) throws Exception {
    if (handshake == null || !Boolean.TRUE.equals(field(transport, "isNegotiated"))) {
      throw new IllegalStateException("finishHandshake did not establish a connection");
    }
    SSLSession session = (SSLSession) field(transport, "conSession");
    boolean resumed = Boolean.TRUE.equals(field(handshake, "isResumption"));
    Object resumedSession = field(handshake, "resumingSession");
    String[] signatures =
        SIGNATURES.getOrDefault(
            handshake, new String[] {"unknown", "unknown", "unknown", "unknown"});
    add(
        "completed",
        transport,
        session.getProtocol(),
        session.getCipherSuite(),
        group(handshake),
        Boolean.toString(resumed),
        signatures[0],
        signatures[1],
        Boolean.toString(resumedSession != null && TICKETS.containsKey(resumedSession)),
        signatures[2],
        signatures[3]);
  }

  /** A fatal failure is not converted into a completed handshake. */
  public static synchronized void failed(Object transport) throws Exception {
    Object handshake = handshake(transport);
    if (handshake != null && !handshake.getClass().getSimpleName().equals("PostHandshakeContext")) {
      add(
          "failed", transport, "unknown", "unknown", "unknown", "unknown", "unknown", "unknown",
          "unknown");
    }
  }

  /** Stores only the selected signature scheme name and its direction. */
  public static synchronized void signature(Object handshake, Object message, boolean peer)
      throws Exception {
    if (SIGNATURES.size() >= LIMIT) {
      throw new IllegalStateException("probe capacity exceeded");
    }
    String name = (String) field(field(message, "signatureScheme"), "name");
    String[] schemes =
        SIGNATURES.computeIfAbsent(
            handshake, ignored -> new String[] {"unknown", "unknown", "unknown", "unknown"});
    schemes[peer ? 1 : 0] = name;
    schemes[peer ? 3 : 2] = Thread.currentThread().getName();
  }

  /** Keeps an object-identity lineage solely within this short-lived test process. */
  public static synchronized void ticket(Object session, Object parent) {
    if (TICKETS.size() >= LIMIT) {
      throw new IllegalStateException("probe capacity exceeded");
    }
    TICKETS.put(session, parent);
  }

  /** Returns text containing no sensitive metadata, for fixture assertions. */
  public static synchronized List<String> snapshot() {
    List<String> result = new ArrayList<>(EVENTS);
    result.add("ticket-derived-sessions\t" + TICKETS.size());
    return List.copyOf(result);
  }

  private static String group(Object handshake) throws Exception {
    Map<?, ?> extensions = (Map<?, ?>) field(handshake, "handshakeExtensions");
    for (Map.Entry<?, ?> entry : extensions.entrySet()) {
      if (((Enum<?>) entry.getKey()).name().equals("SH_KEY_SHARE")) {
        return String.valueOf(field(field(entry.getValue(), "serverShare"), "namedGroupId"));
      }
    }
    return "unknown";
  }

  private static void add(String outcome, Object transport, String... details) throws Exception {
    if (CONNECTIONS.size() >= LIMIT || EVENTS.size() >= LIMIT) {
      throw new IllegalStateException("probe capacity exceeded");
    }
    int identity = CONNECTIONS.computeIfAbsent(transport, ignored -> CONNECTIONS.size() + 1);
    boolean client = (boolean) field(field(transport, "sslConfig"), "isClientMode");
    String kind = field(transport, "transport") instanceof SSLSocket ? "socket" : "engine";
    List<String> row =
        new ArrayList<>(
            List.of(outcome, Integer.toString(identity), kind, client ? "client" : "server"));
    row.addAll(List.of(details));
    row.add(Thread.currentThread().getName());
    EVENTS.add(String.join("\t", row));
  }

  private static Object field(Object target, String name) throws Exception {
    for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
      try {
        Field field = type.getDeclaredField(name);
        if (!field.trySetAccessible()) {
          throw new IllegalAccessException(name);
        }
        return field.get(target);
      } catch (NoSuchFieldException ignored) {
        // Handshake fields are declared on a superclass.
      }
    }
    throw new NoSuchFieldException(name);
  }
}
