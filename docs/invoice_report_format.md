# Invoice Report Format

The `wr` command writes the invoice report to a file named:

```text
<prefix>_invoice.<timestamp>.csv
```

Each row describes one processed invoice from the sender point of view.

## Important Interpretation Rule

The report contains metrics from three different stages:

1. `search_*`: counters produced by the path finder while exploring the graph.
2. `filtered_*`: returned paths rejected after path finding by extra sender-side checks.
3. `attempt_*`: failures observed while actually trying to route HTLCs on the remaining candidate paths.

This distinction is important because `search_*` counters are **not path counts**. They are search events or pruned states/edges, so they can be much larger than `search_returned_paths`.

## CSV Columns

| Field | Meaning |
| --- | --- |
| `hash` | Short invoice hash identifier stored in the report. |
| `sender` | Sender node public key. |
| `dest` | Destination node public key. |
| `amt` | Invoice amount in satoshis. |
| `max_fees` | Maximum fees allowed for this routing attempt, in satoshis. |
| `path_finder` | Path-finding strategy used for this invoice, for example `lnd`, `mini_dijkstra`, `shortest_hop`, or `bfs`. |
| `search_limit_paths` | Maximum number of paths that the sender asked the path finder to return. |
| `search_returned_paths` | Number of paths actually returned by the path finder before any extra sender-side filtering. |
| `search_investigated_states` | Number of search states popped and processed by the path finder. |
| `search_expanded_edges` | Number of graph edges expanded by the search. |
| `search_excluded_capacity` | Number of search expansions discarded because channel capacity was below the payment amount. |
| `search_excluded_visited_state` | Number of search expansions discarded by visited-state or best-cost pruning. |
| `search_excluded_cycle` | Number of search expansions discarded to avoid cycles. |
| `search_excluded_max_hops` | Number of search states discarded because the hop limit was exceeded. |
| `search_excluded_cost` | Number of search expansions discarded because the computed cost was invalid, for example infinite. |
| `filtered_policy` | Returned paths rejected after search because at least one edge had a missing policy. |
| `filtered_capacity` | Returned paths rejected after search because at least one edge did not have enough capacity. |
| `filtered_local_liquidity` | Returned paths rejected after search because the sender did not have enough outbound liquidity on the first hop. |
| `filtered_max_fees` | Returned paths rejected after search because total forwarding fees exceeded `max_fees`. |
| `candidate_paths` | Paths left after all `filtered_*` checks. These are the paths that can actually be attempted. |
| `attempted_paths` | Number of candidate paths that were actually tried. |
| `attempt_failed_temporary_channel` | Number of attempted paths that failed with `temporary_channel_failure`. |
| `attempt_failed_expiry_too_soon` | Number of attempted paths that failed with `expiry_too_soon`. |
| `attempt_failed_local_liquidity` | Number of attempted paths that failed later because the sender could not reserve the first hop anymore. |
| `attempt_failed_timeout` | Number of attempted paths that timed out while waiting for HTLC completion. |
| `attempt_failed_unknown` | Number of attempted paths that failed for a reason that was not classified explicitly. |
| `success` | `true` if the invoice was eventually routed successfully, otherwise `false`. |

## Consistency Rules

- `candidate_paths` is the number of paths that survived all `filtered_*` checks.
- `candidate_paths <= search_returned_paths`.
- `attempted_paths <= candidate_paths`.
- `search_excluded_*` values are search-stage counters, so they are not bounded by `search_returned_paths`.
- In the current implementation, each returned path is rejected by at most one `filtered_*` reason, because the checks are applied in sequence and stop at the first failing condition.

## Example

### Example Row

```csv
4b..1d,pk0,pk74,888862,1000,lnd,64,7,9049,36480,8234,15126,4072,7183,0,0,0,7,0,0,0,0,0,0,0,0,false
```

### How To Read It

- The sender was `pk0`, the destination was `pk74`, the amount was `888862` sat, and the routing strategy was `lnd`.
- The sender allowed the search to return at most `64` paths, and the path finder returned `7`.
- During search, the path finder pruned many search states:
  - `8234` capacity exclusions
  - `15126` visited-state exclusions
  - `4072` cycle exclusions
  - `7183` max-hop exclusions
- These values do **not** mean there were thousands of returned paths. They only mean the search frontier was large.
- After path finding, all `7` returned paths were filtered out by `filtered_local_liquidity=7`.
- Therefore `candidate_paths=0`, `attempted_paths=0`, and the invoice failed with `success=false`.

## Practical Reading Order

When analyzing a row, it is usually best to read it in this order:

1. Start with `path_finder`, `amt`, and `max_fees`.
2. Check `search_returned_paths` to see how many usable path objects came back from the search.
3. Look at `filtered_*` to understand why returned paths were discarded before routing.
4. If `candidate_paths > 0`, inspect `attempted_paths` and the `attempt_failed_*` fields to see what happened during HTLC routing.
5. Use the `search_*` fields to study algorithm behavior and search complexity, not to count final candidate paths.
