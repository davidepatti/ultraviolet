# UltraViolet
Using Lightning Topology and Routing Abstractions VIsible On LEveraged Threads

The goal is to provide a hight-level simulation platform for the Bitcoin Lightning Network hiding the complexity of the underlying elements.
Ultraviolet (UV) makes a massive usage of threads to make each Lightning node living in its own "behavioural space".

The major components of the UV architecture can be summarized as follows:

- UVManager (a global thread, interacting with other thread via syncronized methods when necessary)
- Node (multiple thread instances, one per Ligthning node)
- Timechain (thread)
- Channel (one instance for each channel)

Also, to facilitate the interaction with simulation environment, two further components are being provided:
- UVMServer: used to interact with UVManager via socket
- UVMClient: a command line interface

![what is](uv.png)

# Quick Start

from some terminal:
*java UVManager*

from some other terminal:
*java UVMClient*



# Components

Each component is represented with a Java class that abstract some functional aspects of the related concepts usually found in a Lightning Network environment. 


## Node
Node models both the software element, e.g., lnd implementation running on some hardware, but also the operational/managements aspects.
This includes:

- Node behaviour: opening/closing the channels, choosing peers etc, can be probabilistically characterized when initializing the Node instance. 
- Identity: abstracted to coincide with pubkey,.e.g., no need of having IP/tor addresses, since all network
  communication is done by method calls between synchronized threads. Notice that pubkey should be derived from a root
  private key, not required, since trust is not an issue in UV,i.e., Nothing is “signed”.
## Channel
Single object instance, one per existing channel, accessed from both initiator and peer node threads using synchronized method calls.
- The initiator node creates an instance of a new Channel object and call a method of a peer. If criteria and requirement are matched (e.g. sufficient liquidity) the channel reference is stored at each node.
- TBD: do we need the concept of “connected” as direct peers, i.e., having a connection before having a channel in common?


## Topology
- Choose how to manage the topology creation: is gossip announcement required?
- Depends on the level of dynamicity we want to model, examples:
- using from a static channel allocation (pre-simulation)
- starting from the static scenario above, but allowing subsequent opening/closing charaterized with some probabilistic criteria
- CURRENT IDEA: some kind of static approach is required, transient development of a growing network beginning with no nodes is not part of the focus for UV -> we model a topology that has some “stability” and remain quite structurally similar, even if we can introduce some variability, node failures etc.
- Some topology configuration file is needed 
 e.g.: I’m node X I want to move some sats to Y
- The topology seen and managed via UVManager is referred to the channel graph, not the underlying peer-to-peer network
manage the graph structure locally or globally?

# UVManager 
A global class thread that:
- Bootstrap the network: creates nodes with id and onchain funds, according to some distribution probabilities
- assign a “behavior” to each node and run the associated thread: then each node will invoke methods like “findPeer()”
- starts the thread of running nodes
- represents a vision of the network, abstracting all the info and services that are achieved by broadcasting requests or collecting info via apis, explorer sites like amboss etc…
  listen to events:
  I’m node X find me a peer with these features etc…
  I’m node X I want to open/close a channel to Y
- starts a separate UVMServer thread to accept commands via socket (when not used as a library)

An simple UVMClient to interact with UVMServer is provided, but different client implementations are possible (e.g. GUI)

# Timechain
This component consistis of that thread modeling a running blockchain, just to model the timing. 
Notice that no actual time is used, but only block-related such as current block number is required to the other components.
Indeed, all the events in the Lightning Network are not related to some real-work time, but to the sequence of block.
This include channel opening confirmation, routing HTLC deadline used in routing etc...

