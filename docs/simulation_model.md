# Simulation Model

UltraViolet models a Lightning-like network at a high level. It focuses on topology, channel balances, gossip visibility, timechain confirmation, pathfinding, and HTLC routing behavior.

## Main Concepts

### Network Manager

`UVNetwork` owns the global node map, timechain, random generators, logging, bootstrap process, import/load/save operations, and experiment methods.

### Nodes

Each `UVNode` has:

- a synthetic or imported public key;
- a profile;
- on-chain balance and pending on-chain amount;
- local channels;
- peers;
- a local channel graph;
- P2P and HTLC queues;
- routing and forwarding statistics.

### Channels

`UVChannel` stores a two-party channel with:

- channel id;
- ordered endpoint ids;
- capacity;
- endpoint balances;
- endpoint policies;
- pending reservations;
- commit and HTLC counters.

Capacity is conserved across endpoint balances. Pending reservations reduce available liquidity.

### Timechain

`UVTimechain` advances block height on a dedicated thread when running. It has a mempool, block list, confirmation search, and latch-based block waiting used by bootstrap and event generation.

### Gossip

Gossip messages are queued at nodes and processed by scheduled node services. Gossip propagation is bounded by:

- `p2p_max_hops`;
- `p2p_max_age`;
- `gossip_flush_size`;
- `gossip_flush_period_ms`.

Each node has its own channel graph, so graph visibility can differ between nodes until gossip converges.

### Pathfinding

Pathfinding runs on one node's local channel graph. Available strategies are:

- `BFS`: simple hop-oriented search;
- `SHORTEST_HOP`: returns shortest-hop ties;
- `MINI_DIJKSTRA`: uniform-cost search with hop limit;
- `LND`: LND-style cost model with fees, timelock cost, and probabilistic penalty.

Search results are candidate paths. Payment attempts can still fail later due to local liquidity, policies, fees, timeouts, or HTLC forwarding failures.

## Synthetic Bootstrap

Bootstrap creates synthetic nodes, samples profiles and funding, starts node services, and lets nodes open channels over time.

Important properties:

- node start times are sampled over `bootstrap_blocks`;
- each node samples target channel openings from its profile;
- target peers are selected using profile `hubness`;
- funding transactions are confirmed through the timechain abstraction;
- channel announcements and updates propagate through P2P queues.

Because bootstrap uses executors, scheduled services, queues, and wall-clock sleeps, it is intentionally not byte-for-byte deterministic.

## Imported Topologies

Import reads a JSON graph, creates nodes with the default profile, constructs channels, and populates channel graphs.

Imported topologies are useful for:

- deterministic pathfinding tests;
- structural graph inspection;
- comparing algorithms on fixed topology input.

They should not be interpreted as complete reconstructions of every real Lightning property unless the import code explicitly maps that property.

## Invoice Routing

Invoice routing has three stages:

1. path search on the sender's local graph;
2. sender-side path filtering for policy, capacity, local liquidity, and max fees;
3. actual HTLC attempts through node queues.

The invoice report keeps counters from these stages separate.

## Determinism Boundary

Stable deterministic tests are feasible when:

- the input order is fixed;
- the RNG is explicitly seeded;
- comparisons sort maps/sets into canonical order;
- no timechain/P2P/invoice executor race is part of the expected output.

Threaded live simulations should be tested with invariants and smoke checks rather than golden byte-for-byte replay.
