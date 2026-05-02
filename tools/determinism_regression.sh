#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

cat <<'EOF'
UltraViolet deterministic regression suite
==========================================

This runner compiles the simulator and standalone regression harness into a
temporary build directory, then executes tests that avoid threaded live replay.

What is tested:
- config include/override parsing and seeded profile selection;
- seeded distribution generation with explicit Random instances;
- canonical snapshots of a fixed imported topology;
- pathfinding on a fixed graph for BFS, SHORTEST_HOP, MINI_DIJKSTRA, and LND;
- save/load structural round trips from a frozen imported topology;
- network report generation from frozen imported topology state.

What is intentionally not tested here:
- byte-for-byte bootstrap logs;
- threaded P2P/gossip scheduling order;
- parallel invoice-routing outcomes.

EOF

build_dir="$(mktemp -d "${TMPDIR:-/tmp}/uv-determinism-build.XXXXXX")"
trap 'rm -rf "$build_dir"' EXIT
echo "Build directory: $build_dir"

sources_file="$build_dir/sources.txt"
find src tests -name '*.java' | sort > "$sources_file"
source_count="$(wc -l < "$sources_file" | tr -d ' ')"

echo "Compiling $source_count Java source files..."
javac -d "$build_dir" -cp "src/json-simple-1.1.jar" @"$sources_file"

echo "Running DeterminismRegressionTest..."
echo
java -cp "$build_dir:src/json-simple-1.1.jar" DeterminismRegressionTest
