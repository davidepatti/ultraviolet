# Configuration Reference

UltraViolet uses Java `.properties` files. The default config is `uv_configs/template.properties`.

Run with a config:

```bash
java -jar UltraViolet.jar uv_configs/template.properties
```

## Include Support

Configs can include another file:

```properties
@include=template.properties
```

or:

```properties
@import=template.properties
```

Included files are loaded first. Properties in the current file override included values. Includes are resolved relative to the including file.

Example scenario override:

```properties
@include=template.properties

logfile=100.log
seed=0
bootstrap_nodes=100
bootstrap_blocks=100
```

## General Settings

| Property | Meaning |
| --- | --- |
| `debug` | Enables extra debug logging when `true`. This can be expensive on large simulations. |
| `logfile` | Main simulation log path. Existing logs are renamed to `previous.<name>` when the network manager starts. |
| `seed` | Master seed used by the main network RNG and thread-local RNG derivation. It does not guarantee full deterministic replay under threaded execution. |
| `max_threads` | Upper bound used to size bootstrap, P2P, and invoice executor pools. Should stay below the host process/thread limit. |

## Lightning And Protocol Settings

| Property | Meaning |
| --- | --- |
| `to_self_delay` | Delay used in abstract channel-opening messages and policies. |
| `minimum_depth` | Number of confirmations required before a funding transaction is considered confirmed. |
| `p2p_max_hops` | Maximum forwarding count for gossip messages. |
| `p2p_max_age` | Maximum age, in blocks, before gossip messages are considered obsolete. |
| `gossip_flush_size` | Maximum number of gossip messages processed by one node service tick. |

## Pathfinding Settings

| Property | Meaning |
| --- | --- |
| `pathfinding_max_hops` | Maximum hop depth for `mini_dijkstra` and `lnd` pathfinders. |
| `pathfinding_lnd_risk_factor` | Risk factor used by the LND-style cost model. |
| `pathfinding_lnd_base_attempt_cost_msat` | Base attempt cost in millisatoshis for the LND-style cost model. |
| `pathfinding_lnd_attempt_cost_ppm` | Attempt cost rate in parts per million for the LND-style cost model. |
| `pathfinding_lnd_default_path_probability` | Default path probability used by the probabilistic penalty term. |
| `pathfinding_lnd_default_payment_amount_sat` | Default payment amount used when no amount has been injected into the pathfinder. |

## Time Settings

All time settings are in milliseconds.

| Property | Meaning |
| --- | --- |
| `blocktime_ms` | Delay between simulated timechain blocks. |
| `node_services_tick_ms` | Period for scheduled node service execution. |
| `gossip_flush_period_ms` | Minimum wall-clock interval between gossip processing rounds inside a node. |

These values should be changed together. If `blocktime_ms` is lowered but `node_services_tick_ms` and `gossip_flush_period_ms` are not, node services will run less frequently per block.

## Bootstrap Settings

| Property | Meaning |
| --- | --- |
| `bootstrap_nodes` | Number of synthetic nodes to create during bootstrap. |
| `bootstrap_blocks` | Block interval over which node bootstrap start times are distributed. |
| `bootstrap_time_median` | Fraction of `bootstrap_blocks` used as the median for bootstrap start-time sampling. |
| `bootstrap_time_mean` | Fraction of `bootstrap_blocks` used as the mean for bootstrap start-time sampling. |

Example:

```properties
bootstrap_nodes=1000
bootstrap_blocks=1000
bootstrap_time_median=0.5
bootstrap_time_mean=0.5
```

This creates 1000 nodes with start times sampled across 1000 blocks.

## Node Profiles

Profiles use this form:

```properties
profile.<name>.<attribute>=<value>
```

The built-in template defines `small`, `medium`, `hub`, and `default`. The `default` profile is the fallback whenever cumulative probabilities do not select another profile.

| Attribute | Meaning |
| --- | --- |
| `prob` | Probability mass used when assigning node profiles during bootstrap. |
| `hubness` | Probability mass used when selecting target peer profile types for channel openings. |
| `min_funding`, `max_funding` | Initial on-chain funding range in satoshis. |
| `min_channels`, `max_channels` | Range for the target number of channel openings. |
| `min_channel_size`, `max_channel_size` | Bounds for sampled channel sizes. |
| `median_channel_size`, `mean_channel_size` | Shape parameters for channel-size sampling. |
| `min_ppm_fee`, `max_ppm_fee` | Bounds for sampled proportional forwarding fees. |
| `median_ppm_fee`, `mean_ppm_fee` | Shape parameters for ppm-fee sampling. |

Profile probabilities should be interpreted cumulatively in the order used by `UVConfig`. Avoid assuming that bootstrap profile selection is exactly reproducible in full threaded runs.

## Multivalue Settings

| Property | Meaning |
| --- | --- |
| `base_fee_set` | Comma-separated base-fee values, in millisatoshis, sampled when channel policies are created. |

Example:

```properties
base_fee_set=0,100,1000
```

## Practical Scenario Patterns

Small smoke run:

```properties
@include=template.properties
logfile=10.log
seed=0
bootstrap_nodes=10
bootstrap_blocks=100
```

Larger pathfinding experiment:

```properties
@include=template.properties
logfile=1000.log
seed=7
bootstrap_nodes=1000
bootstrap_blocks=1000
pathfinding_max_hops=6
```

Faster but coarser simulation:

```properties
@include=template.properties
blocktime_ms=50
node_services_tick_ms=5
gossip_flush_period_ms=5
```

Use faster settings carefully: they increase scheduling pressure and can expose more thread-order variance.
