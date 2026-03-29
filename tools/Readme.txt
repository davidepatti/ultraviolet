
---

# Find Unused Pubkeys Tool

This is a simple Bash script to scan a text file and list all public keys in the form pk<n> (where n ranges from 0 to N) that are **never mentioned** on any line containing a specified string.

## Usage

```
./find_unused_pubkeys.sh <string S> <N> <filename>
```

* <string S>: The string to search for in each line (e.g., "INVOICE\_PAID").
* <N>: The highest pubkey index (e.g., 15 to scan for pk0 to pk15).
* <filename>: The file to scan.

## Example

```
./find_unused_pubkeys.sh "INVOICE_PAID" 15 data.txt
```

This command will print all pk0 to pk15 that are **never mentioned on any line containing "INVOICE\_PAID"** in data.txt.

## Requirements

* Bash shell (Linux/Mac or WSL on Windows)
* No additional dependencies

## Notes

* The script removes duplicate matches automatically.
* Results are printed one per line.

---

## uv_dse_gen

`uv_dse_gen` generates a directory of layered `.properties` files for design-space exploration.

### Usage

```sh
./tools/uv_dse_gen uv_configs/template.properties tools/uv_dse_example.json
```

By default it creates a directory named `<F_stem>_<J_stem>` next to the base properties file. For the example above,
that means `uv_configs/template_uv_dse_example/`.

Each generated file:

* starts with `@include=<relative path to F>`
* overrides one unique combination from the JSON parameter space
* is listed in a generated `manifest.json`

### JSON schema

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

The tool computes the Cartesian product of all arrays and writes one `.properties` file per combination.
