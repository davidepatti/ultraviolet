# UV Graph to PyG

Convert graph snapshots exported from UltraViolet into files suitable for PyTorch Geometric experiments.

This tool consumes the intermediate JSON produced by the UV `gexp` and `gexpall` menu commands. These files are UV
graph snapshots, not raw `lncli describegraph` exports. The observer export preserves UV-visible nodes, channels,
directed edges, capacities, UV fee policy fields, balances when available, observer identity, and block height. The
omniscient export preserves the simulator's global topology plus per-side balances and reserve-adjusted spendable
balances. Fields that UV does not model, such as LND policy timestamps, disabled flags, HTLC min/max values, inbound
fees, addresses, and feature bits, are not invented.

## Usage

From the UV main menu:

```text
gexp
Node public key [pk0]:
Output JSON file [uv_graph_pk0.202606171530.json]:
```

or:

```text
gexpall
Output JSON file [uv_omnigraph.202606171530.json]:
```

Then convert the export:

```sh
tools/uv-graph-to-pyg/uv_graph_to_pyg uv_graph_pk0.202606171530.json pyg-out
tools/uv-graph-to-pyg/uv_graph_to_pyg uv_omnigraph.202606171530.json pyg-out-omniscient
```

The full conversion requires `torch` and `torch_geometric` in the Python environment because it writes `graph.pt`.
To validate the schema and write only dependency-free sidecars:

```sh
tools/uv-graph-to-pyg/uv_graph_to_pyg --sidecars-only uv_graph_pk0.202606171530.json pyg-out
tools/uv-graph-to-pyg/uv_graph_to_pyg --sidecars-only uv_omnigraph.202606171530.json pyg-out-omniscient
```

## Output

The output directory contains:

- `graph.pt`: `torch_geometric.data.Data`, unless `--sidecars-only` is used.
- `metadata.json`: schema, provenance, snapshot scope, feature names, normalization notes, counts, and warnings.
- `nodes.jsonl`: node id map and UV node metadata.
- `channels.jsonl`: one row per UV channel visible in the exported graph.
- `directed_edges.jsonl`: one row per directed graph edge. Row order matches `edge_index`, `edge_attr`, and masks.

Missing UV routing policies are kept in `directed_edges.jsonl` with `policy_missing=true`; the generated PyG object marks
those rows inactive in `data.edge_active_mask`. UV does not model LND's disabled-policy field, so exported edges also
carry `disabled_missing=true`.

For `gexpall` inputs, `source_balance_sat`, `destination_balance_sat`,
`source_spendable_balance_sat`, and `destination_spendable_balance_sat` are global simulator state. They are useful for
research experiments that intentionally study liquidity distribution, but they are not information available to ordinary
pathfinding nodes.

For downstream PyTorch Geometric usage, feature interpretation, and starter experiment examples, see
[`PYG_DATA_USAGE.md`](PYG_DATA_USAGE.md).
