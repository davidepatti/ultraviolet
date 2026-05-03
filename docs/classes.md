# Class And Package Map

This document describes the main Java classes and how they interact.

## Top-Level UI

### `UltraViolet`

Interactive terminal entry point. It owns the menu, reads user input, renders tables, and delegates simulator actions to `UVNetwork`.

Key interactions:

- creates `UVConfig`;
- creates and replaces `UVNetwork`;
- applies `PathFinder` strategies to nodes;
- calls report generators through `GlobalStats`;
- reads and writes user-requested files through `UVNetwork` operations.

## `misc`

### `UVConfig`

Loads `.properties` files, resolves `@include` and `@import`, stores top-level simulator parameters, and builds node profiles.

Key interactions:

- used by `UVNetwork` for thread limits, time settings, bootstrap settings, and pathfinding parameters;
- exposes `NodeProfile` objects used by `UVNode`;
- supplies profile and multivalue random selection helpers.

### `UVConfig.NodeProfile`

Stores profile attributes and generated distribution specs for channel sizes and ppm fees.

Key interactions:

- assigned to nodes during bootstrap or import;
- sampled by `UVNetwork.bootstrapNode(...)` and `UVNode.fundingLocked(...)`.

### `CryptoKit` and `Ripemd160`

Cryptographic helpers used for invoice hashes, transaction ids, and related digest operations.

## `network`

### `LNetwork`

Small interface for network-level operations used by nodes.

### `LNode`

Small interface for node-level behavior, including invoice generation.

### `LNChannel`

Channel interface and policy representation.

### `UVNetwork`

Central simulator manager. Owns:

- loaded config;
- node map;
- timechain;
- bootstrap and P2P executors;
- logging;
- global stats object;
- import, save, and load operations;
- event generation and liquidity adjustment helpers.

Key interactions:

- creates `UVNode` instances;
- starts `UVTimechain`;
- schedules node service execution;
- delivers messages between nodes;
- delegates metrics to `GlobalStats`;
- serializes network snapshots.

### `UVNode`

Represents one simulated Lightning node.

Owns:

- channels and peers;
- local channel graph;
- local RNG;
- P2P, channel-opening, and HTLC queues;
- generated, pending, and paid invoices;
- per-node stats.

Key interactions:

- opens channels through `MsgOpenChannel` and `MsgAcceptChannel`;
- updates `UVChannel` balances;
- sends and receives `P2PMessage` instances through `UVNetwork`;
- runs pathfinding with `PathFinder`;
- updates `GlobalStats.NodeStats`.

### `UVChannel`

Concrete channel implementation. Stores endpoint data, policies, balances, pending reservations, and commitment state.

Key interactions:

- referenced by both endpoint nodes;
- turned into graph edges in `ChannelGraph`;
- used by routing to reserve pending liquidity and push sats.

## `protocol`

### `LNInvoice`

Invoice data: payment hash, amount, destination, message, and final CLTV delta.

Key interactions:

- created by destination nodes;
- processed by sender nodes;
- recorded in invoice reports.

### `UVTimechain`

Block and mempool abstraction. Runs as a thread when active.

Key interactions:

- confirms funding transactions;
- wakes bootstrap/event logic via block latches;
- provides block height for CLTV and message age checks.

### `UVTransaction`

Abstract transaction object for funding, closes, HTLC-related transactions, penalties, and external mempool blobs.

Key interactions:

- added to `UVTimechain` mempool;
- used by nodes waiting for funding confirmation.

## `message`

### `P2PMessage`

Base type for all network messages.

### `GossipMsg`

Base type for gossip messages with sender, timestamp, and forwarding count.

### `GossipMsgChannelAnnouncement`

Announces a channel to peers and creates graph entries with initially missing policies.

### `GossipMsgChannelUpdate`

Carries directional channel policy updates.

### `MsgOpenChannel`

Channel-opening request from initiator to peer.

### `MsgAcceptChannel`

Acceptance response for a channel-opening request.

### `MsgUpdateAddHTLC`

HTLC add message carrying amount, payment hash, CLTV expiry, channel id, and onion payload.

### `MsgUpdateFulFillHTLC`

HTLC fulfillment message carrying the payment preimage.

### `MsgUpdateFailHTLC`

HTLC failure message carrying a failure reason.

### `OnionLayer`

Simplified nested onion payload representation used when routing HTLCs.

## `topology`

### `ChannelGraph`

Directed graph view of known channels and policies from one node's perspective.

Key interactions:

- updated by local channel creation and gossip messages;
- consumed by pathfinders;
- serialized with nodes.

### `Path`

Immutable path wrapper over graph edges.

### `PathFinder`

Abstract base class for path search strategies. Provides search result, path details, cost components, and search stats records.

### `BFS`

Simple breadth-first pathfinder.

### `ShortestHop`

Breadth-first pathfinder that keeps multiple shortest-hop parents.

### `MiniDijkstra`

Uniform-cost search with max-hop and simple-path checks.

### `LNDPathFinder`

Extends `MiniDijkstra` with a Lightning-style cost model based on fees, timelock opportunity cost, and probabilistic penalty.

### `PathFinderFactory`

Creates pathfinder instances from a strategy enum and optional config.

## `stats`

### `DistributionGenerator`

Pure sampling helper used for bootstrap timing, channel size distributions, and ppm fee distributions.

### `GlobalStats`

Computes network-wide reports and owns `NodeStats`.

### `GlobalStats.NodeStats`

Per-node counters for invoice processing and forwarding.

### `GlobalStats.NodeStats.InvoiceReport`

Immutable invoice report row. Separates search-stage counters, sender-side filtering counters, HTLC attempt counters, and final success.

## Interaction Summary

```text
UltraViolet UI
  -> UVConfig
  -> UVNetwork
       -> UVTimechain
       -> UVNode[]
            -> UVChannel[]
            -> ChannelGraph
            -> PathFinder
            -> P2PMessage queues
       -> GlobalStats
```

Messages connect nodes through `UVNetwork.deliverMessage(...)`. Graphs are local to nodes. Reports are generated from `UVNetwork`, `UVNode`, and `GlobalStats` state.
