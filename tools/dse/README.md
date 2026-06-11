# DSE Tools

This folder contains the UltraViolet design-space exploration workflow:

| Tool | Purpose |
| --- | --- |
| `uv_dse_gen` | Generate `.properties` files from a JSON parameter space. |
| `uv_dse_run` | Generate configs, run the selected UltraViolet experiment, and collect structured outputs. |
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
| `Create DSE JSON` | A form that loads a properties file, lists parameters and current values, lets you select DSE value ranges, edit the experiment, and load/save DSE JSON files. |
| `Run DSE` | A form for base properties, DSE JSON path, output directory, safe replacement controls, and optional `--limit`, then runs `uv_dse_run` as a background job with live output. |
| `Open DSE Visualizer` | The integrated report visualizer for selecting experiment, report, metric, graph type, filters, and PDF export. |

The JSON creator starts from `../../uv_configs/template.properties` and the bundled `quickstart_dse.json`, so the first screen already contains an editable parameter space and experiment. On this startup example, single-value DSE parameters are shown with the exact value from `template.properties`, and multi-value parameters include the template value. The default save destination is separate from `quickstart_dse.json` to avoid overwriting the bundled starting point.

DSE outputs are expected under `dse_runs/` inside this folder. Browse buttons open native OS file selectors: Finder dialogs on macOS, and `zenity` or `kdialog` on Ubuntu/Linux. If Ubuntu does not open a dialog, install `zenity`:

```bash
sudo apt install zenity
```

For safety, the wizard binds to `127.0.0.1` by default. Binding to a non-loopback host requires `--allow-remote`, and should be avoided unless the machine is otherwise protected. The GUI uses a per-session token for local action endpoints.

The Run DSE screen does not replace existing output directories unless replacement is explicitly enabled and confirmed. Long runs continue in the background; the page polls status and streams runner output.

In the JSON creator, `Current DSE JSON` is the single active JSON file. `Load` opens a native selector and then loads the chosen JSON immediately. `Save` opens a native save dialog starting from the current JSON path and then saves the current editor contents to the chosen path. The experiment section is graphical: edit the single experiment name, choose its command recipe, choose whether the network source is a fresh bootstrap or a `.dat` snapshot, choose its balance setup and report outputs, and edit recipe-specific command parameters. Major sections, parameter categories, and command blocks start collapsed; the single experiment command configuration starts expanded when you open the experiment section. Use `Refresh Preview` to update the resulting JSON preview and configuration count after changing parameter or experiment selections.

Changing the experiment recipe changes which command is emitted into the DSE JSON. The wizard keeps values entered in hidden recipe sections so switching back restores them, and asks for confirmation before a recipe change hides the previously active command from the saved JSON.

The parameter editor treats each field as a comma-separated list of DSE alternatives:

| DSE values field | Meaning |
| --- | --- |
| `1, 7, 13` | Three generated alternatives. |
| `"0,100,1000"` | One generated alternative whose property value contains commas. |

