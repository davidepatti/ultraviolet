#!/usr/bin/env python3
"""Evaluate amount-aware liquidity with physical-channel-grouped label splits."""

from __future__ import annotations

import csv
import hashlib
import json
import os
import platform
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
EXPERIMENT_DIR = Path(__file__).resolve().parents[1]
RESULTS_DIR = EXPERIMENT_DIR / "results"
FIGURES_DIR = EXPERIMENT_DIR / "figures"
os.environ.setdefault("MPLCONFIGDIR", str(EXPERIMENT_DIR / ".matplotlib-cache"))

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import sklearn
import torch
import torch.nn as nn
import torch.nn.functional as F
import torch_geometric
from sklearn.metrics import accuracy_score, average_precision_score, roc_auc_score
from torch_geometric.nn import SAGEConv

torch.set_num_threads(1)

NETWORK_SIZES = [100, 500, 1000]
AMOUNT_THRESHOLDS_SAT = [1000, 10000, 100000]
NUM_SPLITS = 10
EPOCHS = 100
HIDDEN_CHANNELS = 32
TEST_FRACTION = 0.30
MODEL_NAMES = {"gnn": "GraphSAGE", "endpoint_mlp": "Endpoint MLP", "edge_mlp": "Edge MLP"}
NODE_INPUT_FEATURES = [
    "graph_in_degree", "graph_out_degree", "graph_incident_capacity_sat_log1p",
    "local_channel_count", "node_capacity_sat_log1p",
]
EDGE_INPUT_FEATURES = [
    "capacity_sat_log1p", "fee_base_msat_log1p", "fee_rate_milli_msat",
    "time_lock_delta", "policy_missing", "disabled", "direction_is_node2_to_node1",
]


@dataclass(frozen=True)
class Dataset:
    size: int
    x: torch.Tensor
    edge_index: torch.Tensor
    edge_attr: torch.Tensor
    groups: torch.Tensor
    spendable: torch.Tensor
    channels: list[dict]
    metadata: dict
    input_hashes: dict[str, str]


def edge_decoder(node_width: int, edge_in: int) -> nn.Sequential:
    return nn.Sequential(
        nn.Linear(2 * node_width + edge_in, HIDDEN_CHANNELS),
        nn.ReLU(), nn.Dropout(0.10), nn.Linear(HIDDEN_CHANNELS, 1),
    )


class EdgeLiquidityGNN(nn.Module):
    def __init__(self, node_in: int, edge_in: int):
        super().__init__()
        self.conv1 = SAGEConv(node_in, HIDDEN_CHANNELS)
        self.conv2 = SAGEConv(HIDDEN_CHANNELS, HIDDEN_CHANNELS)
        self.edge_mlp = edge_decoder(HIDDEN_CHANNELS, edge_in)

    def forward(self, x, edge_index, edge_attr):
        h = self.conv2(F.relu(self.conv1(x, edge_index)), edge_index)
        src, dst = edge_index
        return self.edge_mlp(torch.cat([h[src], h[dst], edge_attr], dim=-1)).squeeze(-1)


class EndpointFeatureMlp(nn.Module):
    def __init__(self, node_in: int, edge_in: int):
        super().__init__()
        self.node1 = nn.Linear(node_in, HIDDEN_CHANNELS)
        self.node2 = nn.Linear(HIDDEN_CHANNELS, HIDDEN_CHANNELS)
        self.edge_mlp = edge_decoder(HIDDEN_CHANNELS, edge_in)

    def forward(self, x, edge_index, edge_attr):
        h = self.node2(F.relu(self.node1(x)))
        src, dst = edge_index
        return self.edge_mlp(torch.cat([h[src], h[dst], edge_attr], dim=-1)).squeeze(-1)


class EdgeFeatureMlp(nn.Module):
    def __init__(self, node_in: int, edge_in: int):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(edge_in, HIDDEN_CHANNELS), nn.ReLU(),
            nn.Dropout(0.10), nn.Linear(HIDDEN_CHANNELS, 1),
        )

    def forward(self, x, edge_index, edge_attr):
        return self.net(edge_attr).squeeze(-1)


MODEL_CLASSES = {"gnn": EdgeLiquidityGNN, "endpoint_mlp": EndpointFeatureMlp, "edge_mlp": EdgeFeatureMlp}


def sha256(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def read_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle]


def standardize(features: torch.Tensor) -> torch.Tensor:
    std = features.std(dim=0, keepdim=True)
    return (features - features.mean(dim=0, keepdim=True)) / torch.where(std > 0, std, torch.ones_like(std))


