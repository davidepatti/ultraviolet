# Tools

This directory contains helper scripts for report inspection, plotting, and configuration generation.

## `find_missing.sh`

Scans a text file for `pk<n>` identifiers and prints the ones that never appear on lines containing a target string.

Usage:

```sh
./tools/find_missing.sh "INVOICE_PAID" 15 some_log.txt
```

## `plot_invoice_report.py`

Reads one or more invoice report CSV files and generates PDF plots that summarize routing outcomes and search behavior.

Usage:

```sh
python3 ./tools/plot_invoice_report.py test_invoice.202603281824.csv --output-dir figures/test_invoice.202603281824
```

## `plot_latest_invoice_report.sh`

Finds the most recent `*_invoice.*.csv` file in the project root and invokes `plot_invoice_report.py` with a matching output directory under `figures/`.

Usage:

```sh
./tools/plot_latest_invoice_report.sh
```

## `uv_dse_gen`

Generates a design-space directory of layered `.properties` files. Each generated file includes a base config with `@include=...` and overrides one unique combination from a JSON parameter space.

Usage:

```sh
./tools/uv_dse_gen uv_configs/template.properties tools/uv_dse_example.json
```

By default, the output directory is named `<F_stem>_<J_stem>` next to the base properties file. A `manifest.json` is also written to the output directory.

## `uv_dse_example.json`

Example parameter-space input for `uv_dse_gen`. It shows the supported JSON shape and can be used as a starting point for your own sweeps.
