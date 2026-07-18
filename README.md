# Intelligent Java Cache

A concurrent, in-memory caching engine built from scratch in Java, implementing **LRU, LFU, ARC, and TinyLFU** eviction policies, with TTL support, disk persistence, an adaptive policy layer, and a live benchmarking dashboard.

This is not a wrapper around Caffeine/Guava/Redis. Every data structure and eviction algorithm here — the doubly linked lists, the frequency buckets, ARC's four-list ghost tracking, the Count-Min Sketch — is implemented from scratch in `cache-engine`, which has **zero framework dependencies**. Everything else (REST/WebSocket API, React dashboard) is a consumer of that library, not part of it.

## What's actually real vs. what's a documented simplification

Every claim below was checked against a running implementation, not assumed. Where something is a deliberate simplification instead of the full production version, it's called out explicitly, both here and in the relevant class's javadoc:

- **TinyLFU** implements the core Count-Min-Sketch-gated admission filter, but uses a single main LRU region rather than Caffeine's windowed + segmented (probationary/protected) design. See `TinyLFUCache` javadoc.
- **Persistence** is periodic JSON snapshotting, not a write-ahead log — durability between snapshots is not guaranteed. See `SnapshotManager` javadoc.
- **The adaptive layer** is empirical shadow evaluation (real candidate policies running in parallel, switching to whichever is measurably winning), not a heuristic and not machine learning. See `AdaptiveCache` javadoc.
- **Reloading a persisted snapshot** restores key-value pairs, not recency/frequency history — every entry looks "freshly inserted" to its policy after a reload.

## Architecture

```
intelligent-java-cache/
├── cache-engine/            Plain Java library, zero framework dependency
│   └── com.intelligentcache.engine
│       ├── Cache<K,V>              shared interface every policy implements
│       ├── LRUCache, LFUCache      HashMap + doubly linked list, from scratch
│       ├── ARCCache                Megiddo & Modha's Adaptive Replacement Cache
│       ├── TinyLFUCache            LRU + Count-Min Sketch admission filter
│       ├── CountMinSketch          the frequency estimator TinyLFU is built on
│       ├── AdaptiveCache           shadow-evaluates all policies, switches live
│       ├── TtlDecorator            adds TTL to any Cache<K,V> (decorator pattern)
│       ├── SnapshotManager         JSON persistence (save/load/periodic)
│       └── bench/                  TraceGenerator + BenchmarkRunner
│
├── cache-api/                Spring Boot web layer (only place Spring is used)
│   └── com.intelligentcache.api
│       ├── config/CacheConfig       builds the live Cache bean from application.yml
│       ├── web/CacheController      PUT/GET/DELETE /cache/{key}, GET /cache/stats
│       ├── web/BenchmarkController  POST /benchmark/run, POST /benchmark/upload
│       └── websocket/                live stats + eviction events -> dashboard
│
└── frontend/                 React + Vite dashboard
    └── src/
        ├── components/MemoryBankGrid.jsx   signature element: capacity as literal slots
        ├── components/ComparisonChart.jsx  policy comparison bar chart (Recharts)
        └── components/ConfigPanel.jsx      trace config, run/upload benchmark
```

The dependency direction is one-way: `cache-api` depends on `cache-engine`, never the reverse. `cache-engine` could be dropped into a CLI tool, a batch job, or a different framework entirely without modification.

## Running it locally

**Requirements:** JDK 17+, Maven 3.8+, Node 18+, npm.

```bash
# 1. Build and test the engine + API (from repo root)
mvn clean install

# 2. Run the backend (serves REST + WebSocket on :8080)
cd cache-api
mvn spring-boot:run

# 3. In a second terminal, run the frontend dev server (serves on :5173, proxies API calls to :8080)
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. The backend also works standalone via curl:

```bash
curl -X PUT localhost:8080/cache/hello -H 'Content-Type: application/json' -d '{"value":"world"}'
curl localhost:8080/cache/hello
curl localhost:8080/cache/stats
```

### Docker Compose (single command)

```bash
docker compose up --build
```
Frontend on `http://localhost:5173`, API on `http://localhost:8080`.

## Concurrency design

Every policy (`LRUCache`, `LFUCache`, `TinyLFUCache`) uses **coarse-grained locking**: one `ReentrantLock` per cache instance, held for every operation including `get()`. That's a deliberate starting point, not an oversight: in every one of these policies, a plain read *mutates* shared structure (recency/frequency bookkeeping), so there's no clean "cheap read, expensive write" split to exploit with a `ReadWriteLock` — reads are writes here. The natural next optimization if profiling showed contention would be lock striping (partition the keyspace across N independent locks, each guarding its own sub-map/sub-list); it's documented as an extension in `LRUCache`'s javadoc rather than implemented speculatively, since adding it without a benchmark showing it's needed would just be complexity for its own sake.

`SimpleConcurrentCache` (Phase 1, no eviction) is the exception — it's backed directly by `ConcurrentHashMap`, which gives real lock-striped concurrency, because it has no recency/frequency state to protect.

A dedicated concurrency stress test (`ConcurrencyStressTest`) hammers every policy with 32 threads × 20,000 mixed get/put/remove operations each and asserts on the invariants that actually matter under concurrency: no exceptions, size never exceeds capacity, no negative sizes. All four policies pass.

## Benchmark results (measured, not estimated)

