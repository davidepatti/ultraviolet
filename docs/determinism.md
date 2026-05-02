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

Use a fixed, imported topology rather than a bootstrapped threaded topology. The regression test in `tools/determinism_regression.sh` imports the same topology twice with the same seed, compares a canonical sorted snapshot, and checks that another seed can alter seeded channel-balance choices.
