# DSE Tools

This folder contains the UltraViolet design-space exploration workflow:

| Tool | Purpose |
| --- | --- |
| `uv_dse_gen` | Generate `.properties` files from a JSON parameter space. |
| `uv_dse_run` | Generate configs, run UltraViolet experiments, and collect structured outputs. |
| `uv_dse_visualizer.py` | Open a GUI or export PDFs from DSE runner outputs. |
| `uv_dse_wizard.py` | Open a single browser wizard for creating JSON, running DSE, and visualizing results. |

The normal workflow is:

```text
write DSE JSON -> run uv_dse_run -> open uv_dse_visualizer.py -> export figures
```

## GUI Wizard

The wizard is the single visual entry point for the DSE workflow. It keeps the command-line tools available, but lets you perform the same workflow from a browser UI.

From this folder:

```bash
./uv_dse_wizard.py
```

The main menu has three actions:

| Action | What It Opens |
| --- | --- |
| `Create DSE JSON` | A form that loads a properties file, lists parameters and current values, lets you select DSE value ranges, edit experiments, and load/save DSE JSON files. |
| `Run DSE` | A form for base properties, DSE JSON path, output directory, safe replacement controls, and optional `--limit`, then runs `uv_dse_run` as a background job with live output. |
| `Open DSE Visualizer` | The integrated report visualizer for selecting experiment, report, metric, graph type, filters, and PDF export. |

The JSON creator uses `../../uv_configs/template.properties` by default. DSE outputs are expected under `dse_runs/` inside this folder. Browse buttons open native OS file selectors: Finder dialogs on macOS, and `zenity` or `kdialog` on Ubuntu/Linux. If Ubuntu does not open a dialog, install `zenity`:

```bash
sudo apt install zenity
```

For safety, the wizard binds to `127.0.0.1` by default. Binding to a non-loopback host requires `--allow-remote`, and should be avoided unless the machine is otherwise protected. The GUI uses a per-session token for local action endpoints.

The Run DSE screen does not replace existing output directories unless replacement is explicitly enabled and confirmed. Long runs continue in the background; the page polls status and streams runner output.

The parameter editor has two value modes:

| Mode | Use It For |
| --- | --- |
| `single property value` | One property value, including comma-containing values such as `base_fee_set=0,100,1000`. |
| `list of DSE values` | A comma-separated list crossed into the Cartesian parameter space, such as `1, 7, 13`. |

## End-To-End Quickstart

This tutorial starts from an empty DSE setup and creates everything the visualizer needs.

The commands below assume your current directory is `tools/dse`. If you are starting from the repository root, enter this folder first:

```bash
cd tools/dse
```

### 1. Write The First DSE JSON

Create a small DSE file:

```bash
mkdir -p dse_runs/tutorial

cat > dse_runs/tutorial/first_dse.json <<'JSON'
{
  "parameters": {
    "bootstrap_nodes": [8],
    "bootstrap_blocks": [6],
    "blocktime_ms": [2],
    "node_services_tick_ms": [2],
    "gossip_flush_period_ms": [2],
    "p2p_max_age": [2],
    "gossip_flush_size": [200],
    "pathfinding_max_hops": [3, 6],
    "seed": [1, 7]
  },
  "experiments": [
    {
      "name": "quick_invoice_hops",
      "commands": [
        "boot",
        "rndbal",
        {
          "command": "inv",
          "node_events_per_block": 0.10,
          "blocks": 4,
          "min_amt": 50000,
          "max_amt": 100000,
          "max_fees": 1000,
          "path_finder": "lnd"
        }
      ],
      "outputs": ["network", "invoice"]
    }
  ]
}
JSON
```

This varies two parameters:

- `pathfinding_max_hops`: `3`, `6`
- `seed`: `1`, `7`

It runs one experiment, `quick_invoice_hops`, for each parameter combination. The command sequence is:

```text
boot -> rndbal -> inv
```

Expected runs:

```text
2 hop-limit values * 2 seeds = 4 runs
```

### 2. Run The DSE Runner

```bash
./uv_dse_run \
  ../../uv_configs/template.properties \
  dse_runs/tutorial/first_dse.json \
  --output-dir dse_runs/tutorial/output \
  --force
```

The runner will:

- generate config variations under `dse_runs/tutorial/output/configs/`;
- compile the simulator into a temporary build directory;
- execute every experiment for every generated config;
- write run metadata and reports under `dse_runs/tutorial/output/runs/`;
- write `runs_index.csv` and `runs_index.jsonl`.

The visualizer requires one of these index files:

```text
dse_runs/tutorial/output/runs_index.csv
dse_runs/tutorial/output/runs_index.jsonl
```

If they are missing, the selected directory is not a completed `uv_dse_run` output.

### 3. Open The Visualizer

Desktop/browser GUI:

