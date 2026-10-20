#!/usr/bin/env python3
"""Run directed-liquidity classification on UV/PyG graph snapshots."""

from __future__ import annotations

import csv
import json
import os
import re
import time
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
EXPERIMENT_DIR = ROOT / "experiments" / "b2c-2026-extended-abstract"
os.environ.setdefault("MPLCONFIGDIR", str(EXPERIMENT_DIR / ".matplotlib-cache"))

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
import networkx as nx
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from matplotlib.patches import FancyBboxPatch, Polygon
from sklearn.metrics import accuracy_score, average_precision_score, roc_auc_score
from torch_geometric.nn import SAGEConv


torch.set_num_threads(1)

DATASETS = [
    {
        "label": "100",
        "config": "configs/uv-exp-100.properties",
        "pyg_dir": ROOT / "data" / "processed" / "uv-omnigraph-exp100-b2c2026-pyg",
    },
    {
        "label": "500",
        "config": "configs/uv-exp-500.properties",
        "pyg_dir": ROOT / "data" / "processed" / "uv-omnigraph-exp500-b2c2026-pyg",
    },
    {
        "label": "1000",
        "config": "configs/uv-exp-1000.properties",
        "pyg_dir": ROOT / "data" / "processed" / "uv-omnigraph-exp1000-b2c2026-pyg",
    },
]
RESULTS_DIR = EXPERIMENT_DIR / "results"
FIGURES_DIR = EXPERIMENT_DIR / "figures"
METRICS_CSV = RESULTS_DIR / "directed-liquidity-metrics.csv"
SUMMARY_JSON = RESULTS_DIR / "directed-liquidity-summary.json"
SUMMARY_TEX = RESULTS_DIR / "directed-liquidity-summary.tex"
RESULT_FIG = FIGURES_DIR / "directed-liquidity-results.pdf"
METHODOLOGY_FIG = FIGURES_DIR / "methodology-dataflow.pdf"

NUM_SPLITS = 10
EPOCHS = 100
AMOUNT_THRESHOLDS_SAT = [1000, 10000, 100000]

NODE_INPUT_FEATURES = [
    "graph_in_degree",
    "graph_out_degree",
    "graph_incident_capacity_sat_log1p",
    "local_channel_count",
    "node_capacity_sat_log1p",
]

EDGE_INPUT_FEATURES = [
    "capacity_sat_log1p",
    "fee_base_msat_log1p",
    "fee_rate_milli_msat",
    "time_lock_delta",
    "policy_missing",
    "disabled",
    "direction_is_node2_to_node1",
]

EXCLUDED_TARGET_LEAKAGE_FEATURES = [
    "local_balance_sat_log1p",
    "remote_balance_sat_log1p",
    "source_balance_sat_log1p",
    "destination_balance_sat_log1p",
    "source_spendable_balance_sat_log1p",
    "destination_spendable_balance_sat_log1p",
]


@dataclass(frozen=True)
class Dataset:
    label: str
    config: str
    metadata: dict
    data: object
    nodes: list[dict]
    channels: list[dict]
    directed_edges: list[dict]
    x: torch.Tensor
    edge_attr: torch.Tensor
    source_spendable_balance_sat: torch.Tensor


BOOTSTRAP_DURATION_PATTERN = re.compile(r"BOOTSTRAP: Completed.*duration \(ms\):(\d+)")


class EdgeLiquidityGNN(nn.Module):
    def __init__(self, node_in: int, edge_in: int, hidden_channels: int = 32):
        super().__init__()
        self.conv1 = SAGEConv(node_in, hidden_channels)
        self.conv2 = SAGEConv(hidden_channels, hidden_channels)
        self.edge_mlp = nn.Sequential(
            nn.Linear(hidden_channels * 2 + edge_in, hidden_channels),
            nn.ReLU(),
            nn.Dropout(0.10),
            nn.Linear(hidden_channels, 1),
        )

    def forward(self, x: torch.Tensor, edge_index: torch.Tensor, edge_attr: torch.Tensor) -> torch.Tensor:
        h = F.relu(self.conv1(x, edge_index))
        h = self.conv2(h, edge_index)
        src, dst = edge_index
        pair_features = torch.cat([h[src], h[dst], edge_attr], dim=-1)
        return self.edge_mlp(pair_features).squeeze(-1)


