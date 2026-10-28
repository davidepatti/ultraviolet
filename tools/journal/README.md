# Journal static A2 capture

Fresh-bootstrap capture for the separate Lightning GNN journal workspace. This is a dedicated Java/Python batch adapter; legacy DSE and graph export formats are unchanged. It does not load states, import F/U allocations, route payments, or train models.

## Boundary and output

`uv_journal_capture.py` compiles a frozen copy of the actual Java sources with existing Java 24 tooling, starts one fresh network in its own output directory, and runs `JournalCaptureRunner`. The runner waits for bootstrap workers to terminate, joins the timechain, terminates/awaits P2P services, then verifies empty node queues and zero pending amounts. This terminal barrier is for this single-owner batch process; it is not a general concurrent UI pause or a B traffic-drain implementation.

At larger scale, legacy mutual channel-opening rejection can leave unaccepted proposals reserving on-chain funds after active queues drain. The journal terminal boundary now cancels only these unfunded proposals, requiring their amounts to reconcile exactly with the node's pending reservation. It changes neither funded channels nor on-chain balances and writes `opening-cleanup.json` with a hash in the capture provenance/receipt. Active, funded or unreconciled state still fails. The initial failed pilot attempt is retained separately.

The exporter checks both endpoints share each channel object, both endpoint graph views agree with capacities and directional policies, reserves equal integer capacity/100, and balances are untouched one-sided openings. Missing fees, duplicate IDs, pending amounts and inconsistent state fail. Public channel IDs are SHA-256-derived opaque identifiers; the original funder-bearing channel names are not exported. A future importer can compute the same IDs from UV's channel IDs.

The process writes public graph and separate opening truth, resolved effective settings (including defaults and profile properties), request, receipt, source manifest/archive, build/runtime versions, compiled-class hashes and logs. The actual source tree hash accompanies the base commit and dirty flag; an uncommitted patch is never described as part of the base commit. An unchanged stopped state is exported twice and compared before writing. A Python verifier checks file/configuration/source hashes, source archive contents, canonical public fingerprint and opening-state invariants. `capture.json` is the Java completion marker; `validation.json` marks a successfully verified, nonduplicate capture. Failed partial directories are retained and cannot be resumed or overwritten.

Output paths are derived from the adjacent journal `a2` workspace. Acceptance captures go under `a2/validation/captures/`; future study captures go under `a2/incoming/`. Arbitrary destinations, traversal, symlink roots and existing names are rejected. Simulator logs are relative to each new runtime directory. No destination option can point to B2C. All source compilation is temporary; nothing writes to the original UV checkout.

## Interface

A request has exactly `schema`, `purpose`, `topology_id` and `overrides`. Schema is `journal-a2-capture-request-v1`. Acceptance requests use `acceptance-` IDs, at most 20 nodes, a seed disjoint from the study and small explicit overrides. Study requests must match one planned pilot/main identity and seed, 500 nodes and 100 blocks; the main protocol must be frozen. There is no implicit stage loop or dataset materialization. A later pilot run must collect all planned parents and construct the harness's full-stage capture index; individual receipts supply its file/hash entries.

From this checkout, preview only:

```sh
python3 -B tools/journal/uv_journal_capture.py --request ../a2/validation/static-capture-request.json --name preview-static-a2
```

Only an explicit `--execute` starts a bootstrap. `--java`/`--javac` select existing binaries; no dependency is installed. A bounded process timeout defaults to 180 seconds. Read-only validation of the saved acceptance capture:

```sh
python3 -B tools/journal/uv_journal_capture.py --validate ../a2/validation/captures/static-opening-2026-10-27
```

Do not reuse the acceptance request as a pilot request. Neither saved-state loading nor exact F/U import has been implemented. Those remain later simulator-replay/B work.

## Checks

Run the repository's full `tools/determinism-regression/determinism_regression.sh` with Java 24, and `python3 -B -m unittest discover -s tools/journal -v`. The Java suite includes journal fixture checks; Python tests cover tampering, duplicate captures, safe paths, preview-only behavior and refusal of load specifications. Live acceptance is a separate small capture, not part of the deterministic fixtures.
