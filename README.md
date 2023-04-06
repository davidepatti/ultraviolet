# UltraViolet

_**U**tilizing **L**ightning **T**opology and **R**outing **A**bstractions **VI**sible **O**n **LE**vel **T**imechain_

The goal of UltraViolet (UV) is to provide an open-source extensible high-level simulation platform for the Bitcoin Lightning Network, abstracting some of the major complexities of the underlying elements, while still providing a timechain-level accuracy.

Ultraviolet (UV) makes a massive usage of independent threads to make each simulated Lightning Node a living entity in its own "behavioral space".

### Main features of Ultraviolet:

*   _Timechain-level simulation_:  all the LN BOLT protocol events, Gossip/P2P messages, funding/closing on-chain transactions, are simulated according to a scaled-down timechain flow, allowing a fast evaluation of several LN scenarios, while still providing  a fine-grained block-level "vision" of the interactions with the base Bitcoin layer
*   _Large-scale simulation_: Possibility of instantiating thousands of running nodes, where each can be characterized according to some common node features (funding, channel sizes distribution, frequency of opening/closing, cltv deltas, fees etc..)
*   _Real Topology Testing_: support for pre-existing topologies imported from a json file, e.g. using the output of the "lncli describegraph" command executed on a real node.
*   _Pathfinding and Routing_: it is possible to test the routing of payment invoices, emulating the exchange of HTLC update/fulfill messages to experiment with new potential protocol evolutions, e.g., new routing strategies etc...

## Quick Start

from  terminal:  
_`java UltraViolet`_

This command will start the main menu

![](https://user-images.githubusercontent.com/3337669/230136438-e1419961-d2cd-48cd-9983-9d3fc169ce87.png)

For further details about usage and commands please refer to the wikipage (coming soon!)

## Ultraviolet Architecture

### Components exposed by UltraViolet

*   UVNode: an object (one per LN node) mapping the main functionalities of a running node, implemented as multiple thread instances (e.g., HTLC forwarding, Gossip messages, channels management)
*   Timechain: a global thread, representing the blockchain time flow
*   UVChannel: one instance for each channel, representing the main attributes of
*   UVNetworkManager: a global thread, interacting with other threads via synchronized methods when necessary, to implement the common services (start/stop p2p, timechain, load/restore status etc.)

