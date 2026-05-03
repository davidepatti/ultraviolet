# Design-Space Generator

`uv_dse_gen` generates a directory of layered `.properties` files for design-space exploration.

Each generated file starts with `@include=<relative path to base config>` and then overrides one unique combination from the JSON parameter space. The tool also writes a `manifest.json` file that records every generated config and its override values.

## Usage

The simplest workflow is to enter this tool directory first:

```bash
cd tools/design-space-generator
./uv_dse_gen ../../uv_configs/template.properties uv_dse_example.json
```

By default the output directory is named `<base_stem>_<json_stem>` next to the base properties file. From the command above, that is:

```text
uv_configs/template_uv_dse_example/
```

Use `--output-dir <dir>` to choose a destination and `--force` to replace an existing output directory.

## JSON Schema

The JSON file can either be a plain object of `property -> values[]`, or an object with a `parameters` field:

```json
{
  "parameters": {
    "bootstrap_nodes": [100, 500, 1000],
    "seed": [1, 7],
    "profile.hub.prob": [0.1, 0.15]
  }
}
```

The tool computes the Cartesian product of all arrays. Only strings, numbers, and booleans are supported as values.

## Examples

All examples below assume you are inside the tool folder:

```bash
cd tools/design-space-generator
```

### 1. Run The Included Example

```bash
./uv_dse_gen ../../uv_configs/template.properties uv_dse_example.json
```

This generates every combination of:

- `bootstrap_nodes`: `100`, `500`, `1000`
- `seed`: `1`, `7`
- `profile.hub.prob`: `0.1`, `0.15`

Total generated configs: `3 * 2 * 2 = 12`.

### 2. Write To A Named Output Directory

```bash
./uv_dse_gen ../../uv_configs/template.properties uv_dse_example.json \
  --output-dir ../../uv_configs/dse_quickstart
```

Use this when you want a stable directory name for scripts, notebooks, or batch runs.

### 3. Replace An Existing Output Directory

```bash
./uv_dse_gen ../../uv_configs/template.properties uv_dse_example.json \
  --output-dir ../../uv_configs/dse_quickstart \
  --force
```

`--force` removes and recreates the output directory. Use it only when generated configs can be discarded.

### 4. Sweep Network Size And Seeds

Create `network_size_sweep.json` in this folder:

```json
{
  "parameters": {
    "bootstrap_nodes": [100, 500, 1000, 2000],
    "bootstrap_blocks": [100, 500, 1000, 2000],
    "seed": [1, 7, 13]
  }
}
```

Generate configs:

```bash
./uv_dse_gen ../../uv_configs/template.properties network_size_sweep.json \
  --output-dir ../../uv_configs/dse_network_size
```

This is useful for studying scaling effects while keeping each size paired with a coherent bootstrap period.

### 5. Sweep Pathfinding Parameters

Create `pathfinding_sweep.json`:

```json
{
  "parameters": {
    "pathfinding_max_hops": [4, 6, 8],
    "pathfinding_lnd_default_path_probability": [0.4, 0.6, 0.8],
    "pathfinding_lnd_attempt_cost_ppm": [500.0, 1000.0, 2000.0],
    "seed": [1, 7]
  }
}
```

Generate configs:

```bash
./uv_dse_gen ../../uv_configs/template.properties pathfinding_sweep.json \
  --output-dir ../../uv_configs/dse_pathfinding
```

Use these configs when comparing path search complexity, returned candidate paths, and invoice success rates under different cost assumptions.

### 6. Sweep Liquidity And Fee Profiles

Create `profile_sweep.json`:

```json
{
  "parameters": {
    "profile.small.max_channels": [5, 10, 20],
    "profile.medium.max_channels": [50, 100],
    "profile.hub.max_channels": [250, 500],
    "profile.hub.mean_ppm_fee": [300, 700, 1200],
    "seed": [1, 7]
  }
}
```

Generate configs:

```bash
./uv_dse_gen ../../uv_configs/template.properties profile_sweep.json \
  --output-dir ../../uv_configs/dse_profiles
```

Use this for topology-density and fee-policy experiments. Keep an eye on total combinations: this example creates `3 * 2 * 2 * 3 * 2 = 72` configs.

### 7. Sweep Timechain And Gossip Timing

Create `timing_sweep.json`:

```json
{
  "parameters": {
    "blocktime_ms": [50, 100],
    "node_services_tick_ms": [5, 10],
    "gossip_flush_period_ms": [5, 10],
    "gossip_flush_size": [100, 500],
    "seed": [1]
  }
}
```

Generate configs:

```bash
./uv_dse_gen ../../uv_configs/template.properties timing_sweep.json \
  --output-dir ../../uv_configs/dse_timing
```

Timing sweeps affect thread scheduling pressure. Treat exact run replay as nondeterministic; compare aggregate statistics and invariants instead.

### 8. Run A Generated Config

From the repository root:

```bash
java -jar UltraViolet.jar uv_configs/dse_quickstart/cfg_0001__bootstrap-nodes-100__seed-1__profile-hub-prob-0-1.properties
```

From this tool directory:

```bash
cd ../..
java -jar UltraViolet.jar uv_configs/dse_quickstart/cfg_0001__bootstrap-nodes-100__seed-1__profile-hub-prob-0-1.properties
```

## Output Interpretation

Generated configs are intended to be passed directly to UltraViolet:

```bash
java -jar UltraViolet.jar uv_configs/template_uv_dse_example/cfg_0001__bootstrap-nodes-100__seed-1__profile-hub-prob-0-1.properties
```

The `manifest.json` file is useful for automation because it maps each generated filename to the parameter values that produced it.

## Practical Notes

- Generated files are ordinary UltraViolet config files. You can inspect or edit them manually.
- The include path inside each generated file is relative to the generated output directory, not to this tool directory.
- The generator does not run simulations. It only creates configs.
- Large parameter spaces grow quickly because every array is combined with every other array.
- Keep generated DSE directories under `uv_configs/` when you want them to appear naturally in the simulator's `cfg` menu.
