package com.developmentsprint.cryptocensus.agent;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded weak identity table. Values must never retain their keys, directly or transitively. */
final class TlsIdentityMap<V> {
  private final int capacity;
  private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
  private final Map<Identity, V> entries = new HashMap<>();

  TlsIdentityMap(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("Invalid identity capacity");
    }
    this.capacity = capacity;
  }

  synchronized V get(Object key) {
    reap();
    return key == null ? null : entries.get(new Identity(key, null));
  }

  synchronized boolean put(Object key, V value) {
    reap();
    Identity identity = new Identity(java.util.Objects.requireNonNull(key), collected);
    if (entries.size() >= capacity && !entries.containsKey(identity)) {
      return false;
    }
    entries.put(identity, java.util.Objects.requireNonNull(value));
    return true;
  }

  synchronized V remove(Object key) {
    reap();
    return key == null ? null : entries.remove(new Identity(key, null));
  }

  synchronized List<V> values() {
    reap();
    return List.copyOf(entries.values());
  }

  synchronized void clear() {
    entries.clear();
    reap();
  }

  private void reap() {
    Identity identity;
    while ((identity = (Identity) collected.poll()) != null) {
      entries.remove(identity);
    }
  }

  private static final class Identity extends WeakReference<Object> {
    private final int hash;

    Identity(Object key, ReferenceQueue<Object> queue) {
      super(key, queue);
      hash = System.identityHashCode(key);
    }

    @Override
    public int hashCode() {
      return hash;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      Object key = get();
      return key != null && other instanceof Identity identity && identity.refersTo(key);
    }
  }
}
