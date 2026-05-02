#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

build_dir="$(mktemp -d "${TMPDIR:-/tmp}/uv-determinism-build.XXXXXX")"
trap 'rm -rf "$build_dir"' EXIT

sources_file="$build_dir/sources.txt"
find src tests -name '*.java' | sort > "$sources_file"

javac -d "$build_dir" -cp "src/json-simple-1.1.jar" @"$sources_file"
java -cp "$build_dir:src/json-simple-1.1.jar" DeterminismRegressionTest
