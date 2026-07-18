package com.intelligentcache.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CountMinSketchTest {

    @Test
    void estimateNeverUnderestimatesTrueCount() {
        CountMinSketch<String> sketch = new CountMinSketch<>(100);
        for (int i = 0; i < 7; i++) sketch.increment("a");
        // Count-Min Sketch guarantees: estimate >= true count, always.
        // (collisions can only inflate, never deflate)
        assertTrue(sketch.estimate("a") >= 7);
    }

    @Test
    void unseenKeyEstimatesAsZero() {
        CountMinSketch<String> sketch = new CountMinSketch<>(100);
        sketch.increment("a"); // touch something else, table isn't all-zero
        assertEquals(0, sketch.estimate("never-touched"));
    }

    @Test
    void moreAccessedKeyHasHigherOrEqualEstimate() {
        CountMinSketch<String> sketch = new CountMinSketch<>(1000);
        for (int i = 0; i < 50; i++) sketch.increment("popular");
        for (int i = 0; i < 3; i++) sketch.increment("rare");
        assertTrue(sketch.estimate("popular") >= sketch.estimate("rare"));
    }

    @Test
    void agingEventuallyReducesLongStaleCounts() {
        // A key hammered early, then never touched again while a lot of
        // *other* traffic flows through, should eventually show a lower
        // estimate than it did right after being hammered -- that's aging
        // doing its job (without it, ancient popularity would never decay).
        CountMinSketch<String> sketch = new CountMinSketch<>(50); // small table -> aging triggers sooner
        for (int i = 0; i < 15; i++) sketch.increment("early-hot");
        int rightAfter = sketch.estimate("early-hot");

        for (int i = 0; i < 200_000; i++) {
            sketch.increment("other-" + (i % 5000));
        }

        int muchLater = sketch.estimate("early-hot");
        assertTrue(muchLater <= rightAfter, "expected aging to decay or hold steady, not increase, an untouched key's estimate");
    }
}