```bash
./uv_dse_visualizer.py dse_runs/tutorial/output
```

If your Python installation does not include Tkinter, the tool automatically starts a local browser GUI and prints a URL. You can request the browser GUI explicitly:

```bash
./uv_dse_visualizer.py dse_runs/tutorial/output --web
```

### 4. Create Different Figures In The GUI

In the visualizer, make an invoice-search-work bar chart:

```text
Report: invoice
Metric: Average investigated states
X parameter: pathfinding_max_hops
Experiment: quick_invoice_hops
Graph: bar
Aggregation: mean
```

The parameter context should explain:

```text
Varied on x-axis: pathfinding_max_hops = 3, 6
Aggregated background variation: seed = 1, 7
```

This means the figure compares hop limits while averaging across seeds.

Make a network line chart:

```text
Report: network
Metric: Node Channels / Average
X parameter: pathfinding_max_hops
Experiment: quick_invoice_hops
Graph: line
Aggregation: mean
```

Make a filtered scatter plot:

```text
Report: invoice
Metric: Success rate (%)
X parameter: pathfinding_max_hops
Experiment: quick_invoice_hops
Fixed filters: seed=1
Graph: scatter
Aggregation: mean
```

Now the parameter context should show:

```text
Fixed by filter: seed = 1
```

Make a pie chart:

```text
Report: invoice
Metric: Invoice count
X parameter: pathfinding_max_hops
Experiment: quick_invoice_hops
Fixed filters: seed=1
Graph: pie
Aggregation: sum
```

Use `Create PDF` in the GUI to export a clean PDF.

### 5. Create The Same PDFs Without The GUI

Invoice search-work bar chart:

```bash
./uv_dse_visualizer.py dse_runs/tutorial/output \
  --no-gui \
  --report invoice \
  --metric invoice:avg_search_investigated_states \
  --x-param pathfinding_max_hops \
  --experiment quick_invoice_hops \
  --graph bar \
  --aggregation mean \
  --title "Search work by hop limit" \
  --output-pdf dse_runs/tutorial/output/search_work_by_hops.pdf
```

Network line chart:

```bash
./uv_dse_visualizer.py dse_runs/tutorial/output \
  --no-gui \
  --report network \
  --metric "network:Node Channels:Average" \
  --x-param pathfinding_max_hops \
  --experiment quick_invoice_hops \
  --graph line \
  --aggregation mean \
  --title "Average node channels by hop limit" \
  --output-pdf dse_runs/tutorial/output/node_channels_by_hops.pdf
```

Filtered success-rate scatter plot:

```bash
./uv_dse_visualizer.py dse_runs/tutorial/output \
  --no-gui \
  --report invoice \
  --metric invoice:success_rate \
  --x-param pathfinding_max_hops \
  --experiment quick_invoice_hops \
  --filter seed=1 \
  --graph scatter \
  --aggregation mean \
  --title "Success rate by hop limit, seed 1" \
  --output-pdf dse_runs/tutorial/output/success_rate_seed_1.pdf
```

Invoice-count pie chart:

```bash
./uv_dse_visualizer.py dse_runs/tutorial/output \
  --no-gui \
  --report invoice \
  --metric invoice:invoice_count \
  --x-param pathfinding_max_hops \
  --experiment quick_invoice_hops \
  --filter seed=1 \
  --graph pie \
  --aggregation sum \
  --title "Invoice count by hop limit, seed 1" \
  --output-pdf dse_runs/tutorial/output/invoice_count_seed_1.pdf
```

## Complete Guide

### DSE JSON Format

`uv_dse_run` expects a JSON object with:

- `parameters`: UltraViolet `.properties` keys and arrays of values.
- `experiments`: experiment definitions to run for every parameter combination.

Basic shape:

```json
{
  "parameters": {
    "bootstrap_nodes": [100],
    "seed": [1, 7]
  },
  "experiments": [
    {
      "name": "bootstrap_stats",
      "commands": ["boot"],
      "outputs": ["network"]
    }
  ]
}
```

The parameter arrays are crossed as a Cartesian product. For example:

```json
{
  "parameters": {
    "pathfinding_max_hops": [3, 6],
    "seed": [1, 7, 13]
  }
}
```

creates:

```text
2 * 3 = 6 generated configs
```

### Runner Commands

Experiment commands execute in order. A command can be a plain string when no options are needed, or an object when options are needed.

| Command | Effect | Common options |
| --- | --- | --- |
| `boot` | Bootstrap the network from the generated config. | none |
| `bal` | Set initiator-side channel balances to a fixed fraction. | `level`, `min_delta` |
| `rndbal` | Randomize initiator-side channel balances. | `min_delta` |
| `path` | Run path finding between two nodes and store results in `run.json`. | `start`, `destination`, `amount`, `path_finder`, `topk` |
| `route` | Generate one invoice and try to pay it. | `sender`, `destination`, `amount`, `max_fees`, `path_finder` |
| `inv` | Generate invoice traffic over a number of blocks. | `node_events_per_block`, `blocks`, `min_amt`, `max_amt`, `max_fees`, `path_finder` |

