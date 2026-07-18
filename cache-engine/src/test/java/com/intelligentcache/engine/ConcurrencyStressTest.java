package com.intelligentcache.engine;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hammers every eviction policy with many concurrent threads doing mixed
 * get/put/remove traffic and checks for the failure modes that matter under
 * concurrency: no exceptions, no corrupted internal state (size never
 * exceeds capacity, no negative sizes), and no crashes. This does NOT (and
 * cannot, practically) prove the absence of every possible race -- but
 * catching capacity/size invariant violations here has already caught real
 * bugs during this project's development (see ARCCacheTest and the
 * TinyLFU sketch-sizing story in the README), so it earns its place.
 */
class ConcurrencyStressTest {

    private static final int THREADS = 32;
    private static final int OPS_PER_THREAD = 20_000;
    private static final int CAPACITY = 200;
    private static final int KEY_RANGE = 1000; // > capacity, guarantees real eviction pressure

    @Test
    void lruSurvivesConcurrentStress() throws InterruptedException {
        stressTest(new LRUCache<>(CAPACITY));
    }

    @Test
    void lfuSurvivesConcurrentStress() throws InterruptedException {
        stressTest(new LFUCache<>(CAPACITY));
    }

    @Test
    void arcSurvivesConcurrentStress() throws InterruptedException {
        stressTest(new ARCCache<>(CAPACITY));
    }

    @Test
    void tinyLfuSurvivesConcurrentStress() throws InterruptedException {
        stressTest(new TinyLFUCache<>(CAPACITY));
    }

    private void stressTest(Cache<Integer, Integer> cache) throws InterruptedException {
        AtomicInteger exceptions = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(THREADS);
        List<Thread> threads = new java.util.ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            int seed = t;
            Thread thread = new Thread(() -> {
                try {
                    java.util.Random random = new java.util.Random(seed);
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        int key = random.nextInt(KEY_RANGE);
                        int op = random.nextInt(10);
                        if (op < 6) {
                            cache.get(key);
                        } else if (op < 9) {
                            cache.put(key, key);
                        } else {
                            cache.remove(key);
                        }
                        assertTrue(cache.size() >= 0, "size went negative -- corrupted internal state");
                        assertTrue(cache.size() <= CAPACITY, "size exceeded capacity under concurrent access");
                    }
                } catch (Exception e) {
                    exceptions.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }

        boolean completed = latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(completed, "stress test threads did not complete within timeout -- possible deadlock");
        assertEquals(0, exceptions.get(), "one or more threads threw an exception during concurrent access");
        assertTrue(cache.size() <= CAPACITY, "final size exceeds capacity after stress test");
    }
}
