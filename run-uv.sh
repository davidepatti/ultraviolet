#!/usr/bin/env sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

DEFAULT_CONFIG="$SCRIPT_DIR/uv_configs/template.properties"
JSON_JAR="$SCRIPT_DIR/src/json-simple-1.1.jar"
JAVA_RELEASE="${JAVA_RELEASE:-24}"

if [ ! -f "$JSON_JAR" ]; then
  echo "json-simple jar not found: $JSON_JAR" >&2
  exit 1
fi

CONFIG_PATH="${1:-$DEFAULT_CONFIG}"
if [ $# -gt 0 ]; then
  shift
fi

if [ ! -f "$CONFIG_PATH" ]; then
  echo "Config file not found: $CONFIG_PATH" >&2
  exit 1
fi

BUILD_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/ultraviolet-run.XXXXXX")
BUILD_DIR="$BUILD_ROOT/classes"
TMP_SOURCES=$(mktemp)
cleanup() {
  rm -rf "$BUILD_ROOT"
  rm -f "$TMP_SOURCES"
}
trap cleanup EXIT INT TERM

mkdir -p "$BUILD_DIR"
find "$SCRIPT_DIR/src" -name '*.java' | sort > "$TMP_SOURCES"

javac --release "$JAVA_RELEASE" -cp "$JSON_JAR" -d "$BUILD_DIR" @"$TMP_SOURCES"

java -cp "$BUILD_DIR:$JSON_JAR" UltraViolet "$CONFIG_PATH"
