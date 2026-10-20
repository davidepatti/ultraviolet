# Provenance and Version Boundaries

## Included Evidence

The simulator implementation and converter are provided by this UltraViolet
repository. The simulation/export source revision used for the stored inputs is
`b7fa705e0fb9d7085bcc078a5e16ebe1fc5fa83b`. The original artifact-notes commit was
`5f15bc222b8909918bab9593fafab623d5eb77c0`; it contained configurations and notes,
not the complete revised experiment. This publication extends that folder.

The graph captures are dated 2026-07-08. The accepted-paper experiment was run on
2026-09-15, reusing those exact captures and converted files. It did not regenerate
topologies or introduce payment traffic, randomized balances or independent
network replications.

All 18 input-file hashes match the revised reference summary. The two training
scripts, the original study tests and the reference result files are copied
without modification. The revised training script SHA-256 is
`ee79d4cbc7a79fe3be7b96be989332ca6fe343646b5c996e14da5ef4e5b6f15e`.
`checksums.json` records every distributed file. Author-owned synthetic data and
experiment code are published under the repository's MIT license.

## Two Distinct Experiments

| Version | Models | Split unit | Interpretation |
|---|---|---|---|
| Original submitted study | GraphSAGE and edge-only MLP | Directed edge | Historical comparison; opposite channel directions can be in different partitions. |
| Accepted-paper revision | GraphSAGE, endpoint-aware MLP and edge-only MLP | Physical channel | Both directions stay together. All models use the same partition. |

The revised endpoint MLP uses the same endpoint-node and edge features as
GraphSAGE, without neighborhood aggregation. Width/depth and decoder structure
are controlled where applicable, but the total parameter counts differ. The
residual performance margin is therefore not a complete causal isolation of
aggregation from every architectural difference.

## Public Adaptations

The new entry point redirects output directories at runtime, leaving both
historical training scripts unchanged. Published reference results are never the
output target of the documented rerun commands. The public audit independently
checks hashes, grouped partitions, amount labels, predictions and reported
metrics. Unlike the author's original verification script, it does not require
or claim to verify private paper sources or the frozen submission archive.

Paper and slide files are outside this package and were not changed for this
publication. The original workspace verification report is retained as a dated
record, not as evidence that a reader has rerun archive checks.

## Scope and Limits

- The data are omniscient synthetic simulator exports. Per-side liquidity is not ordinarily public Lightning routing information.
- All snapshots reflect opening state: one-sided funding, a 1% reserve and no payment traffic. Bootstrap/profile correlations may contribute to the observed performance.
- Balance columns are excluded from model inputs but used to construct amount-dependent labels. Other public/profile-derived correlations can remain.
- The evaluation is transductive: the full observed graph and its features are available, while only training labels enter the loss. It is not an unseen-network test.
- Ten repeats vary partitions and model initialization within each fixed graph. There is only one topology per size, so size trends are descriptive, not causal scaling evidence.
- Source-side usability is not sufficient for end-to-end payment delivery: downstream liquidity, fees, policies and route constraints also matter.
- Routing improvements, rebalancing benefits, evolving real-world balances and live-network generalization were not evaluated here.

## Reproducibility Levels

1. **Integrity and metric audit:** verify the exact files and recompute all saved revised metrics without retraining.
2. **Fixed-input rerun:** retrain the original or revised models using the exact stored PyG inputs and documented seeds/settings. Numerical tolerance can be platform-dependent.
3. **Conversion repeat:** convert the stored JSON snapshots again and inspect equivalent tensors/sidecars; serialization bytes need not be identical.
4. **New simulation:** bootstrap and export another network with the recorded scenario settings. This is not a claim of exact regeneration of the archived captures.

For a permanent citation, use this folder at the publication's Git commit rather
than relying only on the moving `main` branch.