class EdgeFeatureMlp(nn.Module):
    def __init__(self, edge_in: int, hidden_channels: int = 32):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(edge_in, hidden_channels),
            nn.ReLU(),
            nn.Dropout(0.10),
            nn.Linear(hidden_channels, 1),
        )

    def forward(self, edge_attr: torch.Tensor) -> torch.Tensor:
        return self.net(edge_attr).squeeze(-1)


def read_jsonl(path: Path) -> list[dict]:
    with path.open("r", encoding="utf-8") as handle:
        return [json.loads(line) for line in handle]


def load_dataset(spec: dict) -> Dataset:
    dataset_dir = spec["pyg_dir"]
    metadata = json.loads((dataset_dir / "metadata.json").read_text(encoding="utf-8"))
    data = torch.load(dataset_dir / "graph.pt", weights_only=False)
    nodes = read_jsonl(dataset_dir / "nodes.jsonl")
    channels = read_jsonl(dataset_dir / "channels.jsonl")
    directed_edges = read_jsonl(dataset_dir / "directed_edges.jsonl")

    node_columns = [metadata["node_feature_names"].index(name) for name in NODE_INPUT_FEATURES]
    edge_columns = [metadata["edge_feature_names"].index(name) for name in EDGE_INPUT_FEATURES]
    x = standardize(data.x[:, node_columns])
    edge_attr = standardize(data.edge_attr[:, edge_columns])
    source_spendable_balance_sat = torch.tensor(
        [float(edge["source_spendable_balance_sat"]) for edge in directed_edges],
        dtype=torch.float32,
    )
    return Dataset(
        spec["label"],
        spec["config"],
        metadata,
        data,
        nodes,
        channels,
        directed_edges,
        x,
        edge_attr,
        source_spendable_balance_sat,
    )


def amount_label(amount_sat: int) -> str:
    if amount_sat >= 1000 and amount_sat % 1000 == 0:
        return f"{amount_sat // 1000}k"
    return str(amount_sat)


def labels_for_amount(dataset: Dataset, amount_sat: int) -> torch.Tensor:
    return (dataset.source_spendable_balance_sat >= amount_sat).to(torch.float32)


def standardize(features: torch.Tensor) -> torch.Tensor:
    mean = features.mean(dim=0, keepdim=True)
    std = features.std(dim=0, keepdim=True)
    std = torch.where(std > 0, std, torch.ones_like(std))
    return (features - mean) / std


def stratified_split(y: torch.Tensor, seed: int, test_fraction: float = 0.30) -> tuple[torch.Tensor, torch.Tensor]:
    rng = np.random.default_rng(seed)
    train_indices: list[int] = []
    test_indices: list[int] = []
    y_np = y.numpy()
    for label in [0, 1]:
        indices = np.flatnonzero(y_np == label)
        rng.shuffle(indices)
        test_count = max(1, round(len(indices) * test_fraction))
        test_indices.extend(indices[:test_count].tolist())
        train_indices.extend(indices[test_count:].tolist())
    rng.shuffle(train_indices)
    rng.shuffle(test_indices)
    return torch.tensor(train_indices, dtype=torch.long), torch.tensor(test_indices, dtype=torch.long)


def evaluate(labels: np.ndarray, probabilities: np.ndarray) -> dict[str, float]:
    predictions = (probabilities >= 0.5).astype(np.int64)
    return {
        "auc": float(roc_auc_score(labels, probabilities)),
        "ap": float(average_precision_score(labels, probabilities)),
        "accuracy": float(accuracy_score(labels, predictions)),
    }


def class_weight(labels: torch.Tensor) -> torch.Tensor:
    positives = labels.sum()
    negatives = labels.numel() - positives
    if positives == 0:
        return torch.tensor(1.0)
    return negatives / positives


