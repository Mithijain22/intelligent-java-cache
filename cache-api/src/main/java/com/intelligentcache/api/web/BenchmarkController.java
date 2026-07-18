package com.intelligentcache.api.web;

import com.intelligentcache.api.web.dto.BenchmarkRequest;
import com.intelligentcache.engine.ARCCache;
import com.intelligentcache.engine.Cache;
import com.intelligentcache.engine.LFUCache;
import com.intelligentcache.engine.LRUCache;
import com.intelligentcache.engine.TinyLFUCache;
import com.intelligentcache.engine.bench.BenchmarkResult;
import com.intelligentcache.engine.bench.BenchmarkRunner;
import com.intelligentcache.engine.bench.TraceGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Exposes {@code cache-engine}'s benchmarking harness (Phase 6) over REST so
 * the dashboard (Phase 7) can trigger a trace replay and render a
 * side-by-side comparison chart. This controller is intentionally separate
 * from {@link CacheController}: it operates on its own short-lived cache
 * instances built per-request (one per requested policy), never touching the
 * long-lived "live" cache bean that backs the {@code /cache/*} endpoints --
 * a benchmark run shouldn't perturb whatever state a user has built up in
 * the live cache via the REST API.
 */
@RestController
@RequestMapping("/benchmark")
public class BenchmarkController {

    /** Runs a synthetically generated trace against the requested policies. */
    @PostMapping("/run")
    public Map<String, BenchmarkResult> runSynthetic(@RequestBody BenchmarkRequest request) {
        List<String> keys = generateTrace(request);
        Map<String, Supplier<Cache<String, String>>> factories = buildFactories(request.policies(), request.capacity());
        return BenchmarkRunner.runAll(request.traceType().name(), keys, factories);
    }

    /**
     * Runs a user-uploaded CSV trace (one key per line) against the
     * requested policies. {@code policies} is a comma-separated query param
     * (e.g. {@code ?policies=LRU,LFU}) since multipart requests don't carry
     * a JSON body alongside the file part in the common case.
     */
    @PostMapping("/upload")
    public Map<String, BenchmarkResult> runUploaded(
            @RequestParam("file") MultipartFile file,
            @RequestParam("policies") String policiesCsv,
            @RequestParam("capacity") int capacity) throws IOException {

        Path tmp = Files.createTempFile("uploaded-trace", ".csv");
        try {
            Files.write(tmp, file.getBytes());
            List<String> keys = TraceGenerator.fromCsv(tmp);
            List<String> policies = List.of(policiesCsv.split(","));
            Map<String, Supplier<Cache<String, String>>> factories = buildFactories(policies, capacity);
            return BenchmarkRunner.runAll("uploaded:" + file.getOriginalFilename(), keys, factories);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }



    private List<String> generateTrace(BenchmarkRequest req) {
        int[] indices = switch (req.traceType()) {
            case SEQUENTIAL -> TraceGenerator.sequential(req.length(), req.keyRange());
            case UNIFORM -> TraceGenerator.uniformRandom(req.length(), req.keyRange(), req.seed());
            case ZIPFIAN -> TraceGenerator.zipfian(
                    req.length(), req.keyRange(),
                    req.zipfianExponent() != null ? req.zipfianExponent() : 1.2,
                    req.seed());
            case LOOPING -> TraceGenerator.looping(
                    req.length(),
                    req.loopSize() != null ? req.loopSize() : Math.max(1, req.keyRange() / 2));
        };
        return TraceGenerator.toStringKeys(indices, "k");
    }

    private Map<String, Supplier<Cache<String, String>>> buildFactories(List<String> policyNames, int capacity) {
        Map<String, Supplier<Cache<String, String>>> factories = new LinkedHashMap<>();
        for (String name : policyNames) {
            switch (name.trim().toUpperCase()) {
                case "LRU" -> factories.put("LRU", () -> new LRUCache<>(capacity));
                case "LFU" -> factories.put("LFU", () -> new LFUCache<>(capacity));
                case "ARC" -> factories.put("ARC", () -> new ARCCache<>(capacity));
                case "TINYLFU" -> factories.put("TinyLFU", () -> new TinyLFUCache<>(capacity));
                default -> throw new IllegalArgumentException(
                        "Unknown policy: " + name + " (expected one of LRU, LFU, ARC, TinyLFU)");
            }
        }
        return factories;
    }
}