Numbers below are from an actual run of `BenchmarkRunner` against `TraceGenerator`-produced traces (capacity 100, key range 2000, 200,000 operations, seeded for reproducibility — see `cache-engine`'s `bench` package). Your numbers will differ by machine, but the *relative* story — which policy wins on which workload, and why — is the point.

| Trace | Policy | Hit rate | Evictions | Avg latency | Notes |
|---|---|---|---|---|---|
| Zipfian (skew=1.2) | LRU | 71.82% | 56,253 | 219ns | |
| Zipfian (skew=1.2) | **LFU** | **78.16%** | 43,582 | 413ns | frequency-aware wins on skewed access, as expected |
| Uniform random | LRU | 4.95% | 190,008 | 193ns | |
| Uniform random | LFU | 4.98% | 189,938 | 223ns | **tied**, as it should be — no policy has an edge with no structure in the access pattern (this is a sanity check, not a bug) |
| Looping (loop=150 > capacity=100) | LRU | 0.00% | 199,900 | 98ns | classic LRU thrashing — every key evicted exactly before its next use |
| Looping (loop=150 > capacity=100) | LFU | 0.00% | 199,900 | 129ns | **LFU also collapses here** — every key in the loop is touched with equal frequency, so LFU's tie-break falls back to recency and it behaves just like LRU. This is real, and it's the actual motivation for ARC/TinyLFU below, not a talking point invented after the fact. |

**TinyLFU's scan-resistance** (the headline result): capacity-100 cache warmed with 100 "hot" keys (20 rounds of access each), then flooded with 10,000 never-repeated scan keys.

| Policy | Hot keys surviving the scan | Admission rejections during scan |
|---|---|---|
| LRU | **0 / 100** | n/a |
| TinyLFU | **100 / 100** | 10,000 / 10,000 |

This didn't work on the first try. The initial `CountMinSketch` sizing (table width = 4x capacity) let enough scan keys slip past the admission filter via hash-collision noise from 10,000 distinct cold keys sharing a too-small table, so all 100 hot keys still got evicted eventually. Widening the table to 16x capacity and slowing the aging/reset cadence fixed it — see `CountMinSketch`'s javadoc for the full story. Leaving this in because "here's a bug our own testing caught and how we fixed it" is a more credible story than pretending it worked first time.

**ARC**, on a workload that shifts between a wide one-time scan and a repeating hot-key phase (so neither pure LRU nor pure LFU is right for the *whole* trace): ARC hit-count was competitive with the better of LRU/LFU (within ~10%) without being told in advance which phase was coming. ARC's core invariant — `|T1|+|T2| <= capacity` and `|B1|+|B2| <= capacity`, always — was stress-tested across 200,000 adversarial random operations (keyspace 10x capacity) with zero violations.

**The adaptive layer**, on a workload mixing a small hot set into a heavy stream of scan traffic: switched from LRU to LFU after its first 1,000-operation evaluation window (LRU windowed hit rate 2.8% vs LFU 5.3%), and stayed there. This is genuinely measured switching, not scripted — see `AdaptiveCache`'s javadoc for what "adaptive" does and doesn't mean here.

## What's a heuristic, what's measured, what's neither

Being explicit about this, since it's easy to oversell in a portfolio project:

- **TinyLFU's admission decision** (`candidateFreq > victimFreq`) is a direct comparison of two Count-Min Sketch estimates — a real, if approximate, measurement, not a rule of thumb.
- **ARC's `p` adaptation** is the original Megiddo & Modha algorithm exactly as published — not a heuristic invented for this project.
- **The adaptive policy layer** is empirical shadow evaluation: real candidate caches, real hit-rate measurement, switching to whichever is currently winning. It is not a heuristic that infers workload shape from proxy statistics, and it is not any form of machine learning. If anything it's a *stronger* claim than a heuristic, since nothing is being guessed — see `AdaptiveCache`'s javadoc for the full reasoning and its real, non-trivial cost (Nx memory and per-op work for N candidate policies).

## Testing

```
cache-engine: 60+ JUnit tests across Cache/LRU/LFU/ARC/TinyLFU/CountMinSketch/AdaptiveCache/TTL/SnapshotManager/TraceGenerator/BenchmarkRunner, plus a dedicated 32-thread concurrency stress test per policy.
cache-api: MockMvc tests for the REST layer (CacheController, BenchmarkController).
```

Run everything: `mvn test` from the repo root.

## Scope cuts, in the order they'd be cut if time ran short

1. Adaptive policy layer — implemented and tested at the `cache-engine` level, not yet wired into the REST API (would need a config shape for multiple candidate policies, which `CacheProperties` doesn't yet support).
2. ARC — implemented and invariant-tested, but is the least battle-hardened of the four policies (hardest algorithm here by a wide margin).
3. Persistence — implemented (JSON snapshot, periodic + shutdown-hook save), simple by design, no WAL.
4. TinyLFU — kept, deliberately: it's the project's biggest differentiator and has the clearest "before/after" story (see the scan-resistance table above).
5. Dashboard polish — functional, dark "memory diagnostics" design system, not pixel-hunted.
6. LRU + LFU + core engine + tests — never cut, and they weren't.

## A note on how this was actually verified

Built and tested in a sandboxed environment without Maven or full internet access. Rather than skip verification, `cache-engine` (zero external dependencies beyond Jackson) was compiled and run directly via the JDK's `javax.tools.ToolProvider` compiler API, and every algorithm claim in this README was checked against real runs of the code above — the exact numbers, the TinyLFU sizing bug, the ARC invariant stress test, the adaptive switching event, all really happened during development, not written from memory of how they "should" turn out. `cache-api` (Spring Boot) and `frontend` (React) were written against hand-built stub classes matching the real Spring/Jackson API shapes closely enough to catch real compile errors (and did catch two), but were not run against the real Spring Boot / npm toolchain, since package installs were blocked in that environment. Run `mvn clean install` and `npm install && npm run dev` on your machine to build and run them for real. 