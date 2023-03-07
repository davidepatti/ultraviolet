# UltraViolet
## Underlying Lightning Topology and Routing Abstractions VIsible On LEvel Timechain

The goal is to provide a high-level simulation platform for the Bitcoin Lightning Network, abstracting some complexity of the underlying elements while still providing a timechain-level accuracy.

Ultraviolet (UV) makes a massive usage of threads to make each simulated Lightning Node as living entity in its own "behavioural space".

The major components of the UV architecture can be summarized as follows:

- UVNode: an object (one per LN node)  mapping the main functionalities of a running node, implemented as multiple thread instances (e.g., HTLC forwading, Gossip messages, channels management)
- Timechain: a global thread, representing the blockchain timeflow
- UVChannel: one instance for each channel, representing the main attributes of
- UVNetworkManager/Dashboard: a global thread, interacting with other thread via syncronized methods when necessary, to implement the common services (start/stop p2p, timechain, load/restore status etc.) 

Main features of Ultraviolet:

- *Timechain-level simulation*: where UVNodes communicate with each other by means of Gossip/P2P messags, as specificied in the BOLT protocol
- *Large scale simulation*: Possibility of instantiating thousands of running nodes, where each can be characterized according the tipical node features (funding, channel sizes distribution, frequency of opening/closing, cltv deltas, fees etc..)
- *Real Topology Testing*: it is possible to import a pre-existing topology from the json output of the "lncli describegraph" command exected in a real node.
- *Pathfinding and Routing*: it is possible to test the routing of payment invoices, emulating the exchange of HTLC udpate/fulfill messages



![what is](uv.png)

# Quick Start

from some terminal:
*java UVDashboard*



# Components

Each component is represented with a Java class that abstract some functional aspects of the related concepts usually
found in a Lightning Network environment.

## UVNode
UVNode models both the software element, e.g., lnd implementation running on some hardware, but also the operational/managements aspects.
This includes:

- UVNode behaviour: opening/closing the channels, choosing peers etc, can be probabilistically characterized when initializing the UVNode instance. 
- Identity: abstracted to coincide with pubkey,.e.g., no need of having IP/tor addresses, since all network
  communication is done by method calls between synchronized threads that implement the exchange of BOLT protocol messages. Notice that pubkey should be derived from a root
  private key, not required, since trust is not an issue in UV,i.e., Nothing is “signed”.
- Inside each UVNode other nodes as seen throught the "lens" of LNode or P2PNode intefaces, that expose the services that would have been seen in a real scenario. For example, I can see the balance of my channel's peer, but not its balance for other channels etc...
- 
## UVChannel
Single object instance, one per existing channel, accessed from both initiator and peer UVNode threads using synchronized method calls.
- The initiator UVNode creates an instance of a new UVChannel object and call a method of a peer. If criteria and requirement are matched (e.g. sufficient liquidity) the channel reference is stored at each UVNode.

## Topology

# UVNetworkManager 
Represents a vision of the network, abstracting all the info and services that are achieved by broadcasting requests or collecting info via APIs, explorer sites like Amboss, 1ML etc. Example: I’m UVNode X find me a peer with these features

An simple UVDashboard to interact with UVNetworkManager is provided, but different client implementations are possible (e.g. GUI)

# Timechain
This component consistis of that thread modeling a running blockchain, just to model the timing. 
Notice that no actual time is used, but only block-related such as current block number is required to the other components.
Indeed, all the events in the Lightning Network are not related to some real-work time, but to the sequence of block.
This include channel opening confirmation, routing HTLC deadline used in routing etc...
By the decoupling the "real" (e.g.10 minutes) time from the one used in simulation, a large sequence of events can be simulated using a virtual "blocktime"

