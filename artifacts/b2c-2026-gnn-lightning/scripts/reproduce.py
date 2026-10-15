#!/usr/bin/env python3
"""Run an unmodified study script with outputs redirected to a new run folder."""

import argparse
import importlib.util
import os
import re
import subprocess
import sys
from pathlib import Path

from check_integrity import ROOT, check_integrity

EXPERIMENTS = {
    "revised": "b2c-2026-revision",
    "submitted": "b2c-2026-extended-abstract",
}


def reserve_output(root, name):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]*", name):
        raise ValueError("Run names must use letters, digits, underscores or hyphens")
    runs = root / "runs"
    if runs.is_symlink():
        raise ValueError("The runs directory must not be a symbolic link")
    output = runs / name
    output.mkdir(parents=True, exist_ok=False)
    return output


def load_experiment(path):
    spec = importlib.util.spec_from_file_location("b2c_published_experiment", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def configure_output(module, output, version):
    module.ROOT = ROOT
    module.EXPERIMENT_DIR = output
    module.RESULTS_DIR = output / "results"
    module.FIGURES_DIR = output / "figures"
    if version == "submitted":
        module.METRICS_CSV = module.RESULTS_DIR / "directed-liquidity-metrics.csv"
        module.SUMMARY_JSON = module.RESULTS_DIR / "directed-liquidity-summary.json"
        module.SUMMARY_TEX = module.RESULTS_DIR / "directed-liquidity-summary.tex"
        module.RESULT_FIG = module.FIGURES_DIR / "directed-liquidity-results.pdf"
        module.METHODOLOGY_FIG = module.FIGURES_DIR / "methodology-dataflow.pdf"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--experiment", choices=EXPERIMENTS, default="revised")
    parser.add_argument("--run-name", help="New directory name under runs/; defaults to the experiment name")
    args = parser.parse_args()
    checked = check_integrity()
    output = reserve_output(ROOT, args.run_name or args.experiment)
    os.environ["MPLCONFIGDIR"] = str(output / ".matplotlib-cache")
    source = ROOT / "experiments" / EXPERIMENTS[args.experiment] / "scripts/run_directed_liquidity_classification.py"
    module = load_experiment(source)
    configure_output(module, output, args.experiment)
    print(f"Verified {checked} files. Running {args.experiment}; outputs: {output}", flush=True)
    module.main()
    if args.experiment == "revised":
        subprocess.run([
            sys.executable, str(Path(__file__).with_name("verify.py")),
            "--results-dir", str(output / "results"), "--report", str(output / "verification.json"),
        ], check=True)


if __name__ == "__main__":
    main()
