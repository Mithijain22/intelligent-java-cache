package com.intelligentcache.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Adaptively picks the best eviction policy for the current workload --
 * <b>by measuring it directly</b>, not by guessing from a heuristic and
 * definitely not with any kind of learned model. Every real access is fed
 * to a small set of candidate policy instances running in parallel
 * ("shadows"): each one makes its own independent eviction decisions and
 * accumulates its own real hit rate over a sliding window. One shadow is
 * designated "active" (its result is what callers actually see); every
 * {@code windowSize} operations, {@code AdaptiveCache} checks which shadow
 * had the best windowed hit rate and, if it isn't the current active one,
 * switches.
 *
 * <p><b>Being explicit about what this is and isn't</b> (see project README,
 * "honest account of what's heuristic vs adaptive"): this is empirical
 * shadow evaluation -- real hit-rate measurement of real candidate caches,
 * continuously, online. It is not a heuristic that infers workload shape
 * from proxy statistics (repeat rate, skew estimate, etc.) and guesses a
 * policy from that; it's not a lookup table; and it is absolutely not
 * "machine learning" despite "adaptive" sometimes getting oversold as such
 * in portfolio projects. If anything, direct measurement is a *stronger*
 * claim than a heuristic would be -- there's no guessing here, just
 * continuously re-checking which candidate is actually winning.
 *
 * <h2>Cost</h2>
 * Running N shadow policies means N times the memory and N times the
 * per-operation work of a single policy -- this is a real, worthwhile
 * tradeoff to know about, not free. That's why shadows are ordinary
 * capacity-bounded cache instances (not literally "ghost" structures) --
 * simpler to implement and reason about, at the cost of being explicit that
 * this is O(N) overhead, not O(1). A production system would likely run
 * this only during a tuning phase, or with cheaper approximate shadows, not
 * as a permanent steady-state mode.
 */
public final class AdaptiveCache<K, V> implements Cache<K, V> {

    /** Records one policy switch, for the dashboard to show adaptation actually happening. */
    public record PolicySwitchEvent(long operationIndex, String fromPolicy, String toPolicy,
                                     Map<String, Double> windowedHitRatesAtSwitch) {}

    private final Map<String, Cache<K, V>> shadows;
    private final Map<String, int[]> windowCounts = new LinkedHashMap<>(); // name -> {hits, misses} in current window
    private final int windowSize;
    private final ReentrantLock lock = new ReentrantLock();
    private final CacheStats stats = new CacheStats(); // reflects what the ACTIVE shadow exposes to callers
    private final List<PolicySwitchEvent> switchHistory = new CopyOnWriteArrayList<>();
    private static final int MAX_SWITCH_HISTORY = 100;

    private volatile String activePolicyName;
    private long operationCount = 0;

    /**
     * @param candidatePolicies name -&gt; factory for a fresh instance of that
     *                          policy, all built at the same capacity you
     *                          want the adaptive cache to run at.
     * @param windowSize        how many operations make up one evaluation
     *                          window before re-checking which shadow is winning.
     * @param initialActive     which candidate starts as active before the
     *                          first window completes.
     */
    public AdaptiveCache(Map<String, Supplier<Cache<K, V>>> candidatePolicies, int windowSize, String initialActive) {
        if (candidatePolicies.size() < 2) {
            throw new IllegalArgumentException("adaptive cache needs at least 2 candidate policies to choose between");
        }
        if (!candidatePolicies.containsKey(initialActive)) {
            throw new IllegalArgumentException("initialActive '" + initialActive + "' is not among the candidate policies");
        }
        this.windowSize = Math.max(1, windowSize);
        this.shadows = new LinkedHashMap<>();
        for (var entry : candidatePolicies.entrySet()) {
            shadows.put(entry.getKey(), entry.getValue().get());
            windowCounts.put(entry.getKey(), new int[2]);
        }
        this.activePolicyName = initialActive;
    }

