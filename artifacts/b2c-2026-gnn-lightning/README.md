# B2C 2026: Lightning Network GNN Reproducibility Artifacts

Experiment artifacts for **Hidden Edge-State Inference in Bitcoin Lightning
Network Topologies**, by Davide Patti, Salvatore Monteleone, Enrico Russo and
Maurizio Palesi. The primary reproducibility target is the accepted-paper
revision: physical-channel-grouped splits and three models. The original
submitted two-model experiment is retained separately for historical comparison.

This package contains synthetic simulator data, not measurements of live
Lightning liquidity. Paper sources, PDFs and presentation files are not included
or modified by this artifact publication.

## Contents

| Location | Contents |
|---|---|
| `configs/` | Original 100-, 500- and 1000-node simulator configurations. |
| `data/exports/` | The three exact omniscient UltraViolet JSON snapshots. |
| `data/processed/` | Exact PyG tensors and JSON/JSONL sidecars used by both studies. |
| `experiments/b2c-2026-revision/` | Unmodified revised training script, study tests, reference metrics, runtime/input provenance, partitions, predictions and original verification record. |
| `experiments/b2c-2026-extended-abstract/` | Unmodified original training script and its recorded results. This directory name identifies the historical version, not a different task. |
| `scripts/` | Public integrity audit, independent metric audit, safe rerun entry point and result comparison. |
| `tests/` | Tests for integrity checks and output isolation. |
| `checksums.json` | SHA-256 and byte length of every published package file except this checksum inventory itself. |
| `artifact-manifest.json` | Version boundaries, simulator/converter provenance and study settings. |
| `PUBLICATION-CHECKS.json` | Recorded full-rerun comparisons, test results and conversion checks performed before publication. |

See [provenance and limits](PROVENANCE.md) for the distinction between the two
experiments and between repeating a fixed-snapshot analysis and generating a new
synthetic network. Repository code and author-owned synthetic artifacts are
distributed under the [repository MIT license](../../LICENSE).

## Environment

From a full clone of this repository:

```sh
cd artifacts/b2c-2026-gnn-lightning
python3.12 -m venv .venv
. .venv/bin/activate
python scripts/check_integrity.py
python -m pip install -r requirements.txt
```

The reference revised run used Python 3.12.11, CPU execution, one PyTorch thread,
PyTorch 2.12.1, PyG 2.8.0, NumPy 2.5.1, scikit-learn 1.9.0 and Matplotlib 3.11.0
on macOS arm64. `requirements.txt` pins the direct dependencies, including
NetworkX used by the historical script; it is not a complete transitive lockfile.
Use CPU execution to match the published setup. No Java installation or simulator
run is needed to audit or retrain from the included snapshots.

`graph.pt` uses PyTorch serialization. The supplied entry point checks package
hashes before loading it. Load only trusted artifacts, and use the Git commit
identifier to identify the exact package version being reproduced.

## 1. Audit the Saved Revised Results

This does not train any model and does not load the serialized PyTorch objects:

```sh
python scripts/verify.py
```

Expected report: `status: passed`, 18 unchanged input files, 90 paired partitions,
270 model fits, zero physical-channel overlap, and 810 recomputed per-run metrics.
It checks labels against the amount thresholds, both directions staying in each
physical-channel partition, saved predictions against all ROC-AUC/AP/accuracy
values, and the aggregate means and sample standard deviations.

The public audit is self-contained. The reference `results/verification.json`
also records checks of the author's frozen paper archive; that archive is not
required or claimed to be rechecked by the public audit.

## 2. Run the Tests

```sh
python -m unittest discover -s tests -v
python -m unittest discover -s experiments/b2c-2026-revision/tests -v
```

The study tests check grouped splitting, repeatability of partition membership,
and the absence of neighborhood aggregation from the MLP baselines. The public
entry-point tests check checksums, path handling and output isolation.

Before publication, all 13 tests passed, both complete training reruns matched
their stored metrics exactly, and all three JSON-to-PyG conversions reproduced
the original sidecars and tensor attributes. These checks used the recorded
environment; they did not include a fresh dependency installation or a new
simulator bootstrap. See `PUBLICATION-CHECKS.json` for the recorded scope.

## 3. Retrain the Revised Models

```sh
python scripts/reproduce.py
python scripts/compare_results.py runs/revised/results
```

