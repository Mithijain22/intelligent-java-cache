package com.intelligentcache.api.web.dto;

import java.util.List;

/**
 * Request body for {@code POST /benchmark/run}. {@code traceType} selects
 * which {@code TraceGenerator} method builds the workload; the
 * trace-specific fields ({@code zipfianExponent}, {@code loopSize}) are
 * only consulted for the matching trace type and ignored otherwise.
 */
public record BenchmarkRequest(
        TraceType traceType,
        int length,
        int keyRange,
        Double zipfianExponent, // used only when traceType == ZIPFIAN, defaults to 1.2 if null
        Integer loopSize,       // used only when traceType == LOOPING, defaults to keyRange/2 if null
        long seed,
        int capacity,
        List<String> policies   // e.g. ["LRU", "LFU"]
) {
    public enum TraceType { SEQUENTIAL, UNIFORM, ZIPFIAN, LOOPING }
}