    @Override
    public V get(K key) {
        lock.lock();
        try {
            V activeResult = null;
            for (var entry : shadows.entrySet()) {
                String name = entry.getKey();
                Cache<K, V> shadow = entry.getValue();
                V v = shadow.get(key);
                int[] counts = windowCounts.get(name);
                boolean isActive = name.equals(activePolicyName);
                if (v != null) {
                    counts[0]++; // hit
                } else {
                    counts[1]++; // miss
                    if (!isActive) {
                        // Simulate the cache-aside populate for background
                        // shadows only -- the ACTIVE shadow must stay a pure
                        // read here, exactly like every other Cache in this
                        // project. Doing this for the active shadow too was
                        // a real bug: it made get() silently resurrect a key
                        // right after remove().
                        shadow.put(key, placeholderValueFor(key));
                    }
                }
                if (isActive) {
                    activeResult = v;
                }
            }

            if (activeResult == null) {
                stats.recordMiss();
            } else {
                stats.recordHit();
            }

            operationCount++;
            if (operationCount % windowSize == 0) {
                evaluateAndMaybeSwitch();
            }

            return activeResult;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        lock.lock();
        try {
            shadows.get(activePolicyName).put(key, value);
            stats.recordPut();
        } finally {
            lock.unlock();
        }
    }

    /** Caller must hold `lock`. Picks the shadow with the best windowed hit rate and switches if it isn't already active. */
    private void evaluateAndMaybeSwitch() {
        String bestName = activePolicyName;
        double bestRate = -1;
        Map<String, Double> rates = new LinkedHashMap<>();

        for (var entry : windowCounts.entrySet()) {
            int[] counts = entry.getValue();
            int total = counts[0] + counts[1];
            double rate = total == 0 ? 0.0 : (double) counts[0] / total;
            rates.put(entry.getKey(), rate);
            if (rate > bestRate) {
                bestRate = rate;
                bestName = entry.getKey();
            }
        }

        if (!bestName.equals(activePolicyName)) {
            String previous = activePolicyName;
            activePolicyName = bestName;
            switchHistory.add(new PolicySwitchEvent(operationCount, previous, bestName, rates));
            while (switchHistory.size() > MAX_SWITCH_HISTORY) {
                switchHistory.remove(0);
            }
        }

        for (int[] counts : windowCounts.values()) {
            counts[0] = 0;
            counts[1] = 0;
        }
    }

    @SuppressWarnings("unchecked")
    private V placeholderValueFor(K key) {
        // Shadows that aren't currently active never receive the caller's
        // real value (the caller only ever calls put() once, against the
        // active shadow) -- they get a same-typed placeholder purely to stay
        // populated for realistic eviction bookkeeping. This is a documented
        // simplification: non-active shadows' *values* are not meaningful,
        // only their hit/miss/eviction *decisions* are, which is all the
        // adaptive layer actually needs from them.
        return (V) ("shadow-placeholder:" + key);
    }

    @Override
    public V remove(K key) {
        lock.lock();
        try {
            V result = null;
            for (var entry : shadows.entrySet()) {
                V removed = entry.getValue().remove(key);
                if (entry.getKey().equals(activePolicyName)) {
                    result = removed;
                }
            }
            return result;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean containsKey(K key) {
        lock.lock();
        try {
            return shadows.get(activePolicyName).containsKey(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int size() {
        lock.lock();
        try {
            return shadows.get(activePolicyName).size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clear() {
        lock.lock();
        try {
            for (Cache<K, V> shadow : shadows.values()) {
                shadow.clear();
            }
            for (int[] counts : windowCounts.values()) {
                counts[0] = 0;
                counts[1] = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CacheStats stats() {
        return stats;
    }

    @Override
    public String policyName() {
        return "ADAPTIVE(active=" + activePolicyName + ")";
    }

    @Override
    public Map<K, V> entrySnapshot() {
        lock.lock();
        try {
            return shadows.get(activePolicyName).entrySnapshot();
        } finally {
            lock.unlock();
        }
    }

    /** Currently active candidate's name, e.g. "LRU", "TinyLFU". */
    public String activePolicy() {
        return activePolicyName;
    }

    /** Bounded history of switch decisions, most recent last -- what the dashboard renders to show adaptation happening. */
    public List<PolicySwitchEvent> switchHistory() {
        return List.copyOf(switchHistory);
    }

    /** Per-shadow cumulative (not windowed) stats, for a debug/comparison view. */
    public Map<String, CacheStats.Snapshot> shadowStats() {
        lock.lock();
        try {
            Map<String, CacheStats.Snapshot> result = new LinkedHashMap<>();
            for (var entry : shadows.entrySet()) {
                result.put(entry.getKey(), entry.getValue().stats().snapshot());
            }
            return result;
        } finally {
            lock.unlock();
        }
    }
}
