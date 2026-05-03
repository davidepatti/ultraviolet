# Find Missing Pubkeys

`find_missing.sh` scans a text file for `pk<n>` identifiers and prints the ids that never appear on lines containing a target string.

This is useful when inspecting logs or reports and asking questions such as "which simulated nodes never appeared on an `INVOICE_PAID` line?"

## Usage

```bash
tools/find-missing/find_missing.sh <string> <max-node-index> <filename>
```

Example:

```bash
tools/find-missing/find_missing.sh "INVOICE_PAID" 15 test.log
```

This prints any ids from `pk0` through `pk15` that are not mentioned on lines containing `INVOICE_PAID`.

## Notes

- The search is line-oriented.
- The script only understands synthetic node ids in the `pk<n>` form.
- Output is one pubkey per line.
- It uses Bash and standard Unix tools only.