def load_dataset(size: int) -> Dataset:
    directory = ROOT / "data" / "processed" / f"uv-omnigraph-exp{size}-b2c2026-pyg"
    metadata = json.loads((directory / "metadata.json").read_text())
    data = torch.load(directory / "graph.pt", weights_only=False)
    channels = read_jsonl(directory / "channels.jsonl")
    edges = read_jsonl(directory / "directed_edges.jsonl")
    groups = torch.tensor([edge["channel_idx"] for edge in edges], dtype=torch.long)
    for index, edge in enumerate(edges):
        if data.edge_index[:, index].tolist() != [edge["src"], edge["dst"]]:
            raise ValueError("Tensor edges and metadata have different order")
    grouped = {}
    for edge in edges:
        grouped.setdefault(edge["channel_idx"], []).append(edge)
    for pair in grouped.values():
        if (len(pair) != 2 or pair[0]["channel_id"] != pair[1]["channel_id"]
                or (pair[0]["src"], pair[0]["dst"]) != (pair[1]["dst"], pair[1]["src"])):
            raise ValueError("Each group must contain both directions of one physical channel")
    if len(channels) != len(grouped):
        raise ValueError("Physical channel count does not match direction groups")
    node_cols = [metadata["node_feature_names"].index(name) for name in NODE_INPUT_FEATURES]
    edge_cols = [metadata["edge_feature_names"].index(name) for name in EDGE_INPUT_FEATURES]
    files = [directory / name for name in ["metadata.json", "graph.pt", "nodes.jsonl", "channels.jsonl", "directed_edges.jsonl"]]
    files.append(ROOT / "data" / "exports" / f"uv_omnigraph_exp{size}_b2c2026.json")
    return Dataset(
        size, standardize(data.x[:, node_cols]), data.edge_index,
        standardize(data.edge_attr[:, edge_cols]), groups,
        torch.tensor([edge["source_spendable_balance_sat"] for edge in edges], dtype=torch.float32),
        channels, metadata, {str(path.relative_to(ROOT)): sha256(path) for path in files},
    )


def grouped_stratified_split(y: torch.Tensor, groups: torch.Tensor, seed: int):
    if y.numel() != groups.numel() or not torch.all((y == 0) | (y == 1)):
        raise ValueError("Labels and groups must align, with binary labels")
    group_ids, inverse, counts = np.unique(groups.numpy(), return_inverse=True, return_counts=True)
    if not np.all(counts == 2):
        raise ValueError("Each physical channel must have exactly two directed edges")
    # Stratify physical channels by their number of usable directions (0, 1, or 2).
    usable_count = np.bincount(inverse, weights=y.numpy()).astype(int)
    rng = np.random.default_rng(seed)
    test_groups = []
    for count in np.unique(usable_count):
        candidates = group_ids[usable_count == count].copy()
        if len(candidates) < 2:
            raise ValueError("Each channel stratum needs at least two members")
        rng.shuffle(candidates)
        test_count = min(len(candidates) - 1, max(1, round(len(candidates) * TEST_FRACTION)))
        test_groups.extend(candidates[:test_count].tolist())
    test_mask = np.isin(groups.numpy(), test_groups)
    train_idx = np.flatnonzero(~test_mask)
    test_idx = np.flatnonzero(test_mask)
    rng.shuffle(train_idx)
    rng.shuffle(test_idx)
    if set(groups[train_idx].tolist()) & set(groups[test_idx].tolist()):
        raise AssertionError("A physical channel occurs in both partitions")
    for indices in [train_idx, test_idx]:
        if len(np.unique(y[indices].numpy())) != 2:
            raise ValueError("Both partitions must contain both label classes")
    return torch.from_numpy(train_idx), torch.from_numpy(test_idx)


def train_model(key: str, dataset: Dataset, y, train_idx, test_idx, seed: int):
    torch.manual_seed(seed)
    model = MODEL_CLASSES[key](dataset.x.size(1), dataset.edge_attr.size(1))
    optimizer = torch.optim.Adam(model.parameters(), lr=0.01, weight_decay=1e-4)
    positives = y[train_idx].sum()
    pos_weight = (train_idx.numel() - positives) / positives
    for _ in range(EPOCHS):
        model.train()
        optimizer.zero_grad()
        logits = model(dataset.x, dataset.edge_index, dataset.edge_attr)
        loss = F.binary_cross_entropy_with_logits(logits[train_idx], y[train_idx], pos_weight=pos_weight)
        loss.backward()
        optimizer.step()
    model.eval()
    with torch.no_grad():
        probabilities = torch.sigmoid(model(dataset.x, dataset.edge_index, dataset.edge_attr)[test_idx]).numpy()
    labels = y[test_idx].numpy()
    metrics = {
        "auc": float(roc_auc_score(labels, probabilities)),
        "ap": float(average_precision_score(labels, probabilities)),
        "accuracy": float(accuracy_score(labels, probabilities >= 0.5)),
    }
    return metrics, probabilities, sum(p.numel() for p in model.parameters())