def train_gnn(
    dataset: Dataset,
    y: torch.Tensor,
    train_idx: torch.Tensor,
    test_idx: torch.Tensor,
    seed: int,
) -> dict[str, float]:
    torch.manual_seed(seed)
    model = EdgeLiquidityGNN(dataset.x.size(1), dataset.edge_attr.size(1))
    optimizer = torch.optim.Adam(model.parameters(), lr=0.01, weight_decay=1e-4)
    pos_weight = class_weight(y[train_idx])

    for _ in range(EPOCHS):
        model.train()
        optimizer.zero_grad()
        logits = model(dataset.x, dataset.data.edge_index, dataset.edge_attr)
        loss = F.binary_cross_entropy_with_logits(logits[train_idx], y[train_idx], pos_weight=pos_weight)
        loss.backward()
        optimizer.step()

    model.eval()
    with torch.no_grad():
        logits = model(dataset.x, dataset.data.edge_index, dataset.edge_attr)
        probabilities = torch.sigmoid(logits[test_idx]).cpu().numpy()
    labels = y[test_idx].cpu().numpy()
    return evaluate(labels, probabilities)


def train_mlp(
    dataset: Dataset,
    y: torch.Tensor,
    train_idx: torch.Tensor,
    test_idx: torch.Tensor,
    seed: int,
) -> dict[str, float]:
    torch.manual_seed(seed)
    model = EdgeFeatureMlp(dataset.edge_attr.size(1))
    optimizer = torch.optim.Adam(model.parameters(), lr=0.01, weight_decay=1e-4)
    pos_weight = class_weight(y[train_idx])

    for _ in range(EPOCHS):
        model.train()
        optimizer.zero_grad()
        logits = model(dataset.edge_attr)
        loss = F.binary_cross_entropy_with_logits(logits[train_idx], y[train_idx], pos_weight=pos_weight)
        loss.backward()
        optimizer.step()

    model.eval()
    with torch.no_grad():
        logits = model(dataset.edge_attr)
        probabilities = torch.sigmoid(logits[test_idx]).cpu().numpy()
    labels = y[test_idx].cpu().numpy()
    return evaluate(labels, probabilities)


def channel_pairs(channels: list[dict], node_lookup: dict[str, int]) -> list[tuple[int, int]]:
    pairs = []
    for channel in channels:
        src = node_lookup[channel["node1_pub"]]
        dst = node_lookup[channel["node2_pub"]]
        if src != dst:
            pairs.append(tuple(sorted((src, dst))))
    return sorted(set(pairs))


def dataset_summary(dataset: Dataset) -> dict:
    node_lookup = {node["pub_key"]: int(node["node_id"]) for node in dataset.nodes}
    pairs = channel_pairs(dataset.channels, node_lookup)
    graph = nx.Graph()
    graph.add_nodes_from(node_lookup.values())
    graph.add_edges_from(pairs)
    capacities = [int(channel["capacity_sat"]) for channel in dataset.channels]
    degrees = [degree for _, degree in graph.degree()]
    labels_by_amount = {}
    for amount_sat in AMOUNT_THRESHOLDS_SAT:
        labels = labels_for_amount(dataset, amount_sat).numpy()
        positives = int(labels.sum())
        labels_by_amount[str(amount_sat)] = {
            "amount_label": amount_label(amount_sat),
            "usable_directions": positives,
            "unusable_directions": int(labels.size - positives),
            "usable_share": float(positives / labels.size),
        }
    return {
        "network_size": dataset.label,
        "config": dataset.config,
        "source_file": dataset.metadata["source_file"],
        "capture_time_utc": dataset.metadata["capture_time_utc"],
        "snapshot_scope": dataset.metadata["snapshot_scope"],
        "nodes": len(dataset.nodes),
        "physical_channels": len(pairs),
        "directed_policy_edges": dataset.metadata["num_directed_edges"],
        "density": nx.density(graph),
        "average_degree": float(np.mean(degrees)),
        "max_degree": int(np.max(degrees)),
        "connected_components": nx.number_connected_components(graph),
        "total_capacity_sat": dataset.metadata["total_capacity_sat"],
        "median_channel_capacity_sat": float(np.median(capacities)),
        "amount_labels": labels_by_amount,
        "warnings": dataset.metadata["warnings"],
        "uv_bootstrap_seconds": uv_bootstrap_seconds(dataset.label),
    }


