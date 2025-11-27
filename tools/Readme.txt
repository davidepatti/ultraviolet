
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
