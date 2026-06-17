# PyG Data Usage Guide

This document is for the downstream data user who receives an output directory created by
`tools/uv-graph-to-pyg/uv_graph_to_pyg` and wants to use it as source data for PyTorch Geometric
research.

It is intentionally separate from the tool README. The README explains how to run the converter from
an UltraViolet export. This guide explains what the converted files mean and how to start building a
PyG experiment from them.

## Expected Directory

A converted directory normally contains:

```text
graph.pt
metadata.json
nodes.jsonl
channels.jsonl
directed_edges.jsonl
```

If the converter was run with `--sidecars-only`, `graph.pt` is absent, but the JSON sidecars are still
available and can be used to reconstruct a PyG `Data` object.

## File Meanings

### `graph.pt`

`graph.pt` is a serialized `torch_geometric.data.Data` object. Use it when available.

The object contains:

- `data.x`: node feature matrix, shape `[num_nodes, num_node_features]`.
- `data.edge_index`: directed edge index, shape `[2, num_directed_edges]`.
- `data.edge_attr`: directed edge feature matrix, shape `[num_directed_edges, num_edge_features]`.
- `data.num_nodes`: number of nodes.
- `data.edge_active_mask`: boolean mask where an edge is usable for policy-aware experiments.
- `data.edge_channel_idx`: integer mapping from each directed edge row to `channels.jsonl`.
- `data.node_missing_mask`: boolean mask for nodes that were visible in a graph but lacked UV node state.

UltraViolet exports Lightning channels as directed policy edges. A bidirectional channel normally
becomes two directed edges, one per policy direction.

### `metadata.json`

`metadata.json` is the schema and provenance file. It should be read before designing a model.

Important fields:

- `source_schema`: original UV export schema, such as `uv-node-graph-v1` or `uv-omniscient-graph-v1`.
- `snapshot_scope`: `observer_graph` for a single-node graph view, or `actual_uv_network_state` for
  an omniscient export.
- `node_feature_names`: column names for `data.x`, in order.
- `edge_feature_names`: column names for `data.edge_attr`, in order.
- `normalization`: records simple transformations already applied by the converter, currently `log1p`
  for large nonnegative quantities.
- `warnings`: conversion notes, such as missing policies or disabled-state fields that UV does not
  model.

Do not hard-code feature positions in experiment code. Load feature names from `metadata.json` and
look up column indexes by name.

### `nodes.jsonl`

One JSON object per node. This is the human-readable node map.

Common fields:

- `node_id`: zero-based row index in `data.x`.
- `pub_key`: UV node public key.
- `alias`: UV alias.
- `graph_in_degree`, `graph_out_degree`: directed graph degree in the exported snapshot.
- `graph_incident_capacity_sat`: sum of visible incident channel capacities.
- `local_channel_count`, `node_capacity_sat`, `local_balance_sat`, `remote_balance_sat`: UV node
  state when available.
- `is_observer`: true only for the selected observer in `gexp` exports.
- `node_missing_announcement` or `node_missing_state`: missing-state indicators.

For `gexpall` exports, node state is global simulator state. For `gexp` exports, topology is the
observer's visible graph.

### `channels.jsonl`

One JSON object per unique channel visible in the exported graph.

Common fields:

- `channel_idx`: zero-based channel row index.
- `channel_id`: UV channel id.
- `node1_pub`, `node2_pub`: canonical channel endpoints.
- `capacity_sat`: total channel capacity.
- `reserve_sat`: channel reserve when UV channel state is available.
- `node1_balance_sat`, `node2_balance_sat`: per-side balances.
- `node1_spendable_balance_sat`, `node2_spendable_balance_sat`: balance minus reserve, floored at
  zero.
- `node1_policy_missing`, `node2_policy_missing`: direction-level missing-policy flags.

Use `channels.jsonl` when you need channel-level labels or want to aggregate directed edge predictions
back to physical channels.

