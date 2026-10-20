#!/usr/bin/env python3
"""Compare per-run classification metrics, excluding timestamps and durations."""

import argparse
import csv
from pathlib import Path

from check_integrity import ROOT, check_integrity
from reproduce import EXPERIMENTS


def read_metrics(path, columns):
    with path.open() as handle:
        rows = list(csv.DictReader(handle))
    result = {}
    for row in rows:
        key = tuple(int(float(row[name])) for name in ["network_size", "amount_sat", "seed"])
        if key in result:
            raise ValueError(f"Duplicate experiment key: {key}")
        result[key] = {name: float(row[name]) for name in columns}
    return result


def compare(reference, actual, tolerance):
    if set(reference) != set(actual):
        raise ValueError("The reference and rerun contain different size/amount/seed combinations")
    differences = [abs(reference[key][name] - actual[key][name])
                   for key in reference for name in reference[key]]
    if not differences:
        raise ValueError("No metrics to compare")
    return {"metrics": len(differences), "maximum_absolute_difference": max(differences),
            "tolerance": tolerance, "passed": all(value <= tolerance for value in differences)}


def main():
    import json
    import math

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results_dir", type=Path)
    parser.add_argument("--experiment", choices=EXPERIMENTS, default="revised")
    parser.add_argument("--tolerance", type=float, default=1e-6)
    args = parser.parse_args()
    if not math.isfinite(args.tolerance) or args.tolerance < 0:
        parser.error("tolerance must be finite and nonnegative")
    check_integrity()
    models = ["gnn", "endpoint_mlp", "edge_mlp"] if args.experiment == "revised" else ["gnn", "edge_mlp"]
    columns = [f"{model}_{metric}" for model in models for metric in ["auc", "ap", "accuracy"]]
    reference_path = ROOT / "experiments" / EXPERIMENTS[args.experiment] / "results/directed-liquidity-metrics.csv"
    reference = read_metrics(reference_path, columns)
    actual = read_metrics(args.results_dir / "directed-liquidity-metrics.csv", columns)
    if len(reference) != 90:
        raise ValueError("Expected 90 reference runs")
    report = compare(reference, actual, args.tolerance)
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["passed"] else 1)


if __name__ == "__main__":
    main()
