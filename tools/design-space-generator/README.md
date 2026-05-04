# Design-Space Generator And Runner

This folder contains two related DSE tools:

| Tool | Purpose |
| --- | --- |
| `uv_dse_gen` | Generate one `.properties` file for each parameter combination. |
| `uv_dse_run` | Generate configs, compile UltraViolet, run experiments for each config, and collect reports. |

The intended workflow starts from this folder:

```bash
cd tools/design-space-generator
```

## Quick Start

Generate configs only:

```bash
./uv_dse_gen ../../uv_configs/template.properties uv_dse_example.json
```

Run the full DSE automation:

```bash
./uv_dse_run ../../uv_configs/template.properties uv_dse_example.json \
  --output-dir ../../dse_runs/quickstart \
  --force
```

Use `--limit 1` during development to run only the first generated config.

```bash
./uv_dse_run ../../uv_configs/template.properties uv_dse_example.json \
  --output-dir ../../dse_runs/smoke \
  --limit 1 \
  --force
```

## DSE JSON

The JSON file has two sections:

- `parameters`: property values to sweep over the base `.properties` template.
- `experiments`: command sequences to run for every generated config.

```json
{
  "parameters": {
    "bootstrap_nodes": [100, 500],
    "bootstrap_blocks": [100],
    "seed": [1, 7]
  },
  "experiments": [
    {
      "name": "bootstrap_stats",
      "commands": ["boot"],
      "outputs": ["network"]
    },
    {
      "name": "random_balance_path",
      "commands": [
        "boot",
        "rndbal",
        {
          "command": "path",
          "start": "pk0",
          "destination": "pk99",
          "amount": 10000,
          "path_finder": "all",
          "topk": 5
        }
      ],
      "outputs": ["network"]
    }
  ]
}
```

`uv_dse_gen` reads only `parameters`, so existing generator-only JSON files still work. `uv_dse_run` requires both `parameters` and `experiments`.

## Experiment Commands

Commands run in the order listed. A command can be a string when no parameters are needed, or an object when options are needed.

| Command | Effect | Common options |
| --- | --- | --- |
| `boot` | Bootstrap the network from the generated config. | none |
| `bal` | Set initiator-side channel balances to a fixed fraction. | `level`, `min_delta` |
| `rndbal` | Randomize initiator-side channel balances. | `min_delta` |
| `path` | Run path finding between two nodes and store results in `run.json`. | `start`, `destination`, `amount`, `path_finder`, `topk` |
| `route` | Generate one invoice and try to pay it. | `sender`, `destination`, `amount`, `max_fees`, `path_finder` |
| `inv` | Generate invoice traffic over a number of blocks. | `node_events_per_block`, `blocks`, `min_amt`, `max_amt`, `max_fees`, `path_finder` |

Supported `path_finder` values are `lnd`, `mini_dijkstra`, `shortest_hop`, and `bfs`. For `path`, `path_finder: "all"` runs all strategies and records each result.

Supported report outputs are:

| Output | File |
| --- | --- |
| `network`, `stat`, or `stats` | `reports/network.csv` |
| `invoice` or `invoices` | `reports/invoice.csv` |

If `outputs` is omitted, `uv_dse_run` writes the network report.

## Output Layout

`uv_dse_run` writes stable paths for post-processing:

```text
dse_runs/quickstart/
  configs/
    cfg_0001__seed-1.properties
    manifest.json
  runs/
    cfg_0001/
      bootstrap_stats/
        run.properties
        experiment.json
        run.json
        runner_stdout.txt
        runner_stderr.txt
        uv.log
        reports/
          network.csv
      invoice_campaign/
        run.properties
        experiment.json
        run.json
        reports/
          network.csv
          invoice.csv
  manifest.json
  runs_index.csv
  runs_index.jsonl
```

The top-level `manifest.json` summarizes the DSE execution. `runs_index.csv` is convenient for spreadsheets. `runs_index.jsonl` preserves structured fields such as parameter overrides and is usually the best input for scripts and notebooks.

`run.json` records command-level metadata, durations, path-search results, route success, and relative report paths. The report files are produced through the same report-export code used by the simulator `wr` command, so DSE does not maintain a separate report implementation.

## Examples

All examples assume the current directory is `tools/design-space-generator`.

### Bootstrap Stats Only

```json
{
  "parameters": {
    "bootstrap_nodes": [100, 500, 1000],
    "bootstrap_blocks": [100, 500],
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

Run it:

```bash
./uv_dse_run ../../uv_configs/template.properties bootstrap_stats.json \
  --output-dir ../../dse_runs/bootstrap_stats \
  --force
```

This is the cheapest automated exploration: it checks how topology and aggregate network metrics change across the parameter space.

### Fixed Balance Path Search

```json
{
  "parameters": {
    "bootstrap_nodes": [100],
    "seed": [1, 7, 13],
    "pathfinding_max_hops": [4, 6, 8]
  },
  "experiments": [
    {
      "name": "fixed_balance_path",
      "commands": [
        "boot",
        { "command": "bal", "level": 0.5 },
        {
          "command": "path",
          "start": "pk0",
          "destination": "pk99",
          "amount": 10000,
          "path_finder": "all",
          "topk": 10
        }
      ],
      "outputs": ["network"]
    }
  ]
}
```

`path` writes search statistics and candidate path details into each run's `run.json`. It does not create a separate path report in this phase.

### Random Liquidity And Invoice Traffic

```json
{
  "parameters": {
    "bootstrap_nodes": [100],
    "seed": [1, 7],
    "profile.hub.mean_ppm_fee": [300, 700, 1200]
  },
  "experiments": [
    {
      "name": "invoice_campaign",
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

This is the main workflow for invoice success/failure analysis because it produces both the network report and the invoice report.

### Single Route Probe

```json
{
  "parameters": {
    "bootstrap_nodes": [100],
    "seed": [1, 7]
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

`route` creates one deterministic invoice and attempts payment. The route result is stored in `run.json`, and the invoice attempt contributes to `reports/invoice.csv`.

## Generator-Only Usage

`uv_dse_gen` still creates only `.properties` variations:

```bash
./uv_dse_gen ../../uv_configs/template.properties uv_dse_example.json \
  --output-dir ../../uv_configs/dse_quickstart
```

By default the output directory is named `<base_stem>_<json_stem>` next to the base properties file. Use `--output-dir <dir>` to choose a destination and `--force` to replace an existing output directory.

Generated files start with `@include=<relative path to base config>` and then override one unique combination from the parameter space. The generated `manifest.json` maps each filename to the parameter values that produced it.

## Practical Notes

- Large parameter spaces grow as the Cartesian product of every parameter array.
- Keep generated config-only directories under `uv_configs/` when you want them to appear in the simulator `cfg` menu.
- Keep full DSE run outputs under `dse_runs/` or another analysis directory; they contain logs and reports, not only configs.
- Use small `bootstrap_nodes`, low `bootstrap_blocks`, and `--limit 1` for smoke tests before launching a large exploration.
- Exact replay can still be affected by threaded simulator behavior. Prefer comparing stable aggregate reports, structured command metadata, and regression invariants.