### `directed_edges.jsonl`

One JSON object per directed graph edge. Row order matches `data.edge_index`, `data.edge_attr`,
`data.edge_active_mask`, and `data.edge_channel_idx`.

Common fields:

- `edge_id`: zero-based directed edge row index.
- `channel_idx`: foreign key into `channels.jsonl`.
- `src`, `dst`: integer node ids used in `data.edge_index`.
- `src_pub_key`, `dst_pub_key`: readable endpoint keys.
- `capacity_sat`: channel capacity.
- `fee_base_msat`, `fee_rate_milli_msat`, `time_lock_delta`: direction policy features.
- `policy_missing`: true when the direction lacks a usable routing policy.
- `disabled`, `disabled_missing`: disabled flag and missingness marker. UV currently does not model
  LND disabled-policy state, so `disabled_missing` is normally true.
- `source_balance_sat`, `destination_balance_sat`: per-side balances for this direction.
- `source_spendable_balance_sat`, `destination_spendable_balance_sat`: reserve-adjusted balances for
  this direction.
- `balance_missing`: true when balance state is unavailable.

For `gexpall`, balance fields are omniscient simulator state. They are useful for research on
liquidity distribution, but they are not information available to ordinary routing nodes.

## Minimal Loading Example

Use this when `graph.pt` is present:

```python
import json
from pathlib import Path

import torch

out_dir = Path("pyg-out-omniscient")

with (out_dir / "metadata.json").open("r", encoding="utf-8") as handle:
    metadata = json.load(handle)

data = torch.load(out_dir / "graph.pt", weights_only=False)

print(data)
print("node features:", metadata["node_feature_names"])
print("edge features:", metadata["edge_feature_names"])
print("snapshot scope:", metadata["snapshot_scope"])

active_edges = data.edge_active_mask
print("active directed edges:", int(active_edges.sum()), "of", data.edge_index.size(1))
```

## Feature Lookup Example

Use metadata feature names instead of numeric constants:

```python
node_names = metadata["node_feature_names"]
edge_names = metadata["edge_feature_names"]

node_capacity_col = node_names.index("node_capacity_sat_log1p")
fee_rate_col = edge_names.index("fee_rate_milli_msat")
source_liquidity_col = edge_names.index("source_spendable_balance_sat_log1p")

node_capacity = data.x[:, node_capacity_col]
fee_rate = data.edge_attr[:, fee_rate_col]
source_liquidity = data.edge_attr[:, source_liquidity_col]
```

## Reconstructing `Data` From Sidecars

Use this if only JSONL files are available:

```python
import json
import math
from pathlib import Path

import torch
from torch_geometric.data import Data


def read_jsonl(path):
    with path.open("r", encoding="utf-8") as handle:
        return [json.loads(line) for line in handle]


def log1p_nonnegative(value):
    return math.log1p(max(0, int(value or 0)))


out_dir = Path("pyg-out")
metadata = json.loads((out_dir / "metadata.json").read_text(encoding="utf-8"))
nodes = sorted(read_jsonl(out_dir / "nodes.jsonl"), key=lambda row: row["node_id"])
edges = sorted(read_jsonl(out_dir / "directed_edges.jsonl"), key=lambda row: row["edge_id"])

x = torch.tensor([
    [
        float(row.get("graph_in_degree", 0)),
        float(row.get("graph_out_degree", 0)),
        log1p_nonnegative(row.get("graph_incident_capacity_sat", 0)),
        float(row.get("local_channel_count", 0)),
        log1p_nonnegative(row.get("node_capacity_sat", 0)),
        log1p_nonnegative(row.get("local_balance_sat", 0)),
        log1p_nonnegative(row.get("remote_balance_sat", 0)),
        float(bool(row.get("is_observer", False))),
        float(bool(row.get("node_missing_announcement", False))),
    ]
    for row in nodes
], dtype=torch.float32)

edge_index = torch.tensor([
    [row["src"] for row in edges],
    [row["dst"] for row in edges],
], dtype=torch.long)

edge_attr = torch.tensor([
    [
        log1p_nonnegative(row.get("capacity_sat", 0)),
        log1p_nonnegative(row.get("fee_base_msat", 0)),
        float(row.get("fee_rate_milli_msat", 0)),
        float(row.get("time_lock_delta", 0)),
        float(bool(row.get("policy_missing", False))),
        float(bool(row.get("disabled", False))),
        float(bool(row.get("disabled_missing", True))),
        float(bool(row.get("direction_is_node2_to_node1", False))),
        log1p_nonnegative(row.get("source_balance_sat", 0)),
        log1p_nonnegative(row.get("destination_balance_sat", 0)),
        log1p_nonnegative(row.get("source_spendable_balance_sat", row.get("source_balance_sat", 0))),
        log1p_nonnegative(row.get("destination_spendable_balance_sat", row.get("destination_balance_sat", 0))),
        float(bool(row.get("balance_missing", False))),
    ]
    for row in edges
], dtype=torch.float32)

data = Data(x=x, edge_index=edge_index, edge_attr=edge_attr, num_nodes=len(nodes))
data.edge_active_mask = torch.tensor(
    [not row.get("policy_missing", False) and not row.get("disabled", False) for row in edges],
    dtype=torch.bool,
)
data.edge_channel_idx = torch.tensor([row["channel_idx"] for row in edges], dtype=torch.long)
```

Prefer `graph.pt` when possible because it is generated by the converter and already uses the official
feature order recorded in `metadata.json`.

## Simple GNN Skeleton

The exported data does not define a universal training target. Choose a task before training. Common
research tasks include:

- Node regression or classification, such as predicting future channel count or node role.
- Edge regression, such as predicting future liquidity or fee changes.
- Link prediction, such as predicting whether a future channel appears.
- Graph-level comparison, such as comparing snapshots from multiple simulation runs.
- Self-supervised representation learning when no labels are available.

The following example is a minimal node-level skeleton. It creates a synthetic binary label only to
show the mechanics. Replace `y` with a real target for a meaningful experiment.

```python
import json
from pathlib import Path

import torch
import torch.nn.functional as F
from torch_geometric.nn import GraphSAGE

out_dir = Path("pyg-out")
metadata = json.loads((out_dir / "metadata.json").read_text(encoding="utf-8"))
data = torch.load(out_dir / "graph.pt", weights_only=False)

node_names = metadata["node_feature_names"]
capacity_col = node_names.index("node_capacity_sat_log1p")

# Example placeholder target: high-capacity node vs. low-capacity node.
# Replace this with a task-specific label loaded from your experiment data.
y = (data.x[:, capacity_col] > data.x[:, capacity_col].median()).long()

train_mask = torch.rand(data.num_nodes) < 0.8
test_mask = ~train_mask

model = GraphSAGE(
    in_channels=data.x.size(-1),
    hidden_channels=64,
    num_layers=2,
    out_channels=2,
)

optimizer = torch.optim.Adam(model.parameters(), lr=0.01, weight_decay=1e-4)

for epoch in range(100):
    model.train()
    optimizer.zero_grad()
    logits = model(data.x, data.edge_index)
    loss = F.cross_entropy(logits[train_mask], y[train_mask])
    loss.backward()
    optimizer.step()

model.eval()
with torch.no_grad():
    pred = model(data.x, data.edge_index).argmax(dim=-1)
    accuracy = (pred[test_mask] == y[test_mask]).float().mean().item()

print("test accuracy:", accuracy)
```

This example ignores edge attributes. Many GNN layers do not consume `edge_attr` directly. If edge
features matter for the research question, choose an operator or architecture that supports them, such
as message-passing layers that use `edge_attr`, a custom `MessagePassing` module, or a heterogeneous
representation with channels as separate entities.

