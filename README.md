# UltraViolet
Ultraviolet Lightning Network Topology and Routing  High-level Simulator



# Node
- Node as computing element, e.g., lnd software running, but also operator = management
Management decisions probabilistically characterized 
- UV Abstraction: identifiers should be abstracted to coincide with pubkey  .e.g no need of having IP/tor addresses, since all network communication is done by method calls
- UV Abstraction: pubkey should be derived from a root private key, not required in UV, since trust is not a issue. Nothing is “signed”.
# Channel
- Single object instance, accessed from two different nodes? or two instances representing the concept of channel from each side?
this affects: local/remote balance attributes meanings
- CURRENT IDEA: two different objects, one per side
- The initiator node call a method open_channel
- TBD: do we need the concept of “connected” as direct peers, i.e., having a connection before having a channel in common?


# Topology
- Choose how to manage the topology creation: is gossip announcement required?
- Depends on the level of dynamicity we want to model, examples:
- using from a static channel allocation (pre-simulation)
- starting from the static scenario above, but allowing subsequent opening/closing charaterized with some probabilistic criteria
- CURRENT IDEA: some kind of static approach is required, transient development of a growing network beginning with no nodes is not part of the focus for UV -> we model a topology that has some “stability” and remain quite structurally similar, even if we can introduce some variability, node failures etc.
- Some topology configuration file is needed
- A god-like global class (UVManager) that:
 creates nodes with id and onchain funds, according to some distribution probabilities
- assign a “behavior” to each node and run the associated thread: then each node will invoke methods like “findPeer()”
- starts the thread of running nodes
- represents a vision of the network, abstracting all the info and services that are achieved by broadcasting requests or collecting info via apis, explorer sites like amboss etc…
listen to events:
I’m node X find me a peer with these features etc…
I’m node X I want to open/close a channel to Y
 e.g.: I’m node X I want to move some sats to Y
- The topology seen and managed via UVManager is referred to the channel graph, not the underlying peer-to-peer network
manage the graph structure locally or globally?

# UVManager 
- starts a UVMServer to accept commands (when not used as a library)
UVMClient connects to UVMServer to issue commands, possible different client implementations
# Timing
no actual time, but block-related time
