# Simulator Usage

UltraViolet starts as an interactive terminal program. Run it with an explicit config:

```bash
java -jar UltraViolet.jar uv_configs/template.properties
```

If no config is provided, the simulator uses `uv_configs/template.properties`.

The first screen is a command menu. A normal synthetic-topology workflow is:

```text
cfg -> boot -> t -> path/route/inv -> rep/stat -> wr -> save
```

An imported-topology workflow is:

```text
cfg -> import -> path -> save
```

Imported topologies do not automatically have live P2P convergence history, and imported channel policies can be incomplete depending on the source JSON and current import behavior.

A graph-learning export workflow is:

```text
cfg -> boot/import/load -> gexp -> tools/uv-graph-to-pyg/uv_graph_to_pyg
```

## Menu Commands

### `cfg`: Select Config

Selects a `.properties` file from `uv_configs/`. Switching config resets the current in-memory network if a network is already loaded.

Example:

```text
Available properties in uv_configs:
 1) * template.properties
 2)   exp_100.properties
 3)   exp_1000.properties
* current selection
Select config by number or filename [ENTER to keep current]:2
Selected exp_100.properties [uv_configs/exp_100.properties]
```

Interpretation: the selected config controls future bootstrap/import behavior. It does not rewrite existing saved `.dat` snapshots.

### `boot`: Bootstrap LN From Scratch

Creates synthetic nodes, starts the timechain, opens channels according to node profiles, waits for gossip queues, and marks bootstrap complete.

Example:

```text
Bootstrap started, check template.log for details...
waiting bootstrap to finish...
Bootstrapping [############........]  60.0% (Completed 60 of 100, running:8)
Done!
```

Interpretation: completion means the bootstrap latch ended and final queue-drain/pruning logic ran. Detailed event ordering is in the configured log file. Because bootstrap is threaded, exact topology and log order should not be used as deterministic golden output.

### `t`: Start/Stop Timechain And P2P Messages

Toggles the timechain and scheduled node service tasks. It requires a bootstrapped or loaded network.

Example:

```text
Starting Timechain, check template.log
Timechain set to start!
```

Interpretation: while running, block height advances and node services process channels, HTLC queues, mempool confirmations, and gossip flushes.

### `path`: Find Paths Between Nodes

Searches for routes between two nodes. You choose payment amount, top-k count, and path finder strategy.

Example:

```text
Starting node public key [pk0]:
Destination node public key [pk99]:
Payment amount [10000]:
Single[1] or All paths [all]:
Top K paths [20]:
Path finder [lnd|mini_dijkstra|shortest_hop|bfs|all] [lnd]:all
 -- bfs --------------------------------------
(pk0->pk5->pk99) COST: 2.000000 [hop_count=2.000000]
search stats: investigated=17, expanded_edges=122, excluded_capacity=4, excluded_visited=80, excluded_cycle=0, excluded_max_hops=0, excluded_cost=0, returned=1
```

Interpretation: returned paths are candidate graph paths, not guaranteed successful payments. Later checks can reject paths for missing policy, local liquidity, or max fees.

### `route`: Route Invoice

Creates one invoice at the destination and starts a routing attempt from the sender.

Example:

```text
Starting node public key [pk0]:
Destination node public key [pk99]:
Invoice amount [10000]:
Max fees [1000]:
Invoice message [default]:
Path finder [lnd|mini_dijkstra|shortest_hop|bfs] [lnd]:
Generated Invoice: protocol.LNInvoice{H='...', amt=10000, dst='pk99', msg=default}
Trying path 1 of 3 for invoice ...
Successfully routed invoice ...
```

Interpretation: this is useful for debugging one route. The command runs the invoice processing on a new thread, so follow the log and node reports for final outcome.

### `inv`: Generate Invoice Events

Injects many invoice events over a time interval.

Example:

```text
Press ENTER to accept defaults.
Invoice generation rate (events/node/block) [0.1]:
Timechain duration (blocks) [500]:
Min amount [1000]:
Max amount [1000000]:
Max fees [1000]:
Path finder [lnd|mini_dijkstra|shortest_hop|bfs] [lnd]:
Generating 5000 invoice events (min/max amt:1000,1000000, max_fees1000)
Completed events generation
Waiting for queues to flush...
```

Interpretation: `events/node/block` is multiplied by node count to derive expected global events per block. Outcomes are summarized by `rep`, `stat`, and `wr`.

### `bal`: Set Local Channel Balances

Sets initiator-side local balances toward a requested fraction.

Example:

```text
This will set the local balance of channels initiators
Enter a local level [0..1]:0.25
```

Interpretation: `0.25` attempts to leave initiators with roughly 25 percent local balance where possible. This changes liquidity, not graph connectivity.

### `rndbal`: Set Random Channel Balances

Randomizes channel balances using the network RNG.

Example:

```text
-> rndbal
```

Interpretation: use it to create liquidity imbalance before routing experiments. It is seeded by the network RNG but should not be treated as replay-stable after threaded operations have consumed random values.

### `all`: Show Nodes And Channels

Prints a detailed dashboard for every node and its channels.

Example:

```text
 Node pk0  Double meanings
  Capacity: 90,310,000 sats  Channels:  23  Profile: medium
  On-chain: 12,000,000 sats  Pending: 0 sats
  Local: 8,801,234 sats  Remote: 81,508,766 sats
  Channels
  Channel ID     Peer   Capacity   Outbound   Inbound   Self fee   Peer fee
  ch_pk0_pk1     pk1   3,200,000    100,000  3,068,000    100 320   100 295
```

