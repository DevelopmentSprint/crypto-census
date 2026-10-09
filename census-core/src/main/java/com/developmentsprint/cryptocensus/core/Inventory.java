package com.developmentsprint.cryptocensus.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe aggregate of observed assets and the places they were used.
 *
 * <p>An inventory inside a running application must never grow without bound: dynamically named
 * classes or caller-controlled algorithm strings would otherwise turn the observer into a memory
 * leak. {@link #bounded} caps distinct assets and call sites per asset. Call sites beyond the cap
 * are folded into {@link Occurrence#OVERFLOW} (the asset and its verdict are still reported; only
 * attribution is lost, counted in {@link #overflowedSites()}); assets beyond the cap are dropped and
 * counted in {@link #droppedObservations()}, which makes the collection incomplete.
 *
 * <p>All counts saturate at {@link Long#MAX_VALUE} rather than wrapping, so merged input can never
 * turn a count negative.
 */
public final class Inventory {

    private final ConcurrentHashMap<CryptoAsset, ConcurrentHashMap<Occurrence, AtomicLong>> data = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong overflowedSites = new AtomicLong();
    private final int maxAssets;
    private final int maxOccurrencesPerAsset;

    public Inventory() {
        this(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    private Inventory(int maxAssets, int maxOccurrencesPerAsset) {
        if (maxAssets < 1 || maxOccurrencesPerAsset < 1) throw new IllegalArgumentException("limits must be positive");
        this.maxAssets = maxAssets;
        this.maxOccurrencesPerAsset = maxOccurrencesPerAsset;
    }

    public static Inventory bounded(int maxAssets, int maxOccurrencesPerAsset) {
        return new Inventory(maxAssets, maxOccurrencesPerAsset);
    }

    public void record(CryptoAsset asset, Occurrence occurrence) {
        add(asset, occurrence, 1);
    }

    public void add(CryptoAsset asset, Occurrence occurrence, long count) {
        if (count < 0) throw new IllegalArgumentException("negative count " + count);
        ConcurrentHashMap<Occurrence, AtomicLong> sites = data.get(asset);
        if (sites == null) {
            // Approximate under concurrency (may overshoot by the number of racing threads); that is fine for a safety cap.
            if (data.size() >= maxAssets) {
                addSaturating(dropped, count);
                return;
            }
            sites = data.computeIfAbsent(asset, k -> new ConcurrentHashMap<>());
        }
        Occurrence site = occurrence;
        if (!sites.containsKey(site) && sites.size() >= maxOccurrencesPerAsset) {
            site = Occurrence.OVERFLOW;
        }
        if (Occurrence.OVERFLOW.equals(site)) addSaturating(overflowedSites, count);
        addSaturating(sites.computeIfAbsent(site, k -> new AtomicLong()), count);
    }

    static long saturatingAdd(long a, long b) {
        long r = a + b;
        return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
    }

    private static void addSaturating(AtomicLong counter, long n) {
        counter.accumulateAndGet(n, Inventory::saturatingAdd);
    }

    /** Merges entries and dropped counts. */
    public void mergeFrom(Inventory other) {
        other.data.forEach((asset, sites) -> sites.forEach((site, n) -> add(asset, site, n.get())));
        addSaturating(dropped, other.dropped.get());
        // overflowedSites needs no carry-over: re-adding the other's OVERFLOW occurrences counts them
    }

    /** Observations discarded because the asset cap was reached. Non-zero means the inventory is incomplete. */
    public long droppedObservations() {
        return dropped.get();
    }

    /** Observations whose call site was folded into {@link Occurrence#OVERFLOW}. */
    public long overflowedSites() {
        return overflowedSites.get();
    }

    public void addDropped(long count) {
        if (count < 0) throw new IllegalArgumentException("negative count " + count);
        addSaturating(dropped, count);
    }

    public boolean isEmpty() {
        return data.isEmpty();
    }

    /**
     * Snapshot of all entries, most severe first, then by name. Take one snapshot and pass it to
     * every consumer (CBOM, console) so they agree with each other.
     */
    public List<Entry> entries() {
        return entries(new Policy());
    }

    public List<Entry> entries(Policy policy) {
        List<Entry> result = new ArrayList<>();
        data.forEach((asset, occurrences) -> {
            Map<Occurrence, Long> snapshot = new LinkedHashMap<>();
            occurrences.entrySet().stream()
                    .sorted(Comparator.comparing(e -> e.getKey().toString()))
                    .forEach(e -> snapshot.put(e.getKey(), e.getValue().get()));
            result.add(new Entry(asset, policy.assess(asset), Collections.unmodifiableMap(snapshot)));
        });
        result.sort(Comparator.<Entry>comparingInt(e -> -e.assessment().risk().severity())
                .thenComparing(e -> e.asset().primitive())
                .thenComparing(e -> e.asset().ref()));
        return Collections.unmodifiableList(result);
    }

    /**
     * One asset with its verdict and per-occurrence call counts.
     */
    public record Entry(CryptoAsset asset, Assessment assessment, Map<Occurrence, Long> occurrences) {
        public long total() {
            long sum = 0;
            for (long n : occurrences.values()) sum = saturatingAdd(sum, n);
            return sum;
        }
    }
}