Supported `path_finder` values:

- `lnd`
- `mini_dijkstra`
- `shortest_hop`
- `bfs`

For the `path` command only, `path_finder: "all"` runs all strategies and records each result in `run.json`.

### Runner Outputs

Supported report outputs:

| Output | File |
| --- | --- |
| `network`, `stat`, `stats` | `reports/network.csv` |
| `invoice`, `invoices` | `reports/invoice.csv` |

If `outputs` is omitted, the runner writes the network report.

`path` results and single `route` metadata are recorded in each run's `run.json`. They are not separate report files in this phase.

### Runner Usage

Run from this folder, `tools/dse`:

```bash
./uv_dse_run ../../uv_configs/template.properties quickstart_dse.json \
  --output-dir dse_runs/quickstart \
  --force
```

Useful options:

| Option | Purpose |
| --- | --- |
| `--output-dir DIR` | Write generated configs, runs, indexes, and reports under `DIR`. |
| `--force` | Replace an existing output directory. |
| `--limit N` | Run only the first `N` generated configs. Useful for smoke tests. |
| `--java CMD` | Use a specific Java launcher. |
| `--javac CMD` | Use a specific Java compiler. |
| `--keep-build-dir` | Keep the temporary Java build directory and record it in `manifest.json`. |

### Runner Output Layout

```text
dse_runs/quickstart/
  configs/
    cfg_0001.properties
    manifest.json
  runs/
    cfg_0001/
      quick-invoice-hops/
        run.properties
        experiment.json
        run.json
        runner_stdout.txt
        runner_stderr.txt
        uv.log
        reports/
          network.csv
          invoice.csv
  manifest.json
  runs_index.csv
  runs_index.jsonl
```

The visualizer reads `runs_index.jsonl` first, then falls back to `runs_index.csv`.

### Config Generator Only

Use `uv_dse_gen` when you only want generated `.properties` files and do not want to run experiments:

```bash
./uv_dse_gen ../../uv_configs/template.properties uv_dse_example.json \
  --output-dir ../../uv_configs/dse_generated \
  --force
```

`uv_dse_gen` reads only the `parameters` section. It ignores `experiments`.

### Visualizer GUI

Open a completed DSE output:

```bash
./uv_dse_visualizer.py dse_runs/quickstart
```

If Tkinter is unavailable, the tool starts a browser GUI and prints a local URL. You can request browser mode explicitly:

```bash
./uv_dse_visualizer.py dse_runs/quickstart --web
```

The GUI controls:

| Control | Meaning |
| --- | --- |
| `Report` | Choose `network` or `invoice`. |
| `Metric` | Choose a metric from the selected report. |
| `X parameter` | Choose which parameter varies on the x-axis. |
| `Experiment` | Use one experiment or `All`. |
| `Graph` | Choose `bar`, `line`, `scatter`, or `pie`. |
| `Aggregation` | Combine multiple runs per x-value with `mean`, `median`, `sum`, `min`, `max`, or `count`. |
| `Fixed filters` | Pin background parameters with `key=value`, for example `seed=1`. |
| `Title` | Figure title used in preview and PDF. |
| `PDF font size` | Base font size for exported PDFs. |

### Visualizer Headless Mode

List available metric keys:

```bash
./uv_dse_visualizer.py --list-metrics --report network
./uv_dse_visualizer.py --list-metrics --report invoice
```

Export a PDF:

```bash
./uv_dse_visualizer.py dse_runs/quickstart \
  --no-gui \
  --report invoice \
  --metric invoice:success_rate \
  --x-param pathfinding_max_hops \
  --experiment quick_invoice_hops \
  --graph bar \
  --aggregation mean \
  --output-pdf dse_runs/quickstart/success_rate_by_hops.pdf
```

### Parameter Context

Every preview and PDF includes parameter context:

- `Varied on x-axis`: the parameter being compared visually.
- `Fixed by filter`: a parameter explicitly pinned by the user.
- `Fixed background`: a parameter that has only one value in the selected runs.
- `Aggregated background variation`: a parameter with multiple values that is being aggregated.

For publication figures, either filter background parameters explicitly or state clearly that they are aggregated.

## Representative DSE Configurations

### Bootstrap Network Size Sweep

Use this to compare topology metrics as network size changes.

```json
{
  "parameters": {
    "bootstrap_nodes": [50, 100, 200],
    "bootstrap_blocks": [100],
    "seed": [1, 7, 13]
  },
  "experiments": [
    {
      "name": "bootstrap_stats",
      "commands": ["boot"],
      "outputs": ["network"]
    }
  ]
}
```