Interpretation: `Outbound` is the amount this node can currently push after reserves and pending HTLCs. `Self fee` and `Peer fee` show base fee and ppm fee policy for each direction.

### `net`: Show All Nodes

Prints a compact network-wide node table.

Example:

```text
 Network Nodes
  Nodes: 100  Channels: 890  Capacity: 7,459,900,000 sats  Avg outbound: 46.0%
  Node  Alias             Capacity  Channels  On-chain  Local  Remote  Outbound  Profile
  pk0   Double meanings  90,310,000       23  12,000,000  8,801,234  81,508,766  9.7%  medium
```

Interpretation: use this for scanning capacity, channel count, and liquidity distribution across the whole simulation.

### `node`: Show Node

Shows one node in detail, including channels, graph summary, HTLC stats, invoice reports, and queues.

Example:

```text
insert node public key:pk0
 Node pk0  Double meanings
  Graph View
  Visible nodes: 99  Directed edges: 1390  Null policies: 0  Outbound share: 9.7%
  HTLC Stats
  Invoice processing: 19 ok  0 fail  Volume: 4,231,947 sats
```

Interpretation: this is the best command for diagnosing a single node after routing experiments.

### `graph`: Show Node Graph

Shows the local channel graph as seen by one node.

Example:

```text
Insert node public key:pk0
 Graph pk0  Double meanings
  Visible nodes: 99  Directed edges: 1390  Null policies: 0
  Source  Destination  Channel ID  Capacity  Policy
  pk0     pk1          ch_pk0_pk1  3,200,000  100 320
```

Interpretation: each node has its own graph, built through local channels and gossip. Null policies indicate graph entries that are not ready for policy-aware pathfinding.

### `gexp`: Export Node Graph JSON

Writes one selected node's current channel graph to a UV-specific JSON snapshot. This is the intermediate file used by
`tools/uv-graph-to-pyg/uv_graph_to_pyg` to create PyTorch Geometric sidecars and `graph.pt`.

Example:

```text
Node public key [pk0]:
Output JSON file [uv_graph_pk0.202606171530.json]:
Written uv_graph_pk0.202606171530.json
Nodes: 99, channels: 695, directed edges: 1390, missing policies: 0
Convert with: tools/uv-graph-to-pyg/uv_graph_to_pyg uv_graph_pk0.202606171530.json <output-dir>
```

Interpretation: the export is a UV graph snapshot, not a raw `lncli describegraph` clone. It preserves UV-visible
topology, policies, capacities, balances, observer identity, and block height. Fields UV does not model are left to the
converter as missing values or masks.

### `qs`: Show Queues Status

Prints non-empty queues for every node.

Example:

```text
Showing not empty queues...
All node's queues are empty
```

Interpretation: non-empty queues after a long wait can explain missing graph convergence, pending HTLCs, or incomplete save operations.

### `rep`: Show Invoice Reports

Prints the invoice CSV report to the terminal.

Example:

```csv
hash,sender,dest,amt,max_fees,path_finder,search_limit_paths,search_returned_paths,...
4b..1d,pk0,pk74,888862,1000,lnd,64,7,...
```

Interpretation: rows are sender-side invoice outcomes. See `invoice_report_format.md` for every field.

### `stat`: Show Network Stats

Prints min, max, average, standard deviation, and quartiles for network metrics.

Example:

```text
 Metric          Min        Max      Average   Std dev   Q1    Median   Q3
 Graph nodes      83        100        96.65      5.15   94       99   100
 Node channels     1         70        17.80     16.13    7       11    24
 Outbound %      0.0%     100.0%       46.0%     24.0% 25.0%   46.0% 60.0%
```

Interpretation: these are network-shape summaries. Use them to compare scenarios, not to inspect one route.

### `save`: Save UV Network Status

Serializes the current network to a `.dat` file.

Example:

```text
Save to:100.dat
Start saving status, please wait...
Checking queues before saving...
Saving complete!
```

Interpretation: saving stops the timechain and P2P services after queue checks. Saved files are Java serialization snapshots and can be incompatible across code changes.

### `load`: Load UV Network Status

Loads a previously saved `.dat` snapshot.

Example:

```text
Available .dat files:
  100.dat
Load from:100.dat
UVM LOADED
```

Interpretation: the timechain is left stopped after load. Start it with `t` before routing or invoice events.

### `wr`: Write Reports

Writes network, invoice, per-node, and full node/channel reports.

Example:

```text
Enter description prefix:test
Written test_network.202603281824.csv
Written test_invoice.202603281824.csv
Written test_nodes.202603281824.csv
Written test_all.202603281824.txt
```

Interpretation: filenames include a timestamp. The report contents are better for analysis than console output.

### `import`: Import Topology Graph

Imports a JSON graph, such as `lncli describegraph` output, and asks for the root node pubkey.

Example:

```text
A graph topology will be imported using the json output of 'lncli describegraph' command on some root node
Available .json files:
  graph2.json
Enter a JSON file: graph2.json
Enter root node pubkey:03740f...
Import completed
```

Interpretation: imported topologies are useful for pathfinding and structural inspection. Current import assigns default profile data and synthetic balances rather than reconstructing every real node property.

### `q`: Quit

Exits the simulator and shuts down network services.

Example:

```text
Exiting...
```

Interpretation: quit is the clean exit path for the interactive UI.