def uv_bootstrap_seconds(label: str) -> float | None:
    log_path = ROOT / "ultraviolet" / f"b2c_{label}.log"
    if not log_path.exists():
        return None
    matches = BOOTSTRAP_DURATION_PATTERN.findall(log_path.read_text(encoding="utf-8", errors="ignore"))
    if not matches:
        return None
    return round(int(matches[-1]) / 1000.0, 3)


def run_repeated_experiment(dataset: Dataset, amount_sat: int) -> list[dict[str, float | str]]:
    rows: list[dict[str, float | str]] = []
    y = labels_for_amount(dataset, amount_sat)
    positives = int(y.sum().item())
    negatives = int(y.numel() - positives)
    if positives == 0 or negatives == 0:
        raise ValueError(f"{dataset.label} at {amount_sat} sats has only one label class")
    for seed in range(NUM_SPLITS):
        train_idx, test_idx = stratified_split(y, seed)
        gnn = train_gnn(dataset, y, train_idx, test_idx, seed)
        mlp = train_mlp(dataset, y, train_idx, test_idx, seed)
        rows.append(
            {
                "network_size": dataset.label,
                "amount_sat": float(amount_sat),
                "amount_label": amount_label(amount_sat),
                "seed": float(seed),
                "gnn_auc": gnn["auc"],
                "gnn_ap": gnn["ap"],
                "gnn_accuracy": gnn["accuracy"],
                "edge_mlp_auc": mlp["auc"],
                "edge_mlp_ap": mlp["ap"],
                "edge_mlp_accuracy": mlp["accuracy"],
                "train_edges": float(train_idx.numel()),
                "test_edges": float(test_idx.numel()),
                "usable_edges": float(positives),
                "unusable_edges": float(negatives),
                "usable_share": float(positives / y.numel()),
            }
        )
    return rows


def write_metrics(rows: list[dict[str, float | str]]) -> None:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    fieldnames = [
        "network_size",
        "amount_sat",
        "amount_label",
        "seed",
        "gnn_auc",
        "gnn_ap",
        "gnn_accuracy",
        "edge_mlp_auc",
        "edge_mlp_ap",
        "edge_mlp_accuracy",
        "train_edges",
        "test_edges",
        "usable_edges",
        "unusable_edges",
        "usable_share",
        "processing_seconds",
        "per_split_seconds",
    ]
    with METRICS_CSV.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def rows_for_network(rows: list[dict[str, float | str]], network_size: str) -> list[dict[str, float | str]]:
    return [row for row in rows if row["network_size"] == network_size]


def rows_for_amount(rows: list[dict[str, float | str]], amount_sat: int) -> list[dict[str, float | str]]:
    return [row for row in rows if int(float(row["amount_sat"])) == amount_sat]


def rows_for_network_amount(
    rows: list[dict[str, float | str]],
    network_size: str,
    amount_sat: int,
) -> list[dict[str, float | str]]:
    return [row for row in rows_for_network(rows, network_size) if int(float(row["amount_sat"])) == amount_sat]


def metric_mean_std(rows: list[dict[str, float | str]], key: str) -> tuple[float, float]:
    values = np.array([float(row[key]) for row in rows], dtype=float)
    return float(values.mean()), float(values.std(ddof=1))


def metric_text(rows: list[dict[str, float | str]], key: str) -> str:
    mean, std = metric_mean_std(rows, key)
    return f"{mean:.3f} $\\pm$ {std:.3f}"


