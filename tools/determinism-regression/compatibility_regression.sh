#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
base=143098246566bd34f811e40138f7b09336f93af1
git cat-file -e "$base:src/misc/UVConfig.java"
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/uv-compatibility.XXXXXX")"
trap 'rm -rf "$build_dir"' EXIT
mkdir -p "$build_dir/original" "$build_dir/old-classes" "$build_dir/new-classes"
git archive "$base" src uv_configs/template.properties | tar -x -C "$build_dir/original"
find "$build_dir/original/src" -name '*.java' > "$build_dir/old-sources.txt"
javac --release "${JAVA_RELEASE:-24}" -d "$build_dir/old-classes" @"$build_dir/old-sources.txt" tests/LegacySnapshotWriter.java
java -cp "$build_dir/old-classes" tests.LegacySnapshotWriter "$build_dir" "$build_dir/original/uv_configs/template.properties"
find src -name '*.java' > "$build_dir/new-sources.txt"
javac --release "${JAVA_RELEASE:-24}" -d "$build_dir/new-classes" @"$build_dir/new-sources.txt" tests/LegacySnapshotCompatibilityTest.java
java -cp "$build_dir/new-classes" tests.LegacySnapshotCompatibilityTest "$build_dir"
