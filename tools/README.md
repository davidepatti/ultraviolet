# UltraViolet Tools

This directory contains helper tools. Each tool lives in its own subdirectory with its own README and examples.

## Tools Index

| Tool | Purpose | Entry point |
| --- | --- | --- |
| Determinism regression | Compile and run the deterministic regression harness. | `tools/determinism-regression/determinism_regression.sh` |
| Invoice report plots | Generate dependency-free PDF plots from invoice report CSV files. | `tools/invoice-report-plots/plot_invoice_report.py` |
| Latest invoice report plots | Find the latest root-level invoice report and plot it. | `tools/invoice-report-plots/plot_latest_invoice_report.sh` |
| Design-space generator and runner | Generate DSE configs, run automated experiments, and collect reports. | `tools/design-space-generator/uv_dse_gen`, `tools/design-space-generator/uv_dse_run` |
| Find missing pubkeys | Print `pk<n>` ids that do not appear on matching log/report lines. | `tools/find-missing/find_missing.sh` |
| UV graph to PyG | Convert UV node or omniscient graph JSON exports into PyTorch Geometric sidecars and `graph.pt`. | `tools/uv-graph-to-pyg/uv_graph_to_pyg` |

## Layout Rule

Tool-specific documentation belongs beside the tool under `tools/<tool-name>/README.md`.
General simulator documentation belongs under the repository root `docs/` directory.