## Edge-Level Example

The next example creates a simple edge regression target from the omniscient liquidity feature. This
is a mechanics example, not a scientifically meaningful prediction task unless the target is separated
from the input features or taken from a future snapshot.

```python
import json
from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch_geometric.nn import GCNConv

out_dir = Path("pyg-out-omniscient")
metadata = json.loads((out_dir / "metadata.json").read_text(encoding="utf-8"))
data = torch.load(out_dir / "graph.pt", weights_only=False)

edge_names = metadata["edge_feature_names"]
target_col = edge_names.index("source_spendable_balance_sat_log1p")

edge_mask = data.edge_active_mask
target = data.edge_attr[:, target_col]


class EdgeRegressor(nn.Module):
    def __init__(self, node_in, edge_in, hidden):
        super().__init__()
        self.conv1 = GCNConv(node_in, hidden)
        self.conv2 = GCNConv(hidden, hidden)
        self.edge_mlp = nn.Sequential(
            nn.Linear(2 * hidden + edge_in, hidden),
            nn.ReLU(),
            nn.Linear(hidden, 1),
        )

    def forward(self, x, edge_index, edge_attr):
        h = F.relu(self.conv1(x, edge_index))
        h = self.conv2(h, edge_index)
        src, dst = edge_index
        pair = torch.cat([h[src], h[dst], edge_attr], dim=-1)
        return self.edge_mlp(pair).squeeze(-1)


model = EdgeRegressor(data.x.size(-1), data.edge_attr.size(-1), hidden=64)
optimizer = torch.optim.Adam(model.parameters(), lr=0.01)

for epoch in range(100):
    model.train()
    optimizer.zero_grad()
    prediction = model(data.x, data.edge_index, data.edge_attr)
    loss = F.mse_loss(prediction[edge_mask], target[edge_mask])
    loss.backward()
    optimizer.step()

print("final mse:", float(loss))
```

For a real edge-prediction experiment, avoid target leakage. For example, remove liquidity columns
from `edge_attr` if the model is supposed to predict liquidity, or use snapshot `t` as input and
snapshot `t + 1` as target.

## Guidance For An LLM Building An Experiment

When using this directory as input for code generation:

1. Inspect `metadata.json` first.
2. Prefer loading `graph.pt` if it exists.
3. Use `metadata["node_feature_names"]` and `metadata["edge_feature_names"]` to select columns.
4. Respect `edge_active_mask` when the task depends on usable channel policies.
5. Use `edge_channel_idx` and `channels.jsonl` to map directed edge predictions back to physical
   channels.
6. Do not invent labels. If no labels are supplied, choose a self-supervised task or ask for the
   experimental objective.
7. Watch for target leakage. Omniscient liquidity fields are powerful features, but they may also be
   the target of the experiment.
8. Preserve provenance by copying relevant fields from `metadata.json` into experiment logs.

Minimal prompt for a downstream code-generation LLM:

```text
Use the PyG export directory as source data. Read metadata.json first. Load graph.pt if present;
otherwise reconstruct Data from the JSONL sidecars. Use feature names from metadata instead of
hard-coded column indexes. Build a PyTorch Geometric experiment for the requested task, and explain
which fields are inputs, labels, masks, and provenance.
```

## Common Pitfalls

- The graph is directed. Do not assume `edge_index` is undirected.
- `policy_missing=true` means the edge may be unsuitable for routing-policy experiments.
- `disabled_missing=true` does not mean the edge is disabled. It means UV did not model that external
  LND field.
- `gexp` and `gexpall` have different semantics. `gexp` is an observer-visible graph; `gexpall` is a
  global simulator snapshot.
- `log1p` features are already transformed. Apply inverse transforms only when needed for reporting.
- A single snapshot rarely supports causal claims. Use multiple snapshots or controlled simulation
  runs for temporal questions.