def mean_std(rows: list[dict], key: str) -> tuple[float, float]:
    values = np.array([row[key] for row in rows])
    return float(values.mean()), float(values.std(ddof=1))


def aggregate(rows: list[dict]) -> list[dict]:
    entries = []
    for size in NETWORK_SIZES:
        for amount in AMOUNT_THRESHOLDS_SAT:
            subset = [row for row in rows if row["network_size"] == size and row["amount_sat"] == amount]
            entry = {"network_size": size, "amount_sat": amount, "usable_share": subset[0]["usable_share"], "models": {}}
            for model in MODEL_NAMES:
                entry["models"][model] = {
                    metric: dict(zip(["mean", "std"], mean_std(subset, f"{model}_{metric}")))
                    for metric in ["auc", "ap", "accuracy"]
                }
            entries.append(entry)
    return entries


def write_latex(entries: list[dict]) -> None:
    table_rows = []
    for entry in entries:
        values = [str(entry["network_size"]), f"{entry['amount_sat'] // 1000}k", f"{100 * entry['usable_share']:.1f}\\%"]
        for metric in ["auc", "ap"]:
            for model in MODEL_NAMES:
                value = entry["models"][model][metric]
                values.append(f"{value['mean']:.3f} $\\pm$ {value['std']:.3f}")
        table_rows.append(" & ".join(values) + " \\\\")
    macros = [
        f"\\newcommand{{\\NumSplits}}{{{NUM_SPLITS}}}",
        f"\\newcommand{{\\NumEpochs}}{{{EPOCHS}}}",
        "\\newcommand{\\AmountThresholds}{1k, 10k, and 100k sats}",
        "\\newcommand{\\NodeInputFeatureList}{degree, incident capacity, local channel count, node capacity}",
        "\\newcommand{\\EdgeInputFeatureList}{capacity, base fee, fee rate, timelock delta, policy flags, direction}",
        "\\newcommand{\\MetricTableRows}{%", *table_rows, "}",
    ]
    (RESULTS_DIR / "directed-liquidity-summary.tex").write_text("\n".join(macros) + "\n")


def draw_results(entries: list[dict]) -> None:
    fig, axes = plt.subplots(1, 3, figsize=(7.1, 2.45), sharey=True)
    colors = ["#217660", "#ba5b31", "#447caf"]
    markers = ["o", "s", "^"]
    x = np.arange(len(AMOUNT_THRESHOLDS_SAT))
    for axis, size in zip(axes, NETWORK_SIZES):
        subset = [entry for entry in entries if entry["network_size"] == size]
        for offset, (key, name) in enumerate(MODEL_NAMES.items()):
            axis.errorbar(
                x + (offset - 1) * 0.10,
                [entry["models"][key]["auc"]["mean"] for entry in subset],
                yerr=[entry["models"][key]["auc"]["std"] for entry in subset],
                color=colors[offset], marker=markers[offset], linestyle=["-", "--", ":"][offset],
                linewidth=1.3, markersize=4.2, capsize=3, label=name,
            )
        axis.set_title(f"{size} nodes", fontsize=11)
        axis.set_xticks(x, ["1k", "10k", "100k"], fontsize=10)
        axis.set_xlabel("Payment amount (sats)", fontsize=10)
        axis.set_ylim(0.60, 1.015)
        axis.set_yticks([0.6, 0.7, 0.8, 0.9, 1.0])
        axis.tick_params(axis="y", labelsize=10)
        axis.grid(axis="y", alpha=0.25, linewidth=0.6)
        axis.spines[["top", "right"]].set_visible(False)
    axes[0].set_ylabel("ROC-AUC", fontsize=11)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=3, frameon=False, fontsize=10)
    fig.tight_layout(rect=(0, 0.16, 1, 1), pad=0.4, w_pad=0.65)
    fig.savefig(FIGURES_DIR / "directed-liquidity-results.pdf", bbox_inches="tight", pad_inches=0.05)
    fig.savefig(FIGURES_DIR / "directed-liquidity-results.png", dpi=220, bbox_inches="tight", pad_inches=0.05)
    plt.close(fig)


