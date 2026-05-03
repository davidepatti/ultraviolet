# UltraViolet Documentation

This directory contains general simulator documentation. Tool-specific documentation lives under `tools/<tool-name>/README.md`.

## Documents

| Document | Purpose |
| --- | --- |
| `simulator_usage.md` | Interactive menu workflow, command reference, example outputs, and interpretation notes. |
| `configuration.md` | `.properties` file format, include support, and parameter meanings. |
| `results.md` | Output files written by `wr`, report formats, and how to interpret generated results. |
| `invoice_report_format.md` | Detailed invoice CSV schema and staged routing interpretation rules. |
| `classes.md` | Source class map and how simulator packages interact. |
| `simulation_model.md` | Conceptual model of bootstrap, timechain, gossip, routing, and determinism limits. |
| `determinism.md` | Determinism policy and deterministic regression test scope. |

## Suggested Reading Order

1. Start with `simulator_usage.md` to understand the interactive workflow.
2. Read `configuration.md` before changing experiments.
3. Use `results.md` and `invoice_report_format.md` when interpreting reports.
4. Use `simulation_model.md` and `classes.md` when modifying simulator behavior.