Suggested visualizer settings:

```text
Report: network
Metric: Node Channels / Average
X parameter: bootstrap_nodes
Graph: line
Aggregation: mean
```

### Pathfinding Hop-Limit Sweep

Use this to compare pathfinding search work and success rate.

```json
{
  "parameters": {
    "bootstrap_nodes": [100],
    "bootstrap_blocks": [100],
    "pathfinding_max_hops": [3, 4, 5, 6],
    "seed": [1, 7, 13]
  },
  "experiments": [
    {
      "name": "invoice_hop_sweep",
      "commands": [
        "boot",
        "rndbal",
        {
          "command": "inv",
          "node_events_per_block": 0.05,
          "blocks": 100,
          "min_amt": 1000,
          "max_amt": 100000,
          "max_fees": 1000,
          "path_finder": "lnd"
        }
      ],
      "outputs": ["network", "invoice"]
    }
  ]
}
```

Suggested visualizer settings:

```text
Report: invoice
Metric: Average investigated states
X parameter: pathfinding_max_hops
Graph: bar
Aggregation: mean
```

### Liquidity Mode Comparison

Use separate experiments to compare fixed and randomized channel balances on the same topology parameter space.

```json
{
  "parameters": {
    "bootstrap_nodes": [100],
    "bootstrap_blocks": [100],
    "seed": [1, 7, 13]
  },
  "experiments": [
    {
      "name": "fixed_balance_invoice",
      "commands": [
        "boot",
        { "command": "bal", "level": 0.5 },
        {
          "command": "inv",
          "node_events_per_block": 0.05,
          "blocks": 100,
          "min_amt": 1000,
          "max_amt": 100000,
          "max_fees": 1000,
          "path_finder": "lnd"
        }
      ],
      "outputs": ["network", "invoice"]
    },
    {
      "name": "random_balance_invoice",
      "commands": [
        "boot",
        "rndbal",
        {
          "command": "inv",
          "node_events_per_block": 0.05,
          "blocks": 100,
          "min_amt": 1000,
          "max_amt": 100000,
          "max_fees": 1000,
          "path_finder": "lnd"
        }
      ],
      "outputs": ["network", "invoice"]
    }
  ]
}
```

Suggested visualizer settings:

```text
Report: invoice
Metric: Success rate (%)
X parameter: experiment
Graph: bar
Aggregation: mean
```

### Fee Profile Sweep

Use this to compare invoice outcomes under different hub fee policies.

```json
{
  "parameters": {
    "bootstrap_nodes": [100],
    "bootstrap_blocks": [100],
    "profile.hub.mean_ppm_fee": [300, 700, 1200],
    "seed": [1, 7, 13]
  },
  "experiments": [
    {
      "name": "invoice_fee_sweep",
      "commands": [
        "boot",
        "rndbal",
        {
          "command": "inv",
          "node_events_per_block": 0.05,
          "blocks": 100,
          "min_amt": 1000,
          "max_amt": 100000,
          "max_fees": 1000,
          "path_finder": "lnd"
        }
      ],
      "outputs": ["network", "invoice"]
    }
  ]
}
```

Suggested visualizer settings:

```text
Report: invoice
Metric: Filtered by max fees
X parameter: profile.hub.mean_ppm_fee
Graph: bar
Aggregation: sum
```

### Single-Route Probe Sweep

Use this for controlled route attempts between selected nodes.

```json
{
  "parameters": {
    "bootstrap_nodes": [100],
    "bootstrap_blocks": [100],
    "pathfinding_max_hops": [4, 6, 8],
    "seed": [1, 7, 13]
  },
  "experiments": [
    {
      "name": "single_route_probe",
      "commands": [
        "boot",
        { "command": "bal", "level": 0.5 },
        {
          "command": "route",
          "sender": "pk0",
          "destination": "pk99",
          "amount": 25000,
          "max_fees": 1500,
          "path_finder": "lnd"
        }
      ],
      "outputs": ["network", "invoice"]
    }
  ]
}
```

Suggested visualizer settings:

```text
Report: invoice
Metric: Success rate (%)
X parameter: pathfinding_max_hops
Graph: scatter
Aggregation: mean
```

## Practical Notes

- Keep DSE outputs under `dse_runs/` or another generated-output directory when running from `tools/dse`.
- Do not commit generated DSE run directories unless you intentionally want to version the results.
- Start with `--limit 1` before running a large parameter space.
- Exact replay can still be affected by threaded simulator behavior. Prefer aggregate metrics, report-level invariants, and repeated seeds.
- Always inspect parameter context before using a figure: it tells you what is varied, what is fixed, and what is aggregated.

## Regression Checks

Run the focused DSE wizard and validation tests from the repository root:

```bash
env PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/dse/tests -p 'test_*.py'
```
