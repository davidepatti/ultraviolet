# UltraViolet

_**U**sing **L**ightning **T**opology and **R**outing **A**bstractions **VI**sible **O**n **LE**vel **T**imechain_

The goal is to provide an open-source extensible high-level simulation platform for the Bitcoin Lightning Network, abstracting some complexity of the underlying elements while still providing a timechain-level accuracy.

Ultraviolet (UV) makes a massive usage of independent threads to make each simulated Lightning Node a living entity in its own "behavioral space".

### Main features of Ultraviolet:

*   _Timechain-level simulation_:  UVNodes communicate with each other by means of Gossip/P2P messages, as specified in the BOLT protocol
*   _Large-scale simulation_: Possibility of instantiating thousands of running nodes, where each can be characterized according to the typical node features (funding, channel sizes distribution, frequency of opening/closing, cltv deltas, fees etc..)
*   _Real Topology Testing_: support for pre-existing topologies imported from a json file, e.g. the output of the "lncli describegraph" command executed in a real node.
*   _Pathfinding and Routing_: it is possible to test the routing of payment invoices, emulating the exchange of HTLC update/fulfill messages to experiment with new potential protocol evolutions.

### Components exposed by UltraViolet

*   UVNode: an object (one per LN node) mapping the main functionalities of a running node, implemented as multiple thread instances (e.g., HTLC forwarding, Gossip messages, channels management)
*   Timechain: a global thread, representing the blockchain time flow
*   UVChannel: one instance for each channel, representing the main attributes of
*   UVNetworkManager: a global thread, interacting with other threads via synchronized methods when necessary, to implement the common services (start/stop p2p, timechain, load/restore status etc.)

### What Ultraviolet Abstracts

The architecture of the Ultraviolet environment hides all the complex aspects of the Lightning Network and Bitcoin Protocols that, while absolutely necessary and relevant in the real-world implementation, are not strictly required to the experimental goals of UV. For example:

*   Transaction representation: protocol events: funding/closing transactions in channel opening/closing are still related to the timechain blocks flow, e.g.  they are associated with some particular transaction and block, but they are not actually signed, because cryptographic verification is abstracted in the exposed model
*   Miners/Mempool policies: All the LN events, e.g. transactions confirmations, gossip, HTLC routing, are related to an underlying thread representing the "new block found" events, but this virtual timechain only contains transactions related to the simulated LN world, while in the reality a lot of other unrelated Bitcoin base layer transactions would be included in blocks.  Thus, while side effects such as temporary mempool congestion could result in a real-world fee competition for blockspace, this would mainly affect the delay between channel opening/closing tx broadcasting and subsequent inclusion in some block, while the focus of UV is about investigating off-chain events related to node routing and topology management.
*   Addressing: Transport layer encryption is not modeled, thus the identity of a node represented by node pubkey is also used to refer to the node location for communications purposes, with no need to resolve the actual IP/Tor address. 
*   Timing: the speed of all events is related and expressed in a scaled, simulated time. So while the real-world timechain produces on average a Bitcoin block every 10 minutes, it would be unpractical to maintain such timing in a simulated scenario. Thus, UV users can scale the timing according to their preference, and all the other events should be referred to such scaling. E.g. if I scale the 10 minutes blocktime to 1 second,  then an LN node that in real-world would flush/broadcast gossip messages every minute should do it every 100ms in the simulation time.

All the above abstractions are not meant to be a definitive design choice, as the modularity of UV allows for extension and implementation of aspects that have been originally intentionally hidden. For example, the Timechain class contains a mempool list, and when a new block is created all the transactions are removed from such list. But just adding a additional "fee" field in the tx would easily allow implementation of a selection policy to model base-layer congestion scenarios. 

## Quick Start

from some terminal:  
_java UVDashboard_

# Components

Each component is represented with a Java class that abstract some functional aspects of the related concepts usually  
found in a Lightning Network environment.

## UVNode

UVNode models both the software element, e.g., lnd implementation running on some hardware, but also the operational/managements aspects.  
This includes:

*   UVNode behaviour: opening/closing the channels, choosing peers etc, can be probabilistically characterized when initializing the UVNode instance.
*   Identity: abstracted to coincide with pubkey,.e.g., no need of having IP/tor addresses, since all network  
    communication is done by method calls between synchronized threads that implement the exchange of BOLT protocol messages. Notice that pubkey should be derived from a root  
    private key, not required, since trust is not an issue in UV,i.e., Nothing is “signed”.
*   Inside each UVNode other nodes as seen throught the "lens" of LNode or P2PNode intefaces, that expose the services that would have been seen in a real scenario. For example, I can see the balance of my channel's peer, but not its balance for other channels etc...

## UVChannel

*   Single object instance, one per existing channel, accessed from both initiator and peer UVNode threads using synchronized method calls.
*   The initiator UVNode creates an instance of a new UVChannel object and call a method of a peer. If criteria and requirement are matched (e.g. sufficient liquidity) the channel reference is stored at each UVNode.

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
