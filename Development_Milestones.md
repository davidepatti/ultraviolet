# Development Milestones

This file is a rolling, month-indexed summary of the major milestones in UltraViolet's development.
Add new updates by inserting a new month section at the top of the list below.

## Index

* [March 2026](#march-2026)
* [February 2026](#february-2026)
* [December 2025](#december-2025)
* [November 2025](#november-2025)
* [September 2025](#september-2025)
* [August 2025](#august-2025)
* [July 2025](#july-2025)
* [June 2025](#june-2025)
* [May 2025](#may-2025)
* [April 2025](#april-2025)
* [March 2025](#march-2025)
* [February 2025](#february-2025)
* [January 2025](#january-2025)
* [December 2024](#december-2024)
* [October 2024](#october-2024)
* [September 2024](#september-2024)
* [August 2024](#august-2024)
* [July 2024](#july-2024)
* [April 2024](#april-2024)
* [March 2024](#march-2024)
* [February 2024](#february-2024)
* [January 2024](#january-2024)
* [November 2023](#november-2023)
* [September 2023](#september-2023)
* [August 2023](#august-2023)
* [July 2023](#july-2023)
* [June 2023](#june-2023)
* [May 2023](#may-2023)
* [April 2023](#april-2023)
* [March 2023](#march-2023)
* [February 2023](#february-2023)
* [January 2023](#january-2023)
* [December 2022](#december-2022)

## March 2026

* Refreshed the terminal UX with denser dashboards, clearer menu rendering, styled prompts, richer screenshots, and a redesigned main menu.
* Added live bootstrap progress feedback, including a growing rainbow progress bar that stays visible at 100% while completion messages print below it.
* Made configuration handling more modular with layered `@include` / `@import` support, a shared template baseline, interactive config selection, and a design-space generator for properties sweeps.
* Expanded routing and experiment controls with configurable pathfinder prompts, configurable LND path-search parameters, and safer invoice path-search isolation.
* Improved reporting and analysis with clearer invoice report semantics, plotting tools, and more explicit documentation of exported metrics.
* Hardened load/import and runtime behavior with better snapshot loading, topology import validation, timeout handling, and cleaner logging around route generation and path search internals.

## February 2026

* Clarified node-view documentation and README wording to better explain the terminal output and project capabilities.

## December 2025

* Updated the project documentation to reference the published journal paper and align the README with the latest citation details.

## November 2025

* Added repository-level milestone tracking and a higher-level changelog summary for the recent development cycle.
* Improved logging performance and reliability by switching to buffered writes and introducing critical bootstrap logs with session-based file naming.
* Strengthened bootstrap stability with better channel-opening failure handling, null `initiator_id` protection, and additional crash and race-condition safeguards.

## September 2025

* Performed a broader cleanup pass across the codebase, reducing redundant initialization, imports, and small inconsistencies ahead of later feature work.

## August 2025

* Made pathfinding limits more configurable by removing hardcoded `max_hops` behavior in `MiniDijkstra`.
* Continued cleanup around configuration and debugging output, including fee-distribution diagnostics.

## July 2025

* Reworked the pathfinding architecture around a strategy-based design, replacing legacy `UniformCost` logic and preparing support for multiple routing strategies.
* Added support for top-k path search and improved fee and CLTV-related path cost handling.
* Improved bootstrap management and reliability, including interrupted latch handling and safer bootstrap behavior under load.
* Continued modularization of the codebase and packaging, including reorganizing utility classes and jar layout.
* Refined timechain status handling and safety checks to improve thread-state consistency during simulation.
* Added a helper tool to find unused synthetic pubkeys in logs and exported text files.

## June 2025

* Tuned the core network simulation parameters to better align timing, bootstrap behavior, and reproducibility across experiments.
* Added `test.properties` and consolidated more of the simulation setup into explicit config files.
* Standardized random number generation and improved thread-safety around RNG usage in `UVNetwork`.
* Improved bootstrap ordering, retries, and logging for node creation and funding confirmation flows.

## May 2025

* Introduced `UVTransaction` and refactored mempool handling around explicit transaction objects instead of looser raw-transaction state.
* Improved transaction confirmation tracking, queue draining, and block-processing logic.
* Tightened bootstrap and channel-acceptance validation, including safer null checks and channel-open exit conditions.
* Continued modular cleanup by moving cryptographic helpers into a utilities package and removing unused abstractions such as the old `Timechain` interface.

## April 2025

* Refined invoice-generation prompts and defaults for better command-line usability.
* Added the `hubness` profile parameter and cleaned up unused configuration files.
* Improved naming clarity around invoice generation and hash handling.

## March 2025

* Expanded invoice reporting and node statistics, including better menu commands and more readable node and channel displays.
* Fixed off-by-one issues in random target and item selection that affected event generation and routing experiments.
* Added deterministic invoice support and cleaned up a number of small code and UI issues around prompt handling.
* Added execution-time tracking for traffic generation and improved invoice-related queue logging.

## February 2025

* Standardized fee handling around millisatoshis across the simulator.
* Refined periodic service and gossip timing configuration, replacing less precise timing controls with clearer parameters.
* Added CSV exports for node statistics and improved report file naming and logging consistency.

## January 2025

* Refreshed project documentation and supporting files as the simulator and paper references matured.

## December 2024

* Renamed and consolidated the core network manager abstraction around `UVNetwork` / `UVManager`.
* Removed obsolete interfaces and simplified the network and timechain abstraction layers.
* Continued early architectural cleanup to make later pathfinding and runtime refactors easier.

## October 2024

* Refined channel creation and random-balance initialization behavior.
* Improved validation and warning behavior around channel setup.

## September 2024

* Improved invoice reporting to capture missing-policy failures more clearly.
* Refined event generation accuracy and log output for routing failures.
* Continued README updates as routing and reporting features matured.

## August 2024

* Improved menu input handling by reusing the main `Scanner` and cleaned up CSV formatting.
* Refined routing logs and queue behavior, including handling for previously unknown routing failure reasons.

## July 2024

* Expanded invoice report generation and routing visibility.
* Improved node-level statistics, path-finding support code, and HTLC expiry handling.
* Continued terminology cleanup around CLTV fields and other Lightning-specific semantics.

## April 2024

* Established the initial public project structure, including the move into `src/`, compilation-script cleanup, and the first README-driven usage flow.
* Added the early README visuals and usage screenshots that made the simulator easier to explore.
* Improved basic node and channel inspection output, channel balance handling, and overall outbound balance calculations.

## March 2024

* Improved bootstrap and report-generation feedback with more detailed progress output and clearer capacity-related reporting.
* Continued refining graph traversal, null-policy handling, node retrieval, and transaction-confirmation logic.
* Reworked several method names and interfaces to make node and channel semantics clearer across the codebase.

## February 2024

* Expanded configuration and bootstrap control with new profile settings, parsed double-valued properties, higher thread limits, and machine-oriented config variants.
* Introduced `NodeProfile` and `DistributionGenerator`, enabling more structured node-profile distributions during bootstrap.
* Added queue-aware funding confirmation handling and more options in the menu, including channel-balance manipulation.
* Improved persistence, alias handling, and startup scripts while simplifying a number of older methods and duplicated config paths.

## January 2024

* Strengthened persistence and queue handling, including safer save behavior, better output file naming, and more complete queue status reporting.
* Expanded statistical reporting and network inspection output.
* Added `max_threads` configuration and refined thread and logging behavior around the timechain and network manager.

## November 2023

* Refined output defaults and fixed additional output bugs after the first larger round of invoice-routing and persistence work.

## September 2023

* Fixed save and restore issues around invoice state and first-hop pending handling.
* Improved invoice failure reporting, HTLC id management, and channel-id semantics.
* Continued cleanup of channel state and routing error reporting.

## August 2023

* Fixed pending-liquidity reservation bugs and improved synchronization around pending HTLC removal.

## July 2023

* Shifted invoice routing responsibility more directly into node-level logic.
* Added more routing and channel-management logs, including warnings around multi-channel edge cases.
* Improved accepted-pending-channel detection and HTLC reservation failure handling.

## June 2023

* Fixed HTLC fulfillment and pending-balance accounting.
* Added multi-threaded invoice-event generation.
* Introduced the JSON library dependency used by later topology-import and tooling features.

## May 2023

* Added support for node profiles during bootstrap and made profile generation dynamic.
* Added multivalue properties and policy generation driven by configuration.
* Improved node-printing readability and removed deprecated files as the configuration model matured.

## April 2023

* Clarified the README and improved public-facing project descriptions.
* Refactored gossip-message handling to make their semantics more explicit and added duplicate detection.
* Fixed fee computation and graph-update behavior for non-local channels.
* Began work on invoice-testing events.

## March 2023

* Completed the first working invoice-routing and HTLC-settlement flow.
* Added timechain-based channel opening and funding management, including `short_channel_id` support and `update_fail_htlc`.
* Improved multi-attempt HTLC routing and broadcasting behavior.

## February 2023

* Introduced the first routing-invoice foundation, including onion layers, HTLC add/fulfill flow, and pathfinding on edges.
* Reworked communication to use messages instead of direct method calls.
* Added persistence for P2P queues, policy records, invoice reception, and the first working HTLC routing flow.
* Continued fixing graph management, concurrent-modification bugs, and routing-related edge cases.

## January 2023

* Introduced the first configuration structures and seed-based configuration.
* Built the early channel graph, gossip propagation, multi-hop broadcasting, and asynchronous bootstrap process.
* Added persistence for saving and loading network state, path-finding methods, and the first routing search implementation.
* Reorganized the architecture around `UVManager`, channel and node abstractions, and eventually a monolithic integrated runtime.
* Improved client and server connection management, memory usage, statistics, and menu behavior.

## December 2022

* Started the project on **December 22, 2022** with the first repository commits.
* Built the initial node, channel, and timechain foundations, including the early thread model and manager/server/client structure.
* Reached the first successful opened-channel flow during the initial holiday development cycle.