def main() -> None:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    FIGURES_DIR.mkdir(parents=True, exist_ok=True)
    started = datetime.now(timezone.utc).isoformat()
    rows, audits, snapshots, predictions, split_arrays = [], [], [], {}, {}
    parameter_counts = {}
    for size in NETWORK_SIZES:
        dataset = load_dataset(size)
        channels = dataset.channels
        snapshots.append({
            "nodes": size, "physical_channels": len(channels), "directed_edges": dataset.groups.numel(),
            "capture_time_utc": dataset.metadata["capture_time_utc"],
            "one_sided_channels": sum(sorted([c["node1_balance_sat"], c["node2_balance_sat"]]) == [0, c["capacity_sat"]] for c in channels),
            "one_percent_reserve_channels": sum(c["reserve_sat"] == int(0.01 * c["capacity_sat"]) for c in channels),
            "input_sha256": dataset.input_hashes,
        })
        for amount in AMOUNT_THRESHOLDS_SAT:
            y = (dataset.spendable >= amount).float()
            start = time.perf_counter()
            for seed in range(NUM_SPLITS):
                train_idx, test_idx = grouped_stratified_split(y, dataset.groups, seed)
                run_id = f"n{size}_a{amount}_s{seed}"
                split_arrays[f"{run_id}_train"] = train_idx.numpy()
                split_arrays[f"{run_id}_test"] = test_idx.numpy()
                predictions[f"{run_id}_labels"] = y[test_idx].numpy()
                row = {"network_size": size, "amount_sat": amount, "seed": seed,
                       "usable_share": float(y.mean()), "train_edges": len(train_idx), "test_edges": len(test_idx)}
                audits.append({
                    "run_id": run_id, "train_channels": len(train_idx) // 2, "test_channels": len(test_idx) // 2,
                    "channel_overlap": len(set(dataset.groups[train_idx].tolist()) & set(dataset.groups[test_idx].tolist())),
                    "train_usable_share": float(y[train_idx].mean()), "test_usable_share": float(y[test_idx].mean()),
                    "all_models_share_split": True,
                })
                for key in MODEL_NAMES:
                    metrics, probabilities, count = train_model(key, dataset, y, train_idx, test_idx, seed)
                    row.update({f"{key}_{metric}": value for metric, value in metrics.items()})
                    parameter_counts[key] = count
                    predictions[f"{run_id}_{key}"] = probabilities
                rows.append(row)
            subset = rows[-NUM_SPLITS:]
            scores = ", ".join(f"{MODEL_NAMES[key]}={mean_std(subset, key + '_auc')[0]:.3f}" for key in MODEL_NAMES)
            print(f"{size} nodes, {amount} sats: {scores} ({time.perf_counter() - start:.1f}s)", flush=True)
    with (RESULTS_DIR / "directed-liquidity-metrics.csv").open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    entries = aggregate(rows)
    summary = {
        "task": "source_spendable_balance_sat >= amount_sat",
        "started_utc": started, "completed_utc": datetime.now(timezone.utc).isoformat(),
        "setup": {
            "network_sizes": NETWORK_SIZES, "amount_thresholds_sat": AMOUNT_THRESHOLDS_SAT,
            "snapshots_per_size": 1, "bootstrap_seed": 0, "payment_traffic": False,
            "num_splits": NUM_SPLITS, "epochs": EPOCHS, "test_fraction": TEST_FRACTION,
            "split_unit": "physical channel; both directions together",
            "stratification": "number of usable directions per channel (0, 1, 2)",
            "full_public_graph_visible": True, "test_labels_used_in_training": False,
            "standardization": "all visible node/edge features within each fixed snapshot; no labels",
            "node_features": NODE_INPUT_FEATURES, "edge_features": EDGE_INPUT_FEATURES,
            "hidden_channels": HIDDEN_CHANNELS, "dropout": 0.10,
            "optimizer": "Adam", "learning_rate": 0.01, "weight_decay": 1e-4,
            "loss": "binary cross-entropy; positive class weight from training labels only",
            "accuracy_threshold": 0.5, "parameter_counts": parameter_counts,
            "models": MODEL_NAMES,
        },
        "runtime": {"python": platform.python_version(), "torch": torch.__version__,
                    "torch_geometric": torch_geometric.__version__, "numpy": np.__version__,
                    "sklearn": sklearn.__version__, "matplotlib": matplotlib.__version__,
                    "device": "cpu", "torch_threads": torch.get_num_threads(), "platform": platform.platform()},
        "experiment_script_sha256": sha256(Path(__file__)),
        "datasets": snapshots, "results": entries,
    }
    (RESULTS_DIR / "directed-liquidity-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    (RESULTS_DIR / "split-audit.json").write_text(json.dumps(audits, indent=2) + "\n")
    np.savez_compressed(RESULTS_DIR / "split-indices.npz", **split_arrays)
    np.savez_compressed(RESULTS_DIR / "test-predictions.npz", **predictions)
    write_latex(entries)
    draw_results(entries)
    print(f"Completed {len(rows)} paired runs; channel overlap is zero in every split.", flush=True)


if __name__ == "__main__":
    main()
