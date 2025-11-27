Change log for the last year (since November 2024):

### **Core Architecture & Network Logic**
*   **Network Simulation Refactoring:**
    *   Renamed `UVManager` to `UVNetwork` to better reflect its role and integrated message delivery directly into it.
    *   Improved bootstrap logic with critical event logging, session-based file naming, and better retry mechanisms for channel attempts.
    *   Enhanced **Pathfinding** by migrating to a strategy pattern, introducing `MiniDijkstra` (replacing UniformCost), and adding support for top-k paths and `LND` routing strategies.
    *   Implemented `UVTransaction` class to encapsulate transaction data, replacing raw mempool logic with priority-based selection.
*   **Timechain & Consensus:**
    *   Refactored Timechain state management for thread safety (`isThreadAlive`, `getStatus`).
    *   Added logic to track transaction broadcast timestamps and block depth validation for confirmations.

### **Features & Enhancements**
*   **Deterministic Simulation:**
    *   Standardized random number generation using configurable seeds for reproducible simulations.
    *   Added support for **deterministic invoice generation** to facilitate testing.
*   **Reporting & Analysis:**
    *   Implemented comprehensive **CSV exports** for nodes, channels, and network traffic.
    *   Added execution time tracking for traffic generation loops.
    *   Introduced a tool to identify unused public keys in files.
*   **UI & Usability:**
    *   Refined menu commands and improved the console display for node/channel statistics.
    *   Standardized channel label formatting and invoice generation prompts.

### **Configuration & Tuning**
*   **Simulation Parameters:**
    *   Introduced `test.properties` for centralized configuration of simulation, LN, and bootstrap settings.
    *   Added a `hubness` parameter to control node profile distribution.
    *   Refined timing parameters: replaced generic P2P periods with specific `node_services_tick_ms` and `gossip_flush_period_ms`.
*   **Fees & Economics:**
    *   Standardized fee handling to consistently use **millisatoshis**.
    *   Updated fee calculation logic to include CLTV deltas for better weight determination in pathfinding.

### **Bug Fixes & Stability**
*   **Concurrency:**
    *   Fixed race conditions in the connection loop and random number generation within `UVNetwork`.
    *   Resolved `InterruptedException` handling during the bootstrap latch wait.
*   **Logic Errors:**
    *   Corrected off-by-one errors in random target selection for payments and channel openings.
    *   Fixed null pointer issues in `acceptChannel` regarding `initiator_id`.
    *   Addressed memory visibility issues by making specific latches transient.

### **Code Quality & Maintenance**
*   **Refactoring:**
    *   Modularized the codebase by moving utilities (`CryptoKit`, `Ripemd160`) to a dedicated `utils` package.
    *   Removed obsolete interfaces (`Timechain` interface, `P2PNode`) and unused configuration files.
    *   Standardized logging across the project, replacing custom print methods with unified loggers.
