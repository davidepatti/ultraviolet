# Shared simulation consistency

This branch promotes the reusable simulator changes from the research branch into ordinary UltraViolet execution. Terminal commands, DSE commands and Java/script callers use the same payment, lifecycle, persistence and export boundaries. It intentionally changes outcomes where previous execution lost work, reused invoice identities or left reservations behind. It does not promise byte-identical threaded runs.

The research changes were not all suitable for direct integration. In particular, terminal-only opening cleanup and a second payment/lifecycle API would have left normal execution with the original defects. They have been replaced, rather than merely renamed.

## Review of the research changes

| Original change | Integration decision |
| --- | --- |
| Resolved configuration and defaults (`UVConfig.getEffectiveSettings`) | Keep as a general read-only configuration API. |
| Pending channel inspection | Keep; strengthen capacity conservation and reservation checks. Store the original capacity instead of deriving it from potentially corrupted balances. |
| Joined timechain stop | Keep and use for normal stop, save and export. Start is idempotent; block waiters are notified after the block is committed. |
| Dedicated capture/pause/resume methods | Replace with shared `pauseAndDrain`, `resume` and `withSettledState`. Legacy network start/stop methods delegate to these boundaries. |
| Unique identities only for research invoices | Apply network-wide to ordinary invoice issuance too. Repeated descriptions are independent requests. Explicit identities remain available for replay schedules. |
| Dedicated invoice execution and batch accounting | Use ordinary `processInvoice` and managed `submitInvoice`. Standard `inv` and root traffic use `generateInvoiceBatch`, with an outcome for every submitted task. |
| Synchronized outcome/report access | Keep in the shared implementation; return report/outcome snapshots rather than exposing the paid/report containers. |
| Research-only timeout invalidation | Replace with shared per-contract resolution of protocol expiry and late fulfillment. Preserve conservative rejection for host stalls, interruptions and integrity errors. See [HTLC resolution](htlc-resolution.md). |
| Cleanup of unfunded opening proposals after stopping | Remove from core. A normal rejection message now releases exactly the matching proposal's reservation; stale responses cannot cancel newer proposals. Funding completion is queued to the recipient's service worker. |
| Synchronized registry from the research checkout's uncommitted fix | Keep in core. Iteration uses snapshots; use `registerNode` instead of mutating `getUVNodeList()`. |
| Research capture/traffic runners and exporters | Move to `tools/journal/java/`. Their one-sided opening, reserve convention, LND selection, schema and provenance constraints belong to the adapter, not general network validation. |
| Workspace-specific Python launchers and historical artifacts | Retain as optional research tooling. No capture, trajectory, training result or historical source checkout was regenerated or rewritten. |

## Ordinary execution

`UVNode.generateInvoice` allocates a unique identity independent of the workload RNG, including the older three-argument signature. Its old boolean no longer enables description-hash identity reuse. `processInvoice` returns that invocation's report; callers can ignore the return value after recompiling. Direct calls are tracked by the network. `UVNetwork.submitInvoice` additionally tracks queued work, and the terminal uses it instead of creating an unmanaged thread.

`pauseAndDrain` closes admission, waits for submitted/direct work and protocol state, joins clock/P2P workers, then rechecks for a handler that had already removed a message from its queue. `resume` reuses the same network and does not duplicate services. Bootstrap completion requires worker termination and a settled protocol boundary. Shutdown also terminates owned bootstrap and invoice executors.

`withSettledState` supplies a shared boundary for graph/report export, save, topology import and network balance changes. An export of a running network resumes it afterwards. Snapshot output is written to a temporary sibling before replacing the destination. A batch owns the lifecycle until it finishes: competing capture, pause or reload is rejected rather than interleaved with the batch. Applications should wait for the batch result before those operations. Low-level mutable graph/channel/queue objects remain implementation APIs; arbitrary concurrent mutation through them is not supported by this boundary.

Research-specific identifier formats and workload caps remain in the adapter; the core requires a nonblank batch label and guards integer count overflow. The standard batch sampler retains uniform sender/distinct-recipient selection, inclusive integer amounts and fractional-rate rounding. Equal amount bounds now mean exactly that amount. Injection, submission and settled block heights are distinct; a generator iteration is not a guarantee of one elapsed block. Queued tasks remain accounted for when injection fails. Ordinary `generateInvoiceEvents` and root traffic resume after a successful settled batch. The lower-level `generateInvoiceBatch` returns paused with a `uv-invoice-batch-v1` receipt.

DSE `route` uses the returned report, `inv` includes the batch receipt, and `boot` honors an explicit saved-state mode instead of silently bootstrapping from scratch. The terminal's stop, graph export and save wait for earlier manual requests.

## Persistence and changed behavior

New snapshots preserve invoice identity allocation, attempted invoice hashes and each node's routing strategy. Older clean snapshots default missing routing metadata to the former BFS behavior and derive channel capacity from saved balances. A separate compatibility test writes a snapshot using original commit `1430982`, loads it with this source, runs root traffic, then saves and reloads the upgraded state. This verifies that fixture and format migration; it does not certify every historical snapshot. Files containing unresolved reservations or inconsistent endpoints are rejected.

`UVConfig` already had an explicit serialization ID; it did not need a new one. The channel's nested serialized data class now pins its original ID so synchronization changes do not accidentally break old files.

Changes to identity allocation, opening rejection, completed-work accounting, equal amount bounds and timeout handling can change hashes, topology and numerical results. Those corrections must not be represented as reproducing historical experiment outputs. Newly built research adapters retain historical schema names for consumers but record the new source provenance. Their `opening-cleanup.json` receipt is empty because normal protocol handling resolves rejections.

## HTLC resolution and model boundary

The core now resolves HTLC expiry, fulfillment and failure through an authoritative per-channel contract ledger. Late replies cannot debit twice or act on a retry with the same invoice hash. A resolved protocol expiry is an ordinary failed attempt; a host watchdog or interruption remains an infrastructure failure. See [HTLC resolution](htlc-resolution.md) for identities, transitions, clocks, receipts and tests.

This deliberately retains UV's high-level shared-channel model. It does not add force-closing channels or on-chain transaction/confirmation behavior. Terminal outcome records consume memory for the network lifetime and are discarded on a settled restore. Low-level extension APIs and all parallel interleavings are not exhaustively verified.

## Validation

Run from the repository root with Java 24 or set `JAVA_RELEASE`:

```sh
tools/determinism-regression/determinism_regression.sh
tools/determinism-regression/compatibility_regression.sh
python3 -B -m unittest discover -s tools/journal -p 'test_*.py' -v
```

The main Java suite has 25 groups covering deterministic static behavior, research adapter contracts, opening rejection, retries, expiry, late fulfillment, watchdog rejection and concurrent transfers, queued failures, manual identities, live capture, concurrent batch ownership, DSE route/inv/load, terminal subprocess commands and a small bootstrap. The compatibility runner requires the original commit to be available locally and builds only core sources for its current reader. All simulation fixtures and builds are temporary.

Python tests include optional read-only checks of a saved acceptance fixture when `UV_OT_ACCEPTANCE` names its directory. The full run with that fixture has 31 tests. These tests do not generate study data. Passing these checks supports a future integration review; this branch has not been merged.
