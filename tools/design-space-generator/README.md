# Design-Space Generator

`uv_dse_gen` generates a directory of layered `.properties` files for design-space exploration.

Each generated file starts with `@include=<relative path to base config>` and then overrides one unique combination from the JSON parameter space. The tool also writes a `manifest.json` file that records every generated config and its override values.

## Usage

```bash
tools/design-space-generator/uv_dse_gen uv_configs/template.properties tools/design-space-generator/uv_dse_example.json
```

By default the output directory is named `<base_stem>_<json_stem>` next to the base properties file. For the example above, that is:

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

## Output Interpretation

Generated configs are intended to be passed directly to UltraViolet:

```bash
java -jar UltraViolet.jar uv_configs/template_uv_dse_example/cfg_0001__bootstrap-nodes-100__seed-1__profile-hub-prob-0-1.properties
```

The `manifest.json` file is useful for automation because it maps each generated filename to the parameter values that produced it.
