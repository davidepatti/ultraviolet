#!/usr/bin/env python3
"""Audit published revised results or a fresh rerun without private workspace files."""

import argparse
import csv
import json
from pathlib import Path

from check_integrity import ROOT, check_integrity, digest


def verify(results):
    checked_files = check_integrity()
    import numpy as np
    from sklearn.metrics import accuracy_score, average_precision_score, roc_auc_score

    summary = json.loads((results / "directed-liquidity-summary.json").read_text())
    with (results / "directed-liquidity-metrics.csv").open() as handle:
        rows = list(csv.DictReader(handle))
    audits = json.loads((results / "split-audit.json").read_text())
    assert len(rows) == len(audits) == 90
    source = ROOT / "experiments/b2c-2026-revision/scripts/run_directed_liquidity_classification.py"
    assert digest(source) == summary["experiment_script_sha256"], "Experiment source differs"
    datasets = {}
    checked_inputs = 0
    for dataset in summary["datasets"]:
        for relative, expected in dataset["input_sha256"].items():
            path = ROOT / relative
            assert path.resolve().is_relative_to(ROOT), "Unsafe input path"
            assert digest(path) == expected, f"Input changed: {relative}"
            checked_inputs += 1
        size = dataset["nodes"]
        path = ROOT / "data/processed" / f"uv-omnigraph-exp{size}-b2c2026-pyg/directed_edges.jsonl"
        with path.open() as handle:
            edges = [json.loads(line) for line in handle]
        datasets[size] = (
            np.array([edge["channel_idx"] for edge in edges]),
            np.array([edge["source_spendable_balance_sat"] for edge in edges]),
        )
    audit_by_id = {item["run_id"]: item for item in audits}
    assert len(audit_by_id) == 90
    seen = set()
    with np.load(results / "split-indices.npz", allow_pickle=False) as splits, np.load(results / "test-predictions.npz", allow_pickle=False) as predictions:
        assert len(splits.files) == 180 and len(predictions.files) == 360
        for row in rows:
            size, amount, seed = (int(row[key]) for key in ["network_size", "amount_sat", "seed"])
            assert (size, amount, seed) not in seen
            seen.add((size, amount, seed))
            groups, spendable = datasets[size]
            run_id = f"n{size}_a{amount}_s{seed}"
            train, test = splits[f"{run_id}_train"], splits[f"{run_id}_test"]
            assert len(train) == int(row["train_edges"]) and len(test) == int(row["test_edges"])
            np.testing.assert_array_equal(np.sort(np.concatenate([train, test])), np.arange(len(groups)))
            assert not np.intersect1d(groups[train], groups[test]).size
            for partition in [train, test]:
                assert np.all(np.unique(groups[partition], return_counts=True)[1] == 2)
                assert np.unique((spendable[partition] >= amount).astype(int)).size == 2
            audit = audit_by_id[run_id]
            assert audit["channel_overlap"] == 0 and audit["all_models_share_split"]
            assert audit["train_channels"] == len(train) // 2 and audit["test_channels"] == len(test) // 2
            for key, partition in [("train", train), ("test", test)]:
                assert np.isclose(audit[f"{key}_usable_share"], np.mean(spendable[partition] >= amount), atol=1e-7, rtol=0)
            labels = (spendable[test] >= amount).astype(int)
            np.testing.assert_array_equal(predictions[f"{run_id}_labels"], labels)
            for model in ["gnn", "endpoint_mlp", "edge_mlp"]:
                scores = predictions[f"{run_id}_{model}"]
                assert scores.shape == labels.shape and np.isfinite(scores).all()
                assert np.all((scores >= 0) & (scores <= 1))
                values = {"auc": roc_auc_score(labels, scores), "ap": average_precision_score(labels, scores),
                          "accuracy": accuracy_score(labels, scores >= 0.5)}
                for metric, value in values.items():
                    assert np.isclose(value, float(row[f"{model}_{metric}"]), atol=1e-12, rtol=0)
    assert seen == {(n, a, s) for n in [100, 500, 1000] for a in [1000, 10000, 100000] for s in range(10)}
    assert len(summary["results"]) == 9
    for result in summary["results"]:
        subset = [row for row in rows if int(row["network_size"]) == result["network_size"] and int(row["amount_sat"]) == result["amount_sat"]]
        assert len(subset) == 10
        for model, metrics in result["models"].items():
            for metric, values in metrics.items():
                samples = [float(row[f"{model}_{metric}"]) for row in subset]
                assert np.isclose(np.mean(samples), values["mean"], atol=1e-12, rtol=0)
                assert np.isclose(np.std(samples, ddof=1), values["std"], atol=1e-12, rtol=0)
    return {"status": "passed", "published_files_verified": checked_files,
            "unchanged_input_files": checked_inputs, "paired_splits": len(rows),
            "model_fits": len(rows) * 3, "channel_overlap": 0,
            "recomputed_metrics": len(rows) * 3 * 3,
            "scope": "public experiment audit; private paper/archive checks are not required"}


def main():
    if __debug__ is False:
        raise RuntimeError("Run without -O: audit assertions must remain enabled")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, default=ROOT / "experiments/b2c-2026-revision/results")
    parser.add_argument("--report", type=Path, help="Optional new report path; existing files are not overwritten")
    args = parser.parse_args()
    report = verify(args.results_dir.resolve())
    text = json.dumps(report, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open("x") as handle:
            handle.write(text)
    print(text, end="")


if __name__ == "__main__":
    main()