def percent_text(value: float) -> str:
    return f"{value * 100:.1f}\\%"


def write_latex_summary(summaries: list[dict], rows: list[dict[str, float | str]]) -> None:
    dataset_rows = []
    metric_rows = []
    scalability_rows = []
    for summary in summaries:
        label = summary["network_size"]
        subset = rows_for_network(rows, label)
        dataset_rows.append(
            f"{label} & {summary['nodes']} & {summary['physical_channels']} & "
            f"{summary['directed_policy_edges']} \\\\"
        )
        for amount_sat in AMOUNT_THRESHOLDS_SAT:
            amount_subset = rows_for_network_amount(rows, label, amount_sat)
            usable_share = float(amount_subset[0]["usable_share"])
            metric_rows.append(
                f"{label} & {amount_label(amount_sat)} & {percent_text(usable_share)} & "
                f"{metric_text(amount_subset, 'gnn_auc')} & {metric_text(amount_subset, 'edge_mlp_auc')} & "
                f"{metric_text(amount_subset, 'gnn_accuracy')} \\\\"
        )
        uv_time = summary.get("uv_bootstrap_seconds")
        uv_text = f"{uv_time:.1f}" if uv_time is not None else "--"
        gnn_seconds = float(summary.get("gnn_processing_seconds", 0.0))
        scalability_rows.append(
            f"{label} & {summary['nodes']} & {summary['directed_policy_edges']} & "
            f"{uv_text} & {gnn_seconds:.1f} \\\\"
        )

    content = "\n".join(
        [
            f"\\newcommand{{\\NumSplits}}{{{NUM_SPLITS}}}",
            f"\\newcommand{{\\NumEpochs}}{{{EPOCHS}}}",
            "\\newcommand{\\AmountThresholds}{1k, 10k, and 100k sats}",
            "\\newcommand{\\DatasetTableRows}{%",
            "\n".join(dataset_rows),
            "}",
            "\\newcommand{\\MetricTableRows}{%",
            "\n".join(metric_rows),
            "}",
            "\\newcommand{\\ScalabilityTableRows}{%",
            "\n".join(scalability_rows),
            "}",
            "\\newcommand{\\NodeInputFeatureList}{degree, incident capacity, local channel count, node capacity}",
            "\\newcommand{\\EdgeInputFeatureList}{capacity, base fee, fee rate, timelock delta, policy flags, direction}",
            "",
        ]
    )
    SUMMARY_TEX.write_text(content, encoding="utf-8")


def draw_results(rows: list[dict[str, float | str]]) -> None:
    FIGURES_DIR.mkdir(parents=True, exist_ok=True)
    network_labels = [spec["label"] for spec in DATASETS]
    amount_labels = [amount_label(amount) for amount in AMOUNT_THRESHOLDS_SAT]
    x = np.arange(len(AMOUNT_THRESHOLDS_SAT))
    width = 0.34

    fig, axes = plt.subplots(1, 3, figsize=(4.45, 1.72), sharey=True)
    for axis, network_label in zip(axes, network_labels):
        gnn_means, gnn_stds, mlp_means, mlp_stds = [], [], [], []
        for amount_sat in AMOUNT_THRESHOLDS_SAT:
            subset = rows_for_network_amount(rows, network_label, amount_sat)
            mean, std = metric_mean_std(subset, "gnn_auc")
            gnn_means.append(mean)
            gnn_stds.append(std)
            mean, std = metric_mean_std(subset, "edge_mlp_auc")
            mlp_means.append(mean)
            mlp_stds.append(std)
        axis.bar(x - width / 2, gnn_means, width, yerr=gnn_stds, capsize=2, label="GraphSAGE", color="#e9c46a")
        axis.bar(x + width / 2, mlp_means, width, yerr=mlp_stds, capsize=2, label="Edge MLP", color="#a8dadc")
        axis.set_title(f"{network_label} nodes", fontsize=8.5)
        axis.set_xticks(x)
        axis.set_xticklabels(amount_labels, fontsize=7.5)
        axis.set_xlabel("sats", fontsize=7.8)
        axis.set_ylim(0.0, 1.02)
        axis.tick_params(axis="y", labelsize=7.5)
        axis.grid(axis="y", alpha=0.25, linewidth=0.6)
    axes[0].set_ylabel("ROC-AUC", fontsize=7.8)
    axes[2].legend(loc="lower right", fontsize=6.4, frameon=False)
    fig.tight_layout(pad=0.10, w_pad=0.45)
    fig.savefig(RESULT_FIG, bbox_inches="tight")
    plt.close(fig)


