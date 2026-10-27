package com.developmentsprint.cryptocensus.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Idempotent bounded IDs for normalized declaration keys; never retains application classes. */
final class FlowBindings {
  static final int MAX_KEY_LENGTH = 4096;
  private final int capacity;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, Integer> ids = new LinkedHashMap<>();
  private long overflowed;

  FlowBindings(int capacity) {
    if (capacity < 1 || capacity > FlowScopes.MAX_BINDINGS) {
      throw new IllegalArgumentException("Binding capacity must be between 1 and 8192");
    }
    this.capacity = capacity;
  }

  int register(String normalizedDeclaration) {
    Objects.requireNonNull(normalizedDeclaration, "normalizedDeclaration");
    if (normalizedDeclaration.isEmpty() || normalizedDeclaration.length() > MAX_KEY_LENGTH) {
      throw new IllegalArgumentException("Declaration key must contain 1–4096 characters");
    }
    lock.lock();
    try {
      Integer existing = ids.get(normalizedDeclaration);
      if (existing != null) {
        return existing;
      }
      if (ids.size() == capacity) {
        if (overflowed != Long.MAX_VALUE) {
          overflowed++;
        }
        return 0;
      }
      int id = ids.size() + 1;
      ids.put(normalizedDeclaration, id);
      return id;
    } finally {
      lock.unlock();
    }
  }

  int size() {
    lock.lock();
    try {
      return ids.size();
    } finally {
      lock.unlock();
    }
  }

  long overflowed() {
    lock.lock();
    try {
      return overflowed;
    } finally {
      lock.unlock();
    }
  }
}