The quotes are only DSE input syntax. The generated `.properties` file still receives the unquoted simulator value, for example `base_fee_set=0,100,1000`.

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
  "experiment": {
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
- `experiment`: the single experiment definition to run for every parameter combination.

Basic shape:

```json
{
  "parameters": {
    "bootstrap_nodes": [100],
    "seed": [1, 7]
  },
  "experiment": {
    "name": "bootstrap_stats",
    "commands": ["boot"],
    "outputs": ["network"]
  }
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

### Parameter-To-Command Dependencies

The DSE runner currently uses a full-factorial execution model. Every value array in `parameters` is crossed with every other value array, and the complete experiment command sequence runs once for each generated configuration. The runner does not currently optimize by reusing earlier command results when only a later command depends on a changed parameter.

Use this table to decide which parameters make sense to vary for a given experiment. The "earliest affected command" column describes the semantic dependency in a fresh-bootstrap run. The "current behavior" column describes what `uv_dse_run` does today.

| Parameter group | Examples | Earliest affected command | Later commands affected | Current behavior and notes |
| --- | --- | --- | --- | --- |
| Run/log settings | `debug`, `logfile` | Network construction before `boot` | Usually none scientifically | Full experiment reruns for each value. These are usually not useful DSE dimensions unless you are debugging or measuring logging impact. |
| Random seed | `seed` | `boot` for fresh topology; `rndbal`; `inv` event generation | All later commands that consume topology, liquidity, or invoice events | Full rerun is conservative and appropriate for fresh bootstrap. Seed does not guarantee byte-for-byte deterministic replay because bootstrap and invoice processing use threads. |
| Thread/concurrency limit | `max_threads` | Network construction; `boot`; `inv` executor sizing | Timing-sensitive behavior in live threaded commands | Full rerun is conservative. Treat as a performance/concurrency dimension, not as a clean model parameter. |
| Fresh bootstrap size and timing | `bootstrap_nodes`, `bootstrap_blocks`, `bootstrap_time_median`, `bootstrap_time_mean` | `boot` from scratch | `bal`, `rndbal`, `path`, `route`, `inv`, network and invoice reports | Correct for fresh bootstrap. Changing these values requires a new topology. |
| Node profile selection | `profile.*.prob`, `profile.*.hubness` | `boot` from scratch | All topology, balance, path, route, invoice, and report behavior | Correct for fresh bootstrap. These control profile assignment and peer-selection tendencies during topology creation. |
| Node funding and channel structure | `profile.*.min_funding`, `profile.*.max_funding`, `profile.*.min_channels`, `profile.*.max_channels`, `profile.*.min_channel_size`, `profile.*.max_channel_size`, `profile.*.median_channel_size`, `profile.*.mean_channel_size` | `boot` from scratch | Balance, path, route, invoice, and reports | Correct for fresh bootstrap. These reshape the generated network, so later command results cannot be reused safely across values. |
| Channel fee policy at creation | `base_fee_set`, `profile.*.min_ppm_fee`, `profile.*.max_ppm_fee`, `profile.*.median_ppm_fee`, `profile.*.mean_ppm_fee` | `boot` from scratch | `path`, `route`, `inv`, invoice reports | Correct for fresh bootstrap. `base_fee_set` is itself a simulator multivalue property: quote it in DSE when it should be one value, for example `"0,100,1000"`. |
| Channel opening protocol | `to_self_delay`, `minimum_depth` | `boot` from scratch | Later topology visibility and payment behavior indirectly | Full rerun is conservative. These affect abstract channel-opening and confirmation behavior. |
| Gossip propagation | `p2p_max_hops`, `p2p_max_age`, `gossip_flush_size` | `boot` from scratch and live P2P processing | `path`, `route`, `inv`, reports through each node's graph visibility | Full rerun is appropriate when studying graph visibility or payment outcomes. |
| Simulation timing | `blocktime_ms`, `node_services_tick_ms`, `gossip_flush_period_ms` | `boot` and live command execution | `route`, `inv`, reports | Full rerun is conservative. Change these together; changing only one can change how often services run per block. |
| Path search bounds | `pathfinding_max_hops` | `path`, `route`, `inv` when using `lnd` or `mini_dijkstra` | Path metadata, invoice search statistics, payment outcomes | Current runner reruns the full experiment, including `boot`. This is safe but can be wasteful when topology is otherwise fixed. |
| LND path cost model | `pathfinding_lnd_risk_factor`, `pathfinding_lnd_base_attempt_cost_msat`, `pathfinding_lnd_attempt_cost_ppm`, `pathfinding_lnd_default_path_probability` | `path`, `route`, `inv` when `path_finder` is `lnd`; `path` when `path_finder` is `all` | Path costs, selected paths, invoice outcomes, invoice reports | Current runner reruns the full experiment. If the selected pathfinder is `bfs` or `shortest_hop`, these parameters usually produce redundant runs. |
| Default pathfinding payment amount | `pathfinding_lnd_default_payment_amount_sat` | `path` or `route` only when the command omits `amount` | Path costs and outcomes | Often irrelevant in wizard-generated experiments because `path`, `route`, and `inv` command parameters usually set explicit amounts. |
| Balance command options | `bal.level`, `bal.min_delta`, `rndbal.min_delta` | `bal` or `rndbal` | `path`, `route`, `inv`, invoice reports | These are experiment command fields, not `.properties` DSE parameters. To sweep them today, create separate DSE JSON files or edit the experiment between runs. |
| Invoice and route workload options | `inv.blocks`, `inv.node_events_per_block`, `inv.min_amt`, `inv.max_amt`, `inv.max_fees`, `inv.path_finder`, `route.amount`, `route.max_fees`, `route.path_finder`, `path.amount`, `path.topk`, `path.path_finder` | `path`, `route`, or `inv` | Command metadata and reports for that command | These are experiment command fields, not `.properties` DSE parameters. They are not crossed by the current `parameters` object. |
| Report selection | `experiment.outputs` | Report writing after all commands | None | Report selection is not a DSE parameter. In principle, changing only outputs should not require rerunning the simulator, but the current runner writes reports as part of each run. |

Important implications:

- Add DSE values only for parameters that can affect the selected command sequence. Otherwise the runner will still create extra configurations and rerun the experiment, but the results may be duplicates.
- For fresh-bootstrap experiments, topology parameters should be varied at the DSE parameter level because `boot` must be rerun.
- For pathfinding-only sensitivity on a fixed topology, the current tool is conservative but inefficient: it reruns `boot` unless you split workflows manually.
- When `boot` loads a `.dat` snapshot, UltraViolet restores the config serialized inside that snapshot. Generated DSE `.properties` overrides such as `bootstrap_nodes`, `seed`, and `pathfinding_*` do not reliably reshape or retune the loaded network after the snapshot is applied. Keep snapshot-mode parameter spaces single-valued unless you intentionally need separate output folders.
- The visualizer can hide or aggregate background variation, but it does not change what was executed. Always check the parameter context before interpreting a figure.

### Runner Commands

Experiment commands execute in order. A command can be a plain string when no options are needed, or an object when options are needed.

| Command | Effect | Common options |
| --- | --- | --- |
| `boot` | Bootstrap the network from the generated config, or load a saved `.dat` snapshot. | `mode`, `file` |
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

`boot` can stay as the plain string `"boot"` for a fresh bootstrap. To start from a saved simulator snapshot, use an object:

```json
{
  "command": "boot",
  "mode": "load",
  "file": "snapshots/base-network.dat"
}
```

Relative snapshot paths are resolved from the DSE JSON file directory before each run starts.

When `boot` loads a `.dat` snapshot, UltraViolet restores the config stored in that snapshot. Fresh-bootstrap parameters such as `bootstrap_nodes` and `bootstrap_blocks` therefore do not reshape that loaded network during the run; use snapshot loading for a fixed-topology run or prepare separate DSE JSON files when you need to compare different snapshots.

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

Use `uv_dse_gen` when you only want generated `.properties` files and do not want to run the experiment:

```bash
./uv_dse_gen ../../uv_configs/template.properties uv_dse_example.json \
  --output-dir ../../uv_configs/dse_generated \
  --force
```

`uv_dse_gen` reads only the `parameters` section. It ignores `experiment`.

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
  "experiment": {
    "name": "bootstrap_stats",
    "commands": ["boot"],
    "outputs": ["network"]
  }
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
  "experiment": {
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

### Fixed Liquidity Invoice Campaign

Use this to evaluate invoice outcomes after setting a fixed initiator-side channel balance level. To compare this against randomized balances, create a second DSE JSON with the same parameter space and replace the `bal` command with `rndbal`.

```json
{
  "parameters": {
    "bootstrap_nodes": [100],
    "bootstrap_blocks": [100],
    "seed": [1, 7, 13]
  },
  "experiment": {
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
  }
}
```

Suggested visualizer settings:

```text
Report: invoice
Metric: Success rate (%)
X parameter: seed
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
  "experiment": {
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
  "experiment": {
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

### Fixed Snapshot Route Probe

Use this when you want to run a command sequence on the same saved `.dat` network instead of regenerating a topology. Keep the parameter space single-valued unless you intentionally need multiple generated config folders; the loaded snapshot restores its own saved simulator config.

```json
{
  "parameters": {
    "seed": [1]
  },
  "experiment": {
    "name": "snapshot_route_probe",
    "commands": [
      { "command": "boot", "mode": "load", "file": "snapshots/base-network.dat" },
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
}
```

Suggested visualizer settings:

```text
Report: invoice
Metric: Success rate (%)
X parameter: seed
Graph: bar
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
