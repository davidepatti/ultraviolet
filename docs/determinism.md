# Determinism Scope

UltraViolet has a `seed` setting, but the live simulation is intentionally threaded. The seed can reproduce pseudo-random choices only when the order of those choices is also reproduced.

## Reproducible

- Config parsing and pure calls that consume an explicitly supplied `Random`.
- Importing a fixed topology with a fixed seed, when no P2P/timechain services are running.
- Deterministic pathfinding on a fixed graph when the test avoids equal-cost/tie-order ambiguity.
- Canonical state snapshots that sort nodes and channels before comparison.

## Not Suitable As Golden Replay

- Full bootstrap replay. Bootstrap worker scheduling changes which node consumes each thread-local random stream and when P2P messages are processed.
- Gossip/P2P convergence order. Queues, scheduled executors, wall-clock ticks, and message interleaving can change visible graph state.
- Parallel invoice campaigns. Payment attempts race for liquidity and HTLC queue processing.
- Logs and report filenames. They include wall-clock dates, durations, async batching, and scheduling-dependent ordering.
- External mempool blobs. `UVTransaction` deliberately uses UUIDs for `EXTERNAL_BLOB` transaction IDs.

## Regression Target

Use fixed inputs rather than bootstrapped threaded topology. The regression test in `tools/determinism_regression.sh` covers:

- config include and override parsing with repeated profile selection from the same RNG seed;
- distribution generation from explicitly seeded `Random` instances;
- imported-topology canonical snapshots with same-seed equality and alternate-seed sensitivity;
- deterministic pathfinding snapshots on the fixed graph;
- save/load structural round trips;
- network reports generated from a frozen imported state.

## Current Tests

### Config Includes And Profile Selection

This test writes a temporary config that includes `uv_configs/template.properties`, overrides a small deterministic subset, and loads it twice through `UVConfig`.

It verifies that the seed, `bootstrap_nodes`, and `max_threads` overrides are applied, that inherited profile attributes remain available, and that `selectProfileBy(...)` returns the same profile sequence when driven by the same explicit `Random` seed. This tests config determinism and profile-selection determinism without starting any simulator threads.

### Distribution Generator

This test calls `DistributionGenerator.generateIntSamples(...)` and `generateDoubleSamples(...)` with freshly constructed `Random` instances.

It verifies that equal seeds produce equal sample arrays and that at least one nearby alternate seed changes the integer sample array. This covers deterministic pseudo-random sampling for pure distribution code, not statistical correctness of every generated distribution.

### Imported Topology Snapshot

This test writes a fixed seven-node JSON topology, imports it twice with seed `7`, and compares a canonical snapshot.

The snapshot sorts nodes and channels before comparing aliases, channel endpoints, capacities, balances, and a simple path query. It also checks that at least one alternate seed changes seeded import choices, currently the imported channel balance direction. This intentionally avoids bootstrap, P2P services, and log ordering.

### Pathfinding On Fixed Graph

This test imports the same fixed topology and runs `BFS`, `SHORTEST_HOP`, `MINI_DIJKSTRA`, and `LND` pathfinders from `A` to `F`.

It compares the full pathfinding snapshot across two equal-seed imports, including returned path count, search stats, path text, cost, and cost components. The linear topology avoids equal-cost tie ambiguity. `LND` is expected to return no path because imported channels have null policies; that expectation is deterministic and documents the current behavior.

### Save/Load Round Trip

This test imports the fixed topology, captures its canonical snapshot, saves it through `saveStatus(...)`, then loads it into a new `UVNetwork` constructed with a different seed.

It verifies that the loaded network produces the same canonical structural snapshot. The comparison is structural rather than byte-for-byte serialization, so it tests restored simulator state without depending on object stream ordering details or temporary file names.

### Frozen Network Report

This test imports the fixed topology twice and generates `GlobalStats.generateNetworkReport()` from each frozen state.

It suppresses incidental console output from report generation and compares the returned report strings. This is a deterministic report test for frozen state only; it does not attempt to validate reports generated after threaded bootstrap or invoice campaigns.
