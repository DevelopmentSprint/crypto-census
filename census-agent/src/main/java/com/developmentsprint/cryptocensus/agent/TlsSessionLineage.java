package com.developmentsprint.cryptocensus.agent;

import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;

/** In-process ticket lineage by object identity, with no session identifiers or ticket material. */
final class TlsSessionLineage<V> {
  private static final int MAX_DEPTH = 32;
  private final TlsIdentityMap<Link<V>> sessions;

  TlsSessionLineage(int capacity) {
    sessions = new TlsIdentityMap<>(capacity);
  }

  synchronized boolean derived(Object session, Object parent) {
    if (sessions.get(session) != null) {
      return true;
    }
    V known = origin(parent);
    return sessions.put(session, new Link<>(new WeakReference<>(parent), known));
  }

  synchronized boolean completed(Object session, V attempt) {
    return sessions.put(session, new Link<>(new WeakReference<>(null), attempt));
  }

  // Explicit identity semantics are required; Error Prone also requires the concrete type here.
  @SuppressWarnings("PMD.LooseCoupling")
  synchronized V origin(Object session) {
    IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    Object current = session;
    for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
      if (seen.put(current, Boolean.TRUE) != null) {
        return null;
      }
      Link<V> link = sessions.get(current);
      if (link == null) {
        return null;
      }
      if (link.attempt() != null) {
        return link.attempt();
      }
      current = link.parent().get();
    }
    return null;
  }

  synchronized void clear() {
    sessions.clear();
  }

  private record Link<V>(WeakReference<Object> parent, V attempt) {}
}
