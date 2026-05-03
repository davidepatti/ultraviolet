# Invoice Report Plotting Tools

UltraViolet now includes a small plotting toolkit for invoice report CSV files that:

- uses only the Python standard library
- writes vector PDF files directly
- avoids `matplotlib`, `numpy`, `pandas`, and LaTeX dependencies

## Files

- `tools/invoice-report-plots/plot_invoice_report.py`
  - main plotting CLI
- `tools/invoice-report-plots/plot_latest_invoice_report.sh`
  - convenience wrapper that finds the latest `*_invoice.*.csv` file in the project root and renders figures into `figures/<report-name>/`

## Basic Usage

Render figures from a specific invoice report:

```bash
python3 tools/invoice-report-plots/plot_invoice_report.py test_invoice.202603281824.csv --output-dir figures/test_invoice
```

Render figures from the latest invoice report in the repository root:

```bash
tools/invoice-report-plots/plot_latest_invoice_report.sh
```

## Command-Line Options

```text
python3 tools/invoice-report-plots/plot_invoice_report.py <report.csv> [<report2.csv> ...]
  --output-dir <dir>      Directory for generated PDFs
  --amount-bins <N>       Number of amount bins for the success-rate curve
  --scatter-limit <N>     Maximum scatter points plotted per group
  --title-prefix <text>   Prefix used in chart titles
```

## Output Figures

The script generates the following PDF files:

- `invoice_outcomes_by_group.pdf`
  - stacked bar chart of invoice-level outcomes:
    - `success`
    - `search_no_path`
    - `filtered_out`
    - `candidate_not_attempted`
    - `attempt_failed`
- `success_rate_by_amount_bin.pdf`
  - line chart of routing success rate versus invoice amount bins
- `search_expanded_edges_boxplot_log10.pdf`
  - box plot of `log10(search_expanded_edges + 1)` by group
- `candidate_paths_boxplot.pdf`
  - box plot of surviving `candidate_paths` by group
- `returned_path_filter_breakdown.pdf`
  - stacked bar chart showing how returned paths are consumed by post-search filtering
- `attempt_result_breakdown.pdf`
  - stacked bar chart showing attempted-path outcomes
- `search_exclusion_mix.pdf`
  - stacked bar chart of search pruning composition
- `amount_vs_search_expanded_edges.pdf`
  - scatter plot of invoice amount versus search complexity
- `figure_summary.txt`
  - compact textual summary useful for captions or table building

## Grouping Rules

The plotting script groups rows automatically:

- if a single report contains multiple `path_finder` strategies, plots are grouped by `path_finder`
- if multiple reports are provided and each report has one strategy, plots are grouped by report prefix
- if multiple reports and multiple strategies are mixed, plots are grouped by `report-prefix:path_finder`

## Scientific-Use Notes

- The figures are vector PDFs, so they can be imported directly into papers, slides, or Inkscape.
- Search-complexity figures use `log10(search_expanded_edges + 1)` to keep very large runs readable.
- `returned_path_filter_breakdown.pdf` and `attempt_result_breakdown.pdf` are especially useful when discussing where routing fails:
  - search stage
  - sender-side filtering stage
  - actual HTLC attempt stage