The first command executes the exact revised training script and automatically
audits its new predictions. It produces metrics, summary/provenance JSON,
split indices, predictions, split audit, LaTeX table macros and ROC-AUC plots
under `runs/revised/`. It never changes the published reference results.

Each run name must be new. For another run:

```sh
python scripts/reproduce.py --run-name revised-repeat
python scripts/compare_results.py runs/revised-repeat/results
```

The comparison reports the maximum absolute difference across all 810 per-run
metrics and returns a nonzero status if its tolerance (default `1e-6`) is exceeded.
Small numerical differences can occur with a different software build, platform
or backend; this is a comparison aid, not a guarantee of cross-platform bitwise
identity. Runtime durations and timestamps are not compared.

### Revised Experimental Settings

- One fixed snapshot at each of 100, 500 and 1000 nodes; 885, 5187 and 10797 physical channels, respectively.
- Binary label: source-side reserve-adjusted spendable liquidity is at least 1000, 10000 or 100000 sats. It is an amount-specific label, not a nonempty-balance test or a guarantee of end-to-end payment success.
- Ten seeds per size/amount setting; approximately 70/30 train/test partitions grouped by physical channel. Both directions stay together; groups are stratified by their number of usable directions.
- GraphSAGE, endpoint-aware MLP and edge-only MLP share each partition. The complete observed topology and features are visible; test labels do not enter the training loss. Feature standardization uses the fixed graph's observed features, without labels.
- Two 32-channel GraphSAGE layers; the endpoint MLP has two 32-channel node-wise layers and the same edge decoder. Parameter counts are 4769, 3585 and 289, respectively; they are not identical.
- 100 epochs, dropout 0.10, Adam, learning rate 0.01, weight decay `1e-4`, positive-class weighting from training labels, and an accuracy threshold of 0.5.
- Model inputs exclude local/remote balance and source/destination balance/spendable-balance columns. Synthetic balances remain available only for constructing labels and auditing the task.

ROC-AUC measures ranking across classification thresholds; AP is average
precision, summarizing the precision-recall ranking; accuracy uses the fixed
0.5 probability threshold. Aggregate dispersion is the sample standard deviation
across ten partition/initialization repeats, not independent-network uncertainty.

## 4. Repeat the Historical Submitted Experiment

```sh
python scripts/reproduce.py --experiment submitted
python scripts/compare_results.py runs/submitted/results --experiment submitted
```

This reproduces the original two-model, directed-edge-split comparison, not the
revised grouped evaluation. It writes only under `runs/submitted/`. Its bootstrap
timing fields will be `null` without the original simulator logs, and measured
training times will differ; neither field is part of metric comparison.

## Optional: Rebuild PyG Inputs from the Included JSON

From this package directory, with the same Python environment:

```sh
python ../../tools/uv-graph-to-pyg/uv_graph_to_pyg \
  data/exports/uv_omnigraph_exp100_b2c2026.json \
  runs/converted/uv-omnigraph-exp100-b2c2026-pyg
```

Repeat for sizes 500 and 1000 by replacing both occurrences of `100`. See the
[converter documentation](../../tools/uv-graph-to-pyg/README.md). The exact
converter Git blob is recorded in `artifact-manifest.json`. Serialization bytes
may differ after reconversion; the default study rerun deliberately uses the
original hash-verified PyG files rather than replacing them.

## Optional: Generate New Simulator Snapshots

From the repository root, with the JDK required by `run-uv.sh` (Java release 24
by default):

```sh
./run-uv.sh artifacts/b2c-2026-gnn-lightning/configs/uv-exp-100.properties
```

In UltraViolet, use `boot`, allow pending bootstrap announcements/queues to drain
(inspect with `qs`), then use `gexpall` and choose a new JSON output path. Repeat
with the 500- and 1000-node configurations as needed. The original snapshots were
captured before payment traffic or balance randomization, with seed 0 and the
template node-profile mix. All channels had one-sided opening balances and a 1%
reserve.

A new simulator run is a new input realization: seed 0 and the same node count
alone are not a promise of the archived snapshot's exact bytes or results. Use
the included snapshots for exact input provenance. Independent topologies,
payment-driven balances, temporal/partial-observation evaluation and live-network
validation are future work, not reproduced claims of this package.
