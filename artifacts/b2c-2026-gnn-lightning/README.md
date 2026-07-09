# B2C 2026 GNN Lightning Artifact Notes

This folder records the UltraViolet-side inputs and processing path used for the B2C 2026 graph-learning study on Lightning Network topology snapshots.

## Scope

- Network sizes: 100, 500, and 1000 simulated nodes.
- Bootstrap profile source: the default UltraViolet profile mix inherited from `uv_configs/template.properties`.
- Graph export scope: omniscient research snapshots of the simulated topology and directed channel state.
- Downstream format: PyTorch Geometric sidecars generated from the exported graph snapshots.

## Files

- `configs/uv-exp-100.properties`: 100-node bootstrap scenario.
- `configs/uv-exp-500.properties`: 500-node bootstrap scenario.
- `configs/uv-exp-1000.properties`: 1000-node bootstrap scenario.
- `artifact-manifest.json`: compact summary of the artifact pipeline and reusable tools.

## Regeneration Outline

1. Build UltraViolet from this repository.
2. Select one of the scenario configs in `configs/`.
3. Bootstrap the synthetic Lightning topology.
4. Export an omniscient graph snapshot for research use.
5. Convert the exported snapshot with `tools/uv-graph-to-pyg/uv_graph_to_pyg`.
6. Run the downstream PyTorch Geometric edge-classification analysis on the generated sidecars.

The folder is intended to make the experimental artifacts traceable to explicit simulator inputs and repository tools. For exact archival traceability, preserve the generated graph snapshots and downstream result files produced by a specific run.