def draw_methodology_dataflow() -> None:
    FIGURES_DIR.mkdir(parents=True, exist_ok=True)
    steps = [
        {
            "title": "Synthetic topology\nbootstrap",
            "detail": "profile mix\n100/500/1000 nodes",
            "facecolor": "#dbeafe",
            "edgecolor": "#1d4ed8",
        },
        {
            "title": "GNN input\nconversion",
            "detail": "directed graph\nfeatures + labels",
            "facecolor": "#dcfce7",
            "edgecolor": "#15803d",
        },
        {
            "title": "GNN\nprocessing",
            "detail": "GraphSAGE\nedge classifier",
            "facecolor": "#fef3c7",
            "edgecolor": "#b45309",
        },
        {
            "title": "Outputs",
            "detail": "metrics, tables,\nresult figure",
            "facecolor": "#fce7f3",
            "edgecolor": "#be185d",
        },
    ]
    arrows = [
        {
            "label": "graph snapshot",
            "color": "#2563eb",
            "style": "solid",
            "y": 0.75,
        },
        {
            "label": "features + labels",
            "color": "#0f766e",
            "style": "dashed",
            "y": 0.25,
        },
        {
            "label": "scores + metrics",
            "color": "#a21caf",
            "style": "dashdot",
            "y": 0.75,
        },
    ]
    fig, axis = plt.subplots(figsize=(7.25, 1.22))
    axis.set_axis_off()
    x_positions = np.array([0.105, 0.370, 0.630, 0.895])
    box_width = 0.145
    box_height = 0.46
    box_bottom = 0.27
    for idx, (step, x_pos) in enumerate(zip(steps, x_positions)):
        left = x_pos - box_width / 2
        right = x_pos + box_width / 2
        bottom = box_bottom
        top = box_bottom + box_height
        if idx < len(steps) - 1:
            box = FancyBboxPatch(
                (left, bottom),
                box_width,
                box_height,
                boxstyle="round,pad=0.018,rounding_size=0.035",
                facecolor=step["facecolor"],
                edgecolor=step["edgecolor"],
                linewidth=1.15,
                clip_on=False,
            )
            axis.add_patch(box)
        else:
            fold = 0.035
            document = Polygon(
                [
                    (left, bottom),
                    (right, bottom),
                    (right, top - fold),
                    (right - fold, top),
                    (left, top),
                ],
                closed=True,
                facecolor=step["facecolor"],
                edgecolor=step["edgecolor"],
                linewidth=1.15,
                joinstyle="round",
                clip_on=False,
            )
            fold_patch = Polygon(
                [
                    (right - fold, top),
                    (right - fold, top - fold),
                    (right, top - fold),
                ],
                closed=True,
                facecolor="#fbcfe8",
                edgecolor=step["edgecolor"],
                linewidth=0.8,
                clip_on=False,
            )
            axis.add_patch(document)
            axis.add_patch(fold_patch)
        axis.text(
            x_pos,
            box_bottom + box_height * 0.63,
            step["title"],
            ha="center",
            va="center",
            fontsize=7.3,
            fontweight="bold",
            color="#111827",
            linespacing=1.0,
        )
        axis.text(
            x_pos,
            box_bottom + box_height * 0.22,
            step["detail"],
            ha="center",
            va="center",
            fontsize=6.0,
            color="#374151",
            linespacing=1.05,
        )

    for idx, arrow in enumerate(arrows):
        start = x_positions[idx] + box_width / 2 + 0.018
        end = x_positions[idx + 1] - box_width / 2 - 0.018
        axis.annotate(
            "",
            xy=(end, 0.50),
            xytext=(start, 0.50),
            arrowprops={
                "arrowstyle": "-|>",
                "lw": 1.45,
                "color": arrow["color"],
                "linestyle": arrow["style"],
                "mutation_scale": 12,
                "shrinkA": 0,
                "shrinkB": 0,
            },
        )
        axis.text(
            (start + end) / 2,
            arrow["y"],
            arrow["label"],
            ha="center",
            va="center",
            fontsize=6.0,
            color=arrow["color"],
            linespacing=1.05,
        )
    axis.set_xlim(-0.02, 1.02)
    axis.set_ylim(0, 1)
    fig.tight_layout(pad=0.04)
    fig.savefig(METHODOLOGY_FIG, bbox_inches="tight", pad_inches=0.03)
    plt.close(fig)


