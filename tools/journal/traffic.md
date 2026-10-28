# Journal O/T continuation and capture

The dedicated `uv_journal_traffic.py` launcher runs one fresh profile-generated opening and continues the **same in-memory network** through native invoice batches. It writes only inside the adjacent journal `b` workspace. It cannot load a saved state, randomize balances, run a stage automatically or train a model. The original DSE sampler and static A2 exporter/launcher remain unchanged.

## Lifecycle and accounting

`JournalTrafficRunner` first uses the existing validated terminal opening boundary and its narrowly reconciled unaccepted-opening cleanup. It checks genuine one-sided O, selects LND explicitly, enters a journal pause and exports O. It then resumes the clock and creates P2P workers only after the previous executor has terminated. Neither profiles, graph objects, balances, RNG state nor invoice identities reset.

`generateJournalInvoiceBatch` follows the native uniform sender/distinct-recipient and inclusive integer-uniform amount law, rate rounding and block alignment. It tracks every submitted task and waits for executor termination while protocol services remain live. The completion deadline and subsequent drain share one budget after injection. Actual request counts must match the declared nominal exposure. Submission, injection and settled heights/timestamps are recorded separately; iterations are not exact elapsed blocks.

A journal-only monotonic 64-bit preimage supplies unique invoice hashes. The legacy generator uses the 32-bit hash of the payment description, so repeated descriptions can reuse an invoice identity. The new identity consumes no traffic RNG and does not alter sampled sender, recipient or amount. Ordinary routing remains shared; its existing report is returned directly to the requesting task. Shared report writes and cross-thread outcome-map accesses are synchronized. Wide integers aggregate invoice amounts.

The barrier checks received-forwarding state, outgoing HTLCs, pending invoices, message queues, confirmations, opening state and channel reservations. It joins timechain/P2P workers, then checks again to detect a handler that had already dequeued work. Such transient work resumes ordinary processing within the deadline. Service exceptions, incomplete/exceptional task results and nonsettling state prevent a completed export. Payment state is never cleared to manufacture quiescence.

**Native sender timeouts are conservatively rejected.** UV's existing timeout releases a sender reservation without proving end-to-end cancellation of forwarded work; a late downstream settlement can therefore be unsafe. A journal timeout marks the trajectory invalid even if queues subsequently empty. The multihop fixture retains the unresolved forwarding state and rejects export. Implementing a new cancellation protocol is outside this minimal change. The legacy non-journal timeout behavior is unchanged.

This is a single-owner batch API, not a pause facility for concurrent UI, arbitrary external payments or topology mutations.

## Export and provenance

Each checkpoint contains `public.json`, separate `truth.json` and `private.json`, and a receipt written last. T checkpoints also contain `invoices.json`. The public schema contains only parent identity, public node IDs and R4-compatible channel capacities/fees with opaque channel IDs. Profiles, on-chain balances, private CLTV audit fields, liquidity, commitment numbers, invoice receipts and provenance are kept outside public inputs.

The separate dynamic exporter accepts arbitrary settled balances while checking nonnegativity, capacity conservation, the existing reserve convention, zero pending state, shared endpoint channel objects and consistent endpoint graph views. O also passes the unchanged A2 opening validator. A public fingerprint and structural fingerprint must stay fixed; profiles, on-chain funding, topology, directional fees and CLTV policies cannot drift. Repeated export of the same paused state must match.

The launcher archives the actual compiled sources/template/tools with hashes, base commit, dirty flag, Java versions and compiled-class hashes. Request, effective configuration and opening cleanup are hashed into every checkpoint's provenance. `trajectory.json` is the final completion marker; Python independently verifies the chain of receipts, source archive, identities, financial invariants, state continuity, all invoice amounts/outcomes and effective pool size. `validation.json` records success. Partial/failed outputs remain for diagnostics, are never overwritten and cannot be resumed as simulator state.

## Interface and execution boundaries

Requests use `journal-ot-request-v1` with `purpose`, `topology_id`, integer opening `overrides` and `traffic`. Traffic declares the native rate, amount bounds, fees, explicit `lnd`, drain timeout and ordered `{id, blocks, requested}` batches. Acceptance is limited to 2–20 nodes, short bootstrap/traffic, at most 200 requests and an explicit bounded worker setting. Equal amount bounds are rejected because the legacy sampler changes that case's upper bound.

Pilot execution additionally requires an exact request in `b/configs/ot-pilot-plan.json`, 500 nodes/100 bootstrap blocks and the planned 50/200/750 native iterations. **No pilot roster is created by this implementation.** The six-network pilot is not run.

Preview a tiny acceptance request from this checkout:

```sh
python3 -B tools/journal/uv_journal_traffic.py --request ../b/validation/ot-implementation-2026-10-28/acceptance-request.json --name preview-ot
```

Preview does not build, create an output directory or simulate. `--execute` explicitly starts that one request; `--java`, `--javac` and `--timeout` select existing tooling and the enclosing process deadline. No dependency installation occurs. Read-only verification of the final saved acceptance:

```sh
python3 -B tools/journal/uv_journal_traffic.py --validate ../b/validation/captures/native-ot-acceptance-2026-10-28-v2
```

Paths are derived from `b/validation/captures` or `b/incoming`; existing names, arbitrary destinations, traversal and redirected directories are rejected. F/U and all completed A1/A2 files remain separate.

## Validation

The full regression runner now covers thirteen groups, including handcrafted O/T routing, liquidity failure/retry, expiry, multihop timeout rejection, unchanged paused exports, safe repeated resume, native RNG sampling, queued task exceptions and wide-integer accounting. The original A2 groups remain in this suite. Tests assert invariants, not byte-identical threaded outcomes.

Python discovery covers fourteen original static-capture tests and thirteen O/T tests. Set `UV_OT_ACCEPTANCE` to the absolute final acceptance directory to enable the nine saved-capture corruption tests; these copy artifacts into temporary fixtures, never edit the source capture. The four request/path tests use self-contained temporary fixtures and run without the private journal workspace or simulation. Without `UV_OT_ACCEPTANCE`, the nine saved-capture tests are explicitly skipped; the remaining eighteen Python tests still run. The linked acceptance request, capture and implementation report belong to the external journal workspace and are not included in this code repository. See the [implementation and acceptance report](../../../b/validation/ot-implementation-2026-10-28/README.md) for measured evidence and preservation checks.
