# UltraViolet

_**U**tilizing **L**ightning **T**opology and **R**outing **A**bstractions **VI**sible **O**n **LE**vel **T**imechain_

The goal of UltraViolet (UV) is to provide an open-source extensible high-level simulation platform for the Bitcoin
Lightning Network, abstracting some of the major complexities of the underlying elements, while still providing a
timechain-level accuracy.

Ultraviolet (UV) makes a massive usage of independent threads to make each simulated Lightning Node a living entity in
its own "behavioral space".

### Main features of Ultraviolet:

* **Timechain-level simulation**:all the LN BOLT protocol events, Gossip/P2P messages, funding/closing on-chain
  transactions, are simulated according to a scaled-down timechain flow, allowing a fast evaluation of several LN
  scenarios, while still providing a fine-grained block-level "vision" of the interactions with the base Bitcoin layer
* **Large-scale simulation**: Possibility of instantiating thousands of running nodes, where each can be characterized
  according to some common node features (funding, channel sizes distribution, frequency of opening/closing, cltv
  deltas, fees etc..)
* **Real Topology Testing**: support for pre-existing topologies imported from a json file, e.g. using the output of
  the _lncli describegraph_ command executed on a real node.
* **Pathfinding and Routing**: it is possible to test the routing of payment invoices, emulating the exchange of HTLC
  update/fulfill messages to experiment with new potential protocol evolutions, e.g., new routing strategies etc...

## Quick Start

* compile source: `javac *.java`
* the just execute:  _`java UltraViolet` template.properties`_

By customizing the properties file you can experiment different aspects of the deployed network, including nodes behavior, channels,
fee/routing policies, p2p/gossip etc...

Just give at look at the comments in the properties file here to get an idea:
https://github.com/davidepatti/ultraviolet/blob/main/template.properties

Also, you can easily define new properties and refer to them in code!

For further details about usage and commands please refer to the [wikipage](https://github.com/davidepatti/ultraviolet/wiki/UltraViolet-Wiki)


This command will start the main menu:

![](https://user-images.githubusercontent.com/3337669/230136438-e1419961-d2cd-48cd-9983-9d3fc169ce87.png)