def main() -> None:
    datasets = [load_dataset(spec) for spec in DATASETS]
    summaries = [dataset_summary(dataset) for dataset in datasets]
    rows: list[dict[str, float | str]] = []
    summary_by_label = {summary["network_size"]: summary for summary in summaries}
    for dataset in datasets:
        print(f"Running {dataset.label}-node experiment")
        dataset_start = time.perf_counter()
        for amount_sat in AMOUNT_THRESHOLDS_SAT:
            print(f"  amount threshold {amount_sat} sats")
            start = time.perf_counter()
            dataset_rows = run_repeated_experiment(dataset, amount_sat)
            processing_seconds = time.perf_counter() - start
            per_split_seconds = processing_seconds / NUM_SPLITS
            for row in dataset_rows:
                row["processing_seconds"] = processing_seconds
                row["per_split_seconds"] = per_split_seconds
            rows.extend(dataset_rows)
        dataset_processing_seconds = time.perf_counter() - dataset_start
        summary_by_label[dataset.label]["gnn_processing_seconds"] = round(dataset_processing_seconds, 3)
        summary_by_label[dataset.label]["gnn_seconds_per_split"] = round(
            dataset_processing_seconds / (NUM_SPLITS * len(AMOUNT_THRESHOLDS_SAT)),
            3,
        )

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    SUMMARY_JSON.write_text(
        json.dumps(
            {
                "task": "amount-aware directed edge classification: source_spendable_balance_sat >= amount_sat",
                "setup": {
                    "snapshots": [summary["config"] for summary in summaries],
                    "snapshot_scope": "actual_uv_network_state from UV gexpall",
                    "amount_thresholds_sat": AMOUNT_THRESHOLDS_SAT,
                    "num_splits": NUM_SPLITS,
                    "epochs": EPOCHS,
                    "test_fraction": 0.30,
                    "models": ["GraphSAGE + edge MLP", "edge-feature-only MLP"],
                },
                "inputs": {
                    "node_features": NODE_INPUT_FEATURES,
                    "edge_features": EDGE_INPUT_FEATURES,
                    "excluded_target_leakage_features": EXCLUDED_TARGET_LEAKAGE_FEATURES,
                },
                "outputs": {
                    "metrics_csv": str(METRICS_CSV.relative_to(ROOT)),
                    "latex_summary": str(SUMMARY_TEX.relative_to(ROOT)),
                    "result_figure": str(RESULT_FIG.relative_to(ROOT)),
                    "methodology_figure": str(METHODOLOGY_FIG.relative_to(ROOT)),
                },
                "datasets": summaries,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    write_metrics(rows)
    write_latex_summary(summaries, rows)
    draw_results(rows)
    draw_methodology_dataflow()

    print(f"Wrote {METRICS_CSV}")
    print(f"Wrote {SUMMARY_JSON}")
    print(f"Wrote {SUMMARY_TEX}")
    print(f"Wrote {RESULT_FIG}")
    print(f"Wrote {METHODOLOGY_FIG}")


if __name__ == "__main__":
    main()
