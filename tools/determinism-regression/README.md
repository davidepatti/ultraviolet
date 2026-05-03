# Determinism Regression Tool

`determinism_regression.sh` compiles the simulator and runs the standalone deterministic regression harness in `tests/DeterminismRegressionTest.java`.

The harness intentionally avoids threaded live replay. It focuses on deterministic surfaces that can be checked without changing simulator behavior.

## Usage

```bash
tools/determinism-regression/determinism_regression.sh
```

## What It Tests

- config include and override parsing;
- seeded profile selection from explicit `Random` instances;
- seeded integer and double distribution generation;
- fixed imported topology snapshots;
- pathfinding snapshots on a fixed graph;
- save/load structural round trips;
- network report generation from frozen imported state.

## What It Avoids

- byte-for-byte bootstrap logs;
- P2P/gossip scheduling order;
- parallel invoice-routing outcomes;
- wall-clock report filenames.

See `docs/determinism.md` for the broader determinism policy.
