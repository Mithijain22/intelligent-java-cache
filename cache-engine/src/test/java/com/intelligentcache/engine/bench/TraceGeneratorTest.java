package com.intelligentcache.engine.bench;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TraceGeneratorTest {

    @Test
    void sequentialCyclesThroughKeyRange() {
        int[] trace = TraceGenerator.sequential(10, 3);
        assertArrayEquals(new int[]{0, 1, 2, 0, 1, 2, 0, 1, 2, 0}, trace);
    }

    @Test
    void uniformRandomStaysWithinKeyRange() {
        int[] trace = TraceGenerator.uniformRandom(1000, 50, 1L);
        for (int key : trace) {
            assertTrue(key >= 0 && key < 50);
        }
    }

    @Test
    void uniformRandomIsDeterministicForSameSeed() {
        int[] a = TraceGenerator.uniformRandom(100, 50, 42L);
        int[] b = TraceGenerator.uniformRandom(100, 50, 42L);
        assertArrayEquals(a, b);
    }

    @Test
    void zipfianStaysWithinKeyRangeAndIsSkewedTowardLowIndices() {
        int[] trace = TraceGenerator.zipfian(50_000, 100, 1.2, 99L);
        int[] counts = new int[100];
        for (int key : trace) {
            assertTrue(key >= 0 && key < 100);
            counts[key]++;
        }
        // key 0 (rank 1, "hottest") should be touched far more than key 99 (coldest)
        assertTrue(counts[0] > counts[99] * 10,
                "expected strong skew toward low-index keys: counts[0]=" + counts[0] + " counts[99]=" + counts[99]);
    }

    @Test
    void loopingRepeatsFixedCycle() {
        int[] trace = TraceGenerator.looping(7, 3);
        assertArrayEquals(new int[]{0, 1, 2, 0, 1, 2, 0}, trace);
    }

    @Test
    void toStringKeysAppliesPrefix() {
        List<String> keys = TraceGenerator.toStringKeys(new int[]{0, 1, 2}, "k-");
        assertEquals(List.of("k-0", "k-1", "k-2"), keys);
    }

    @Test
    void csvRoundTripSkipsBlankLines(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("trace.csv");
        Files.write(file, List.of("a", "b", "", "  ", "c"));
        List<String> loaded = TraceGenerator.fromCsv(file);
        assertEquals(List.of("a", "b", "c"), loaded);
    }
}
